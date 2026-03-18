package com.example.src.controller;

import com.example.src.dto.PatternDto;
import com.example.src.dto.ReferenceImageDto;
import com.example.src.dto.SettingsDto;
import com.example.src.preprocessing.ImagePreprocessor;
import com.example.src.service.AdminDatabaseService;
import com.example.src.service.DatabaseService;
import com.example.src.service.EmbeddingService;
import com.example.src.service.FeedbackDatabaseService;
import com.example.src.service.LogService;
import com.example.src.service.SettingsService;
import com.example.src.service.SimilarityService;
import com.example.src.util.PathValidator;
import com.example.src.util.ImageValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;

/**
 * Admin REST controller — all endpoints are under /api/admin and require
 * HTTP Basic Auth (ROLE_ADMIN as configured in SecurityConfig).
 *
 * Endpoints:
 *   GET    /api/admin/patterns                         - list patterns
 *   POST   /api/admin/patterns                         - create pattern
 *   DELETE /api/admin/patterns/{id}                    - delete pattern + refs
 *   GET    /api/admin/patterns/{patternId}/references  - list refs for pattern
 *   POST   /api/admin/patterns/{patternId}/references  - upload ref image(s)
 *   DELETE /api/admin/references/{referenceId}         - delete single ref
 *   GET    /api/admin/settings                         - get settings
 *   PUT    /api/admin/settings/threshold               - update threshold
 *   PUT    /api/admin/settings/margin                  - update margin
 *   GET    /api/admin/metrics                          - log-derived metrics (counts, latency, histogram, per-pattern)
 *   GET    /api/admin/logs                             - paginated filtered logs
 *   GET    /api/admin/feedback                         - list feedback (status, limit, offset)
 *   GET    /api/admin/feedback/{id}/image              - stream feedback image
 *   POST   /api/admin/feedback/{id}/approve            - approve feedback
 *   POST   /api/admin/feedback/{id}/reject             - reject feedback
 *   POST   /api/admin/feedback/{id}/approve-and-add-reference - approve and add image as reference
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private static final long   MAX_FILE_SIZE_BYTES = 10L * 1024 * 1024;
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("jpg", "jpeg", "png");

    private final AdminDatabaseService   adminDb;
    private final DatabaseService        databaseService;
    private final SettingsService        settingsService;
    private final FeedbackDatabaseService feedbackDb;
    private final ImagePreprocessor      imagePreprocessor;
    private final EmbeddingService       embeddingService;
    private final SimilarityService      similarityService;
    private final LogService             logService;
    private final PathValidator          pathValidator;
    private final ImageValidator         imageValidator;
    private final String                 leatherImagesPath;
    private final String                 feedbackImagesPath;

    public AdminController(
            AdminDatabaseService adminDb,
            DatabaseService databaseService,
            SettingsService settingsService,
            FeedbackDatabaseService feedbackDb,
            ImagePreprocessor imagePreprocessor,
            EmbeddingService embeddingService,
            SimilarityService similarityService,
            LogService logService,
            PathValidator pathValidator,
            ImageValidator imageValidator,
            @Value("${leather.images.path}") String leatherImagesPath,
            @Value("${feedback.images.path}") String feedbackImagesPath) {

        this.adminDb           = adminDb;
        this.databaseService   = databaseService;
        this.settingsService   = settingsService;
        this.feedbackDb       = feedbackDb;
        this.imagePreprocessor = imagePreprocessor;
        this.embeddingService  = embeddingService;
        this.similarityService = similarityService;
        this.logService        = logService;
        this.pathValidator     = pathValidator;
        this.imageValidator    = imageValidator;
        this.leatherImagesPath = leatherImagesPath;
        this.feedbackImagesPath = feedbackImagesPath;
    }

    // =========================================================================
    // Patterns
    // =========================================================================

    @GetMapping("/patterns")
    public ResponseEntity<List<PatternDto>> listPatterns() {
        List<PatternDto> patterns = adminDb.getAllPatterns();
        // Override referenceCount with the in-memory cache count so that
        // patterns loaded from pattern_database.json show their real count
        // even before their images are imported into SQLite.
        List<PatternDto> enriched = new ArrayList<>();
        for (PatternDto p : patterns) {
            List<float[]> cached = databaseService.getPatternEmbeddings(p.getCode());
            int cacheCount = (cached != null) ? cached.size() : 0;
            int displayCount = Math.max(p.getReferenceCount(), cacheCount);
            PatternDto dto = new PatternDto(p.getId(), p.getCode(), p.getCreatedAt(), displayCount);
            dto.setThumbnailReferenceId(p.getThumbnailReferenceId());
            enriched.add(dto);
        }
        return ResponseEntity.ok(enriched);
    }

    @PostMapping("/patterns")
    public ResponseEntity<PatternDto> createPattern(@RequestBody Map<String, String> body) {
        String code = body.get("code");
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Pattern code must not be blank");
        }
        PatternDto created = adminDb.createPattern(code);
        log.info("Admin: created pattern id={} code={}", created.getId(), created.getCode());
        return ResponseEntity.status(201).body(created);
    }

    @DeleteMapping("/patterns/{id}")
    public ResponseEntity<Map<String, Object>> deletePattern(@PathVariable long id) {
        Optional<PatternDto> opt = adminDb.getPatternById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        PatternDto pattern = opt.get();

        // Delete from DB (cascades to reference_images via FK)
        List<String> imagePaths = adminDb.deletePattern(id);

        // Remove from in-memory cache
        databaseService.removePattern(pattern.getCode());

        // Optionally delete files from disk (best-effort, don't fail request on IO error)
        int deletedFiles = 0;
        for (String p : imagePaths) {
            try {
                // Validate path before deletion
                Path validatedPath = pathValidator.validateExistingPath(leatherImagesPath, p);
                Files.deleteIfExists(validatedPath);
                deletedFiles++;
            } catch (IOException | SecurityException e) {
                log.warn("Could not delete image file '{}': {}", p, e.getMessage());
            }
        }

        log.info("Admin: deleted pattern id={} code={}, {} refs removed, {} files deleted",
                id, pattern.getCode(), imagePaths.size(), deletedFiles);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("deleted",       true);
        resp.put("patternCode",   pattern.getCode());
        resp.put("refsRemoved",   imagePaths.size());
        resp.put("filesDeleted",  deletedFiles);
        return ResponseEntity.ok(resp);
    }

    // =========================================================================
    // Reference images
    // =========================================================================

    @GetMapping("/patterns/{patternId}/references")
    public ResponseEntity<List<ReferenceImageDto>> listReferences(@PathVariable long patternId) {
        if (adminDb.getPatternById(patternId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(adminDb.getReferencesForPattern(patternId));
    }

    @PutMapping("/patterns/{patternId}/thumbnail-reference")
    public ResponseEntity<Map<String, Object>> setThumbnailReference(
            @PathVariable long patternId,
            @RequestBody Map<String, Object> body) throws IOException {
        Optional<PatternDto> opt = adminDb.getPatternById(patternId);
        if (opt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Object refIdObj = body.get("referenceId");
        if (refIdObj == null) {
            throw new IllegalArgumentException("referenceId is required");
        }
        long referenceId = refIdObj instanceof Number ? ((Number) refIdObj).longValue() : Long.parseLong(refIdObj.toString());
        List<ReferenceImageDto> refs = adminDb.getReferencesForPattern(patternId);
        ReferenceImageDto selected = refs.stream().filter(r -> r.getId() == referenceId).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Reference " + referenceId + " does not belong to this pattern"));
        adminDb.setThumbnailReference(patternId, referenceId);
        // File-based thumbnail: copy selected image to _thumbnail.<ext> (Match screen reads this).
        // The file copy is best-effort: the DB record is already updated above.
        // If the copy fails (path validation, missing file, IO) we log a warning
        // but still return success so the UI is not blocked.
        try {
            Path patternDir = pathValidator.validatePath(leatherImagesPath, opt.get().getCode());
            String ext = getExtension(Paths.get(selected.getImagePath()).getFileName().toString());
            Path thumbFile = patternDir.resolve("_thumbnail." + ext);
            Files.createDirectories(patternDir);
            Path sourcePath = pathValidator.validateExistingPath(leatherImagesPath, selected.getImagePath());
            Files.copy(sourcePath, thumbFile, StandardCopyOption.REPLACE_EXISTING);
            log.info("Admin: set thumbnail for pattern {} to reference {} (file copied to {})",
                    patternId, referenceId, thumbFile.getFileName());
        } catch (SecurityException | IOException e) {
            log.warn("Admin: thumbnail DB record updated for pattern {} ref {} but file copy failed: {}",
                    patternId, referenceId, e.getMessage());
        }
        return ResponseEntity.ok(Map.of("thumbnailReferenceId", referenceId));
    }

    /**
     * Scans Leather_Images/<PATTERN_CODE>/ on disk and imports any image files
     * that are not yet registered in the reference_images table.
     * Each new file is processed through the ONNX pipeline to generate its embedding.
     * Already-registered paths are skipped (idempotent).
     *
     * This is a one-time migration for patterns whose embeddings came from
     * pattern_database.json (Python tooling) and whose images exist on disk
     * but have never been imported into SQLite.
     */
    @PostMapping("/patterns/{patternId}/import-from-disk")
    public ResponseEntity<Map<String, Object>> importFromDisk(
            @PathVariable long patternId) throws Exception {

        Optional<PatternDto> opt = adminDb.getPatternById(patternId);
        if (opt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        PatternDto pattern = opt.get();

        Path patternDir = pathValidator.validatePath(leatherImagesPath, pattern.getCode());
        if (!Files.exists(patternDir) || !Files.isDirectory(patternDir)) {
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("imported", 0);
            resp.put("skipped",  0);
            resp.put("message",  "Directory not found: " + patternDir);
            return ResponseEntity.ok(resp);
        }

        // Collect already-registered paths to avoid duplicates
        List<ReferenceImageDto> existing = adminDb.getReferencesForPattern(patternId);
        Set<String> registeredPaths = new HashSet<>();
        for (ReferenceImageDto r : existing) {
            registeredPaths.add(Paths.get(r.getImagePath()).toAbsolutePath().toString());
        }

        int imported = 0;
        int skipped  = 0;
        List<String> errors = new ArrayList<>();

        try (var stream = Files.list(patternDir)) {
            List<Path> imageFiles = stream
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase();
                        return name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png");
                    })
                    .sorted()
                    .toList();

            for (Path imgPath : imageFiles) {
                String absPath = imgPath.toAbsolutePath().toString();
                if (registeredPaths.contains(absPath)) {
                    skipped++;
                    continue;
                }
                try {
                    float[] preprocessed = imagePreprocessor.preprocessImage(absPath);
                    float[] rawEmbedding = embeddingService.extractEmbedding(preprocessed);
                    float[] normalized   = similarityService.l2Normalize(rawEmbedding);

                    adminDb.addReferenceImage(patternId, pattern.getCode(), absPath, normalized);
                    // Do NOT call addEmbeddingToCache here — the JSON already populated the cache.
                    // rebuildPatternCache() is called once after the loop to switch the cache
                    // from JSON-based to SQLite-based, eliminating any duplication.
                    imported++;
                    log.info("Imported: {} for pattern '{}'", imgPath.getFileName(), pattern.getCode());
                } catch (Exception e) {
                    log.warn("Failed to import '{}': {}", absPath, e.getMessage());
                    errors.add(imgPath.getFileName() + ": " + e.getMessage());
                }
            }
        }

        // Rebuild cache from SQLite so it replaces the JSON-sourced embeddings exactly once.
        // This eliminates the duplication that would occur if both JSON and SQLite were kept.
        if (imported > 0) {
            databaseService.rebuildPatternCache(pattern.getCode());
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("imported", imported);
        resp.put("skipped",  skipped);
        if (!errors.isEmpty()) {
            resp.put("errors", errors);
        }
        log.info("Import-from-disk for pattern '{}': {} imported, {} skipped",
                pattern.getCode(), imported, skipped);
        return ResponseEntity.ok(resp);
    }

    /**
     * Uploads one or more reference images for a pattern.
     * For each file:
     *   1. Validate
     *   2. Save to Leather_Images/<PATTERN_CODE>/<uuid>.<ext>
     *   3. Preprocess → ONNX embedding → L2 normalize
     *   4. Persist to SQLite
     *   5. Update in-memory cache
     */
    @PostMapping(value = "/patterns/{patternId}/references",
                 consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<List<ReferenceImageDto>> uploadReferences(
            @PathVariable long patternId,
            @RequestParam("files") List<MultipartFile> files) throws Exception {

        Optional<PatternDto> opt = adminDb.getPatternById(patternId);
        if (opt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        PatternDto pattern = opt.get();

        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("No files provided");
        }

        List<ReferenceImageDto> results = new ArrayList<>();

        for (MultipartFile file : files) {
            imageValidator.validateImageFile(file);  // Comprehensive validation
            String ext = getExtension(file.getOriginalFilename());

            // --- Save file to disk ---
            String filename = UUID.randomUUID() + "." + ext;
            Path patternDir = pathValidator.validatePath(leatherImagesPath, pattern.getCode());
            Files.createDirectories(patternDir);
            Path savedPath = pathValidator.validatePath(leatherImagesPath, pattern.getCode(), filename);
            file.transferTo(savedPath.toFile());

            // --- Preprocess → embed → normalize ---
            float[] preprocessed = imagePreprocessor.preprocessImage(savedPath.toString());
            float[] rawEmbedding = embeddingService.extractEmbedding(preprocessed);
            float[] normalized   = similarityService.l2Normalize(rawEmbedding);

            // --- Persist to SQLite ---
            ReferenceImageDto dto = adminDb.addReferenceImage(
                    patternId, pattern.getCode(), savedPath.toString(), normalized);

            // --- Update in-memory cache ---
            databaseService.addEmbeddingToCache(pattern.getCode(), normalized);

            results.add(dto);
            log.info("Admin: added reference id={} for pattern '{}' ({})",
                    dto.getId(), pattern.getCode(), filename);
        }

        return ResponseEntity.status(201).body(results);
    }

    @DeleteMapping("/references/{referenceId}")
    public ResponseEntity<Map<String, Object>> deleteReference(@PathVariable long referenceId) {
        Optional<String> imagePath = adminDb.deleteReference(referenceId);
        if (imagePath.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        // We need the pattern code to rebuild the cache.
        // At this point the row is already deleted; iterate all loaded patterns to find ownership,
        // OR do a targeted SQLite rebuild. We call rebuildPatternCache for ALL patterns that the
        // deleted image might belong to — since we have the path we can infer the code from the
        // directory structure: Leather_Images/<CODE>/<filename>
        String code = inferPatternCodeFromPath(imagePath.get());
        if (code != null) {
            databaseService.rebuildPatternCache(code);
        }

        // Best-effort delete the file from disk
        boolean fileDeleted = false;
        try {
            Path validatedPath = pathValidator.validateExistingPath(leatherImagesPath, imagePath.get());
            fileDeleted = Files.deleteIfExists(validatedPath);
        } catch (IOException | SecurityException e) {
            log.warn("Could not delete image file '{}': {}", imagePath.get(), e.getMessage());
        }

        log.info("Admin: deleted reference id={}, fileDeleted={}", referenceId, fileDeleted);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("deleted",      true);
        resp.put("referenceId",  referenceId);
        resp.put("fileDeleted",  fileDeleted);
        return ResponseEntity.ok(resp);
    }

    // =========================================================================
    // Reference image preview
    // =========================================================================

    /**
     * Streams the raw image file for a reference image so the frontend can
     * show a thumbnail.  The endpoint is under /api/admin/** but SecurityConfig
     * permits /api/admin/references/*\/image without auth so that <img> tags work.
     */
    @GetMapping("/references/{referenceId}/image")
    public ResponseEntity<byte[]> getReferenceImage(@PathVariable long referenceId) throws IOException {
        Optional<String> pathOpt = adminDb.getImagePathForReference(referenceId);
        if (pathOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Path imgPath = pathValidator.validateExistingPath(leatherImagesPath, pathOpt.get());
        if (!Files.exists(imgPath)) {
            return ResponseEntity.notFound().build();
        }
        byte[] bytes = Files.readAllBytes(imgPath);
        String filename = imgPath.getFileName().toString().toLowerCase();
        String contentType = filename.endsWith(".png") ? "image/png" : "image/jpeg";
        return ResponseEntity.ok()
                .header("Content-Type", contentType)
                .header("Cache-Control", "max-age=86400")
                .body(bytes);
    }

    // =========================================================================
    // Settings
    // =========================================================================

    @GetMapping("/settings")
    public ResponseEntity<SettingsDto> getSettings() {
        return ResponseEntity.ok(new SettingsDto(settingsService.getThreshold(), settingsService.getMargin()));
    }

    @PutMapping("/settings/threshold")
    public ResponseEntity<SettingsDto> updateThreshold(@RequestBody Map<String, Double> body) {
        Double value = body.get("threshold");
        if (value == null) {
            throw new IllegalArgumentException("Request body must contain 'threshold' field");
        }
        settingsService.setThreshold(value);
        log.info("Admin: threshold updated to {}", value);
        return ResponseEntity.ok(new SettingsDto(settingsService.getThreshold(), settingsService.getMargin()));
    }

    @PutMapping("/settings/margin")
    public ResponseEntity<SettingsDto> updateMargin(@RequestBody Map<String, Double> body) {
        Double value = body.get("margin");
        if (value == null) {
            throw new IllegalArgumentException("Request body must contain 'margin' field");
        }
        settingsService.setMargin(value);
        log.info("Admin: margin updated to {}", value);
        return ResponseEntity.ok(new SettingsDto(settingsService.getThreshold(), settingsService.getMargin()));
    }

    // =========================================================================
    // Metrics (log-derived)
    // =========================================================================

    @GetMapping("/metrics")
    public ResponseEntity<Map<String, Object>> getMetrics() {
        Map<String, Object> logs = logService.getMatchMetrics();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("logs", logs);
        return ResponseEntity.ok(body);
    }

    // =========================================================================
    // Logs
    // =========================================================================

    /**
     * Returns paginated match logs with optional filters.
     *
     * Query params:
     *   limit           - max rows (default 50)
     *   offset          - skip rows (default 0)
     *   pattern         - filter by predicted_pattern (exact)
     *   isMatch         - filter by is_match (true/false)
     */
    @GetMapping("/logs")
    public ResponseEntity<Map<String, Object>> getLogs(
            @RequestParam(defaultValue = "50")  int limit,
            @RequestParam(defaultValue = "0")   int offset,
            @RequestParam(required = false)     String pattern,
            @RequestParam(required = false)     Boolean isMatch) {

        List<Map<String, Object>> logs = logService.getFilteredLogs(limit, offset, pattern, isMatch);
        long total = logService.getTotalLogCount();

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("total",  total);
        resp.put("limit",  limit);
        resp.put("offset", offset);
        resp.put("data",   logs);
        return ResponseEntity.ok(resp);
    }

    // =========================================================================
    // Feedback (operator corrections, admin review)
    // =========================================================================

    @GetMapping("/feedback")
    public ResponseEntity<Map<String, Object>> getFeedback(
            @RequestParam(defaultValue = "PENDING") String status,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {

        if (!Set.of("PENDING", "APPROVED", "REJECTED").contains(status)) {
            throw new IllegalArgumentException("status must be PENDING, APPROVED, or REJECTED");
        }
        int safeLimit = Math.min(Math.max(limit, 1), 100);
        int safeOffset = Math.max(offset, 0);

        List<Map<String, Object>> data = feedbackDb.getFeedbackList(status, safeLimit, safeOffset);
        long total = feedbackDb.getFeedbackCount(status);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("total", total);
        resp.put("limit", safeLimit);
        resp.put("offset", safeOffset);
        resp.put("data", data);
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/feedback/{id}/image")
    public ResponseEntity<byte[]> getFeedbackImage(@PathVariable long id) throws IOException {
        Optional<String> pathOpt = feedbackDb.getImagePathForFeedback(id);
        if (pathOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Path imgPath = pathValidator.validateExistingPath(feedbackImagesPath, pathOpt.get());
        if (!Files.exists(imgPath)) {
            return ResponseEntity.notFound().build();
        }
        byte[] bytes = Files.readAllBytes(imgPath);
        String filename = imgPath.getFileName().toString().toLowerCase();
        String contentType = filename.endsWith(".png") ? "image/png" : "image/jpeg";
        return ResponseEntity.ok()
                .header("Content-Type", contentType)
                .header("Cache-Control", "max-age=86400")
                .body(bytes);
    }

    @PostMapping("/feedback/{id}/approve")
    public ResponseEntity<Map<String, Object>> approveFeedback(@PathVariable long id) {
        Optional<Map<String, Object>> opt = feedbackDb.getFeedbackById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        if (!"PENDING".equals(opt.get().get("status"))) {
            throw new IllegalArgumentException("Feedback already reviewed");
        }
        String reviewer = SecurityContextHolder.getContext().getAuthentication().getName();
        boolean updated = feedbackDb.updateStatus(id, "APPROVED", reviewer);
        if (!updated) {
            return ResponseEntity.internalServerError().build();
        }
        log.info("Admin: approved feedback id={} by {}", id, reviewer);
        return ResponseEntity.ok(Map.of("status", "APPROVED", "id", id));
    }

    @PostMapping("/feedback/{id}/reject")
    public ResponseEntity<Map<String, Object>> rejectFeedback(@PathVariable long id) {
        Optional<Map<String, Object>> opt = feedbackDb.getFeedbackById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        if (!"PENDING".equals(opt.get().get("status"))) {
            throw new IllegalArgumentException("Feedback already reviewed");
        }
        String reviewer = SecurityContextHolder.getContext().getAuthentication().getName();
        boolean updated = feedbackDb.updateStatus(id, "REJECTED", reviewer);
        if (!updated) {
            return ResponseEntity.internalServerError().build();
        }
        log.info("Admin: rejected feedback id={} by {}", id, reviewer);
        return ResponseEntity.ok(Map.of("status", "REJECTED", "id", id));
    }

    @PostMapping("/feedback/{id}/approve-and-add-reference")
    public ResponseEntity<Map<String, Object>> approveAndAddReference(@PathVariable long id) throws Exception {
        Optional<Map<String, Object>> opt = feedbackDb.getFeedbackById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> fb = opt.get();
        if (!"PENDING".equals(fb.get("status"))) {
            throw new IllegalArgumentException("Feedback already reviewed");
        }
        String operatorSelectedPattern = (String) fb.get("operatorSelectedPattern");
        if (operatorSelectedPattern == null || operatorSelectedPattern.isBlank()) {
            throw new IllegalArgumentException("Feedback has no operator-selected pattern");
        }

        Optional<PatternDto> patternOpt = adminDb.getPatternByCode(operatorSelectedPattern);
        if (patternOpt.isEmpty()) {
            throw new IllegalArgumentException("Pattern '" + operatorSelectedPattern + "' does not exist. Create it first.");
        }
        PatternDto pattern = patternOpt.get();

        Optional<String> imagePathOpt = feedbackDb.getImagePathForFeedback(id);
        if (imagePathOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Path sourcePath = pathValidator.validateExistingPath(feedbackImagesPath, imagePathOpt.get());
        if (!Files.exists(sourcePath)) {
            return ResponseEntity.notFound().build();
        }

        String ext = getExtension(sourcePath.getFileName().toString());
        String filename = UUID.randomUUID() + "." + ext;
        Path patternDir = pathValidator.validatePath(leatherImagesPath, pattern.getCode());
        Files.createDirectories(patternDir);
        Path destPath = pathValidator.validatePath(leatherImagesPath, pattern.getCode(), filename);
        Files.copy(sourcePath, destPath, StandardCopyOption.REPLACE_EXISTING);

        float[] preprocessed = imagePreprocessor.preprocessImage(destPath.toString());
        float[] rawEmbedding = embeddingService.extractEmbedding(preprocessed);
        float[] normalized = similarityService.l2Normalize(rawEmbedding);

        adminDb.addReferenceImage(pattern.getId(), pattern.getCode(), destPath.toString(), normalized);
        databaseService.addEmbeddingToCache(pattern.getCode(), normalized);

        String reviewer = SecurityContextHolder.getContext().getAuthentication().getName();
        feedbackDb.updateStatus(id, "APPROVED", reviewer);

        log.info("Admin: approved feedback id={} and added as reference for pattern '{}' by {}",
                id, pattern.getCode(), reviewer);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("status", "APPROVED");
        resp.put("id", id);
        resp.put("addedAsReference", true);
        resp.put("patternCode", pattern.getCode());
        return ResponseEntity.ok(resp);
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private static String getExtension(String filename) {
        if (filename == null || filename.isBlank()) return "jpg";
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) return "jpg";
        return filename.substring(dot + 1).toLowerCase();
    }

    /**
     * Infers the pattern code from the stored image path.
     * Expected structure: .../Leather_Images/<PATTERN_CODE>/<filename>
     */
    private String inferPatternCodeFromPath(String imagePath) {
        try {
            Path p = Paths.get(imagePath);
            if (p.getParent() != null) {
                return p.getParent().getFileName().toString();
            }
        } catch (Exception e) {
            log.warn("Could not infer pattern code from path '{}': {}", imagePath, e.getMessage());
        }
        return null;
    }
}
