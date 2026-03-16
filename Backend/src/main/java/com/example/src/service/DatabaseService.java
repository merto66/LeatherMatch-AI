package com.example.src.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Loads and caches reference embeddings for the similarity matching engine.
 *
 * Sources (merged at startup, in order):
 *   1. pattern_database.json  — legacy embeddings built by Python tooling (read-only)
 *   2. SQLite reference_images table — admin-managed embeddings added via the admin panel
 *
 * Thread safety:
 *   - ConcurrentHashMap for the outer map (pattern-code → embedding list).
 *   - CopyOnWriteArrayList per pattern so concurrent reads (matching) never block,
 *     while infrequent admin writes (add/remove) replace the list copy.
 *   - Deletion rebuilds a single pattern's list from SQLite to stay consistent.
 *
 * IMPORTANT: all embeddings stored here must already be L2-normalized.
 */
@Service
public class DatabaseService {

    private static final Logger log = LoggerFactory.getLogger(DatabaseService.class);

    private final String databasePath;
    private final AdminDatabaseService adminDatabaseService;

    /**
     * Key: pattern code (e.g. "PB-746")
     * Value: CopyOnWriteArrayList of L2-normalized embedding float arrays
     */
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<float[]>> patternEmbeddings =
            new ConcurrentHashMap<>();

    /**
     * Flat, array-based snapshot for hot path similarity search.
     * Immutable structure, swapped atomically when the cache changes.
     */
    public record FlatSnapshot(String[] patternCodes, float[][][] embeddings) {}

    private final AtomicReference<FlatSnapshot> flatSnapshot = new AtomicReference<>();

    /**
     * Cached total embedding count to avoid stream traversal on every stats request.
     */
    private final AtomicInteger totalEmbeddingCount = new AtomicInteger(0);

    public DatabaseService(@Value("${pattern.database.path}") String databasePath,
                           AdminDatabaseService adminDatabaseService) {
        this.databasePath = databasePath;
        this.adminDatabaseService = adminDatabaseService;
    }

    /**
     * Loads embeddings from JSON and merges admin SQLite embeddings.
     * Called by Spring after all dependencies are constructed.
     */
    @PostConstruct
    public void loadDatabase() throws IOException {
        // --- Step 1: load legacy JSON ---
        log.info("Loading pattern database from: {}", databasePath);
        File dbFile = new File(databasePath);
        if (dbFile.exists()) {
            ObjectMapper mapper = new ObjectMapper();
            Map<String, List<List<Double>>> rawData = mapper.readValue(
                    dbFile, new TypeReference<Map<String, List<List<Double>>>>() {});

            for (Map.Entry<String, List<List<Double>>> entry : rawData.entrySet()) {
                String patternCode = entry.getKey();
                CopyOnWriteArrayList<float[]> embeddings = new CopyOnWriteArrayList<>();
                for (List<Double> rawEmb : entry.getValue()) {
                    float[] emb = new float[rawEmb.size()];
                    for (int i = 0; i < rawEmb.size(); i++) {
                        emb[i] = rawEmb.get(i).floatValue();
                    }
                    embeddings.add(emb);
                    totalEmbeddingCount.incrementAndGet();
                }
                patternEmbeddings.put(patternCode, embeddings);

                // Register in SQLite so legacy patterns appear in the admin panel
                adminDatabaseService.ensurePatternExists(patternCode);
            }
            log.info("JSON database loaded: {} patterns", patternEmbeddings.size());
        } else {
            log.warn("Pattern database JSON not found at '{}', starting with empty cache.", databasePath);
        }

        // --- Step 2: merge admin SQLite embeddings ---
        // If SQLite has entries for a pattern, they REPLACE the JSON entries for that pattern.
        // This prevents duplication after "import from disk" has been run: the same images
        // would otherwise be counted twice (once from JSON, once from SQLite).
        Map<String, List<AdminDatabaseService.EmbeddingEntry>> adminEmbeddings =
                adminDatabaseService.loadAllEmbeddings();

        int adminCount = 0;
        for (Map.Entry<String, List<AdminDatabaseService.EmbeddingEntry>> entry : adminEmbeddings.entrySet()) {
            String code = entry.getKey();
            List<AdminDatabaseService.EmbeddingEntry> entries = entry.getValue();
            if (!entries.isEmpty()) {
                // SQLite is now authoritative for this pattern — replace any JSON embeddings
                CopyOnWriteArrayList<float[]> list = new CopyOnWriteArrayList<>();
                for (AdminDatabaseService.EmbeddingEntry e : entries) {
                    list.add(e.getEmbedding());
                    adminCount++;
                }
                // adjust total count: remove previous count for this pattern, then add new
                CopyOnWriteArrayList<float[]> previous = patternEmbeddings.put(code, list);
                if (previous != null) {
                    totalEmbeddingCount.addAndGet(-previous.size());
                }
                totalEmbeddingCount.addAndGet(list.size());
            }
        }
        log.info("Admin SQLite embeddings loaded: {} embeddings across {} patterns (replace JSON where present)",
                adminCount, adminEmbeddings.size());

        rebuildFlatSnapshot();

        log.info("Total cache: {} patterns, {} embeddings", getPatternCount(), getTotalEmbeddingCount());

        if (log.isDebugEnabled()) {
            patternEmbeddings.forEach((code, embs) ->
                    log.debug("  Pattern {}: {} embeddings, dim={}",
                            code, embs.size(), embs.isEmpty() ? 0 : embs.get(0).length));
        }
    }

    // -------------------------------------------------------------------------
    // Dynamic cache operations (used by AdminController)
    // -------------------------------------------------------------------------

    /**
     * Adds a single L2-normalized embedding to the in-memory cache.
     * Thread-safe: CopyOnWriteArrayList.add() is atomic.
     */
    public void addEmbeddingToCache(String patternCode, float[] embedding) {
        patternEmbeddings.computeIfAbsent(patternCode, k -> new CopyOnWriteArrayList<>())
                         .add(embedding);
        totalEmbeddingCount.incrementAndGet();
        log.debug("Added embedding to cache for pattern '{}', new count={}",
                patternCode, patternEmbeddings.get(patternCode).size());
        rebuildFlatSnapshot();
    }

    /**
     * Rebuilds the cache for a single pattern from the current SQLite state.
     * Used after a reference image is deleted.  The rebuild is isolated to
     * the affected pattern so matching of other patterns is not impacted.
     */
    public void rebuildPatternCache(String patternCode) {
        List<float[]> fresh = adminDatabaseService.loadEmbeddingsForPattern(patternCode);
        CopyOnWriteArrayList<float[]> previous = patternEmbeddings.get(patternCode);

        if (fresh.isEmpty()) {
            if (previous != null) {
                totalEmbeddingCount.addAndGet(-previous.size());
            }
            patternEmbeddings.remove(patternCode);
            log.debug("Pattern '{}' has no admin embeddings; removed from cache (JSON embeddings gone too).", patternCode);
        } else {
            CopyOnWriteArrayList<float[]> list = new CopyOnWriteArrayList<>(fresh);
            patternEmbeddings.put(patternCode, list);

            if (previous != null) {
                totalEmbeddingCount.addAndGet(-previous.size());
            }
            totalEmbeddingCount.addAndGet(list.size());

            log.debug("Rebuilt cache for pattern '{}': {} embeddings", patternCode, fresh.size());
        }

        rebuildFlatSnapshot();
    }

    /**
     * Removes a pattern entirely from the in-memory cache.
     * Called when a pattern is deleted via the admin panel.
     */
    public void removePattern(String patternCode) {
        CopyOnWriteArrayList<float[]> removed = patternEmbeddings.remove(patternCode);
        if (removed != null) {
            totalEmbeddingCount.addAndGet(-removed.size());
        }
        log.debug("Removed pattern '{}' from cache", patternCode);
        rebuildFlatSnapshot();
    }

    // -------------------------------------------------------------------------
    // Read-only accessors (used by SimilarityService / PatternMatchController)
    // -------------------------------------------------------------------------

    public List<float[]> getPatternEmbeddings(String patternCode) {
        return patternEmbeddings.get(patternCode);
    }

    /**
     * Returns an unmodifiable snapshot view of the full database.
     * ConcurrentHashMap reads are safe; individual lists are CopyOnWriteArrayList.
     */
    public Map<String, List<float[]>> getAllPatterns() {
        Map<String, List<float[]>> snapshot = new HashMap<>(patternEmbeddings.size());
        patternEmbeddings.forEach((k, v) -> snapshot.put(k, Collections.unmodifiableList(v)));
        return Collections.unmodifiableMap(snapshot);
    }

    public Set<String> getAllPatternCodes() {
        return Collections.unmodifiableSet(patternEmbeddings.keySet());
    }

    public int getTotalEmbeddingCount() {
        return totalEmbeddingCount.get();
    }

    public int getPatternCount() {
        return patternEmbeddings.size();
    }

    /**
     * Returns the current flat snapshot for high-performance similarity search.
     */
    public FlatSnapshot getFlatSnapshot() {
        return flatSnapshot.get();
    }

    /**
     * Rebuilds the flat snapshot from the current patternEmbeddings map.
     * Called after any structural mutation of the cache.
     */
    private void rebuildFlatSnapshot() {
        if (patternEmbeddings.isEmpty()) {
            flatSnapshot.set(null);
            return;
        }

        String[] codes = patternEmbeddings.keySet().toArray(new String[0]);
        float[][][] allEmbeddings = new float[codes.length][][];

        for (int i = 0; i < codes.length; i++) {
            List<float[]> list = patternEmbeddings.get(codes[i]);
            if (list == null || list.isEmpty()) {
                allEmbeddings[i] = new float[0][];
            } else {
                allEmbeddings[i] = list.toArray(new float[0][]);
            }
        }

        flatSnapshot.set(new FlatSnapshot(codes, allEmbeddings));
    }
}
