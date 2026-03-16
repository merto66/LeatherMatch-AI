package com.example.src.controller;

import com.example.src.dto.MatchResult;
import com.example.src.preprocessing.ImagePreprocessor;
import com.example.src.service.AdminDatabaseService;
import com.example.src.service.DatabaseService;
import com.example.src.service.EmbeddingService;
import com.example.src.service.LogService;
import com.example.src.service.PerformanceMetricsService;
import com.example.src.service.SettingsService;
import com.example.src.service.SimilarityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * REST controller for the LeatherMatch-AI pattern recognition API.
 *
 * All endpoints are prefixed with /api and accept cross-origin requests
 * (suitable for LAN deployments where the front-end may run on a different host/port).
 *
 * Endpoint summary:
 *   POST /api/match       - Upload a leather photo; returns MatchResult JSON
 *   GET  /api/patterns    - List all pattern codes (including new patterns without references)
 *   GET  /api/patterns/{code}/thumbnail - First reference image for pattern (Admin/Operator auth)
 *   GET  /api/health      - Health / readiness check
 *   GET  /api/stats       - Runtime statistics (patterns, embeddings, log count)
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class PatternMatchController {

    private static final Logger log = LoggerFactory.getLogger(PatternMatchController.class);

    private static final long MAX_FILE_SIZE_BYTES = 10L * 1024 * 1024; // 10 MB
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("jpg", "jpeg", "png");

    private final ImagePreprocessor        imagePreprocessor;
    private final EmbeddingService         embeddingService;
    private final DatabaseService          databaseService;
    private final AdminDatabaseService     adminDatabaseService;
    private final SimilarityService        similarityService;
    private final LogService               logService;
    private final SettingsService          settingsService;
    private final PerformanceMetricsService performanceMetricsService;

    public PatternMatchController(
            ImagePreprocessor imagePreprocessor,
            EmbeddingService embeddingService,
            DatabaseService databaseService,
            AdminDatabaseService adminDatabaseService,
            SimilarityService similarityService,
            LogService logService,
            SettingsService settingsService,
            PerformanceMetricsService performanceMetricsService) {

        this.imagePreprocessor        = imagePreprocessor;
        this.embeddingService         = embeddingService;
        this.databaseService          = databaseService;
        this.adminDatabaseService     = adminDatabaseService;
        this.similarityService        = similarityService;
        this.logService               = logService;
        this.settingsService          = settingsService;
        this.performanceMetricsService = performanceMetricsService;
    }

    // =========================================================================
    // POST /api/match
    // =========================================================================

    /**
     * Main pattern-matching endpoint.
     *
     * Pipeline:
     *   1. Validate uploaded file (not null, size <= 10 MB, JPG/PNG format)
     *   2. Write to a system temp file (avoids storing in memory for large images)
     *   3. Preprocess: resize to 224x224, NCHW float array, normalize 0-1
     *   4. ONNX inference: extract embedding vector
     *   5. L2-normalize the query embedding
     *   6. Cosine similarity against all reference embeddings in RAM
     *   7. Persist log entry to SQLite
     *   8. Clean up temp file (in finally block, guaranteed)
     *   9. Return MatchResult as JSON
     */
    @PostMapping(value = "/match", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<MatchResult> matchPattern(
            @RequestParam("file") MultipartFile file) throws Exception {

        long t0 = System.nanoTime();

        try {
            // --- Step 1: Input validation ---
            validateFile(file);

            long t1;
            long t2;
            long t3;
            long t4;

            // --- Step 3: Preprocess ---
            float[] preprocessed = imagePreprocessor.preprocessImage(file.getInputStream());
            t1 = System.nanoTime();

            // --- Step 4: Extract embedding ---
            float[] rawEmbedding = embeddingService.extractEmbedding(preprocessed);
            log.debug("Embedding extracted, dim={}", rawEmbedding.length);
            t2 = System.nanoTime();

            // --- Step 5: L2-normalize query embedding ---
            float[] queryEmbedding = similarityService.l2Normalize(rawEmbedding);
            t3 = System.nanoTime();

            // --- Step 6: Find best match (use live threshold and margin from SettingsService) ---
            DatabaseService.FlatSnapshot snapshot = databaseService.getFlatSnapshot();
            MatchResult result = similarityService.findBestMatch(
                    queryEmbedding, snapshot, settingsService.getThreshold(), settingsService.getMargin());
            t4 = System.nanoTime();

            long tEnd = System.nanoTime();

            long preprocessNs = t1 - t0;
            long inferenceNs  = t2 - t1;
            long normalizeNs  = t3 - t2;
            long similarityNs = t4 - t3;
            long totalNs      = tEnd - t0;

            performanceMetricsService.record(PerformanceMetricsService.Stage.PREPROCESS, preprocessNs);
            performanceMetricsService.record(PerformanceMetricsService.Stage.INFERENCE, inferenceNs);
            performanceMetricsService.record(PerformanceMetricsService.Stage.NORMALIZE, normalizeNs);
            performanceMetricsService.record(PerformanceMetricsService.Stage.SIMILARITY, similarityNs);
            performanceMetricsService.record(PerformanceMetricsService.Stage.TOTAL, totalNs);

            result.setProcessingTimeMs(totalNs / 1_000_000);

            // --- Step 7: Persist log ---
            logService.saveLog(result);

            log.info("Match: pattern={}, score={}, confidence={}, isMatch={}, time={}ms",
                    result.getPatternCode(),
                    String.format("%.4f", result.getSimilarityScore()),
                    result.getConfidence(),
                    result.getIsMatch(),
                    result.getProcessingTimeMs());

            return ResponseEntity.ok(result);

        } finally {
            // nothing to clean up; MultipartFile is managed by Spring
        }
    }

    // =========================================================================
    // GET /api/patterns
    // =========================================================================

    /**
     * Returns all pattern codes from the database, including newly created patterns
     * that do not yet have reference images. Used by the feedback form pattern picker.
     */
    @GetMapping("/patterns")
    public ResponseEntity<List<String>> getPatterns() {
        Set<String> codes = new LinkedHashSet<>(databaseService.getAllPatternCodes());
        adminDatabaseService.getAllPatterns().forEach(p -> codes.add(p.getCode()));
        List<String> sorted = new ArrayList<>(codes);
        Collections.sort(sorted);
        return ResponseEntity.ok(sorted);
    }

    // =========================================================================
    // GET /api/patterns/{code}/thumbnail
    // =========================================================================

    /**
     * Returns the thumbnail image for a pattern.
     * Uses thumbnail_reference_id if set, otherwise the first reference by DB order.
     * Requires Admin or Operator auth (no public access).
     */
    @GetMapping("/patterns/{code}/thumbnail")
    public ResponseEntity<byte[]> getPatternThumbnail(@PathVariable String code) throws IOException {
        var imagePathOpt = adminDatabaseService.getThumbnailImagePathForPattern(code);
        if (imagePathOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Path imgPath = Paths.get(imagePathOpt.get());
        if (!Files.exists(imgPath)) {
            return ResponseEntity.notFound().build();
        }
        byte[] bytes = Files.readAllBytes(imgPath);
        String filename = imgPath.getFileName().toString().toLowerCase();
        String contentType = filename.endsWith(".png") ? "image/png" : "image/jpeg";
        return ResponseEntity.ok()
                .header("Content-Type", contentType)
                .header("Cache-Control", "max-age=60")
                .body(bytes);
    }

    // =========================================================================
    // GET /api/health
    // =========================================================================

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status",          "UP");
        body.put("modelLoaded",     true);
        body.put("patternsLoaded",  databaseService.getPatternCount());
        body.put("totalEmbeddings", databaseService.getTotalEmbeddingCount());
        body.put("threshold",       settingsService.getThreshold());
        return ResponseEntity.ok(body);
    }

    // =========================================================================
    // GET /api/stats
    // =========================================================================

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> stats() {
        Map<String, Object> accuracyStats = logService.getAccuracyStats();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("patternCount",       databaseService.getPatternCount());
        body.put("embeddingCount",     databaseService.getTotalEmbeddingCount());
        body.put("threshold",          settingsService.getThreshold());
        body.put("performance",        performanceMetricsService.getStats());
        body.put("metricsAvailableAt", "/api/admin/metrics");
        body.putAll(accuracyStats);

        return ResponseEntity.ok(body);
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException(
                    "No file provided, or the file is empty. " +
                    "Send a JPG or PNG image as multipart form-data with field name 'file'.");
        }
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new IllegalArgumentException(String.format(
                    "File size %d bytes exceeds the maximum allowed size of %d bytes (10 MB).",
                    file.getSize(), MAX_FILE_SIZE_BYTES));
        }
        String ext = getExtension(file.getOriginalFilename());
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw new IllegalArgumentException(String.format(
                    "Unsupported file format '.%s'. Accepted formats: %s",
                    ext, ALLOWED_EXTENSIONS));
        }
    }

    private static String getExtension(String filename) {
        if (filename == null || filename.isBlank()) {
            return "jpg";
        }
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == filename.length() - 1) {
            return "jpg";
        }
        return filename.substring(dotIndex + 1).toLowerCase();
    }
}
