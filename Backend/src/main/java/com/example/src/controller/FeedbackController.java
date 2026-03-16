package com.example.src.controller;

import com.example.src.service.FeedbackDatabaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Operator-facing controller for submitting match feedback when the result is wrong.
 * POST /api/feedback — requires authenticated user (operator or admin).
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class FeedbackController {

    private static final Logger log = LoggerFactory.getLogger(FeedbackController.class);

    private static final long MAX_FILE_SIZE_BYTES = 10L * 1024 * 1024; // 10 MB
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("jpg", "jpeg", "png");

    private final FeedbackDatabaseService feedbackDb;
    private final String feedbackImagesPath;

    public FeedbackController(FeedbackDatabaseService feedbackDb,
                              @Value("${feedback.images.path}") String feedbackImagesPath) {
        this.feedbackDb = feedbackDb;
        this.feedbackImagesPath = feedbackImagesPath;
    }

    /**
     * Submits operator feedback for a wrong match.
     * Multipart form: file, predictedPattern, predictedScore, threshold, margin,
     * operatorSelectedPattern, note (optional), secondBestScore (optional).
     */
    @PostMapping(value = "/feedback", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> submitFeedback(
            @RequestParam("file") MultipartFile file,
            @RequestParam("predictedPattern") String predictedPattern,
            @RequestParam("predictedScore") double predictedScore,
            @RequestParam("threshold") double threshold,
            @RequestParam("margin") double margin,
            @RequestParam("operatorSelectedPattern") String operatorSelectedPattern,
            @RequestParam(value = "note", required = false) String note,
            @RequestParam(value = "secondBestScore", required = false) Double secondBestScore) throws IOException {

        validateFile(file);
        if (operatorSelectedPattern == null || operatorSelectedPattern.isBlank()) {
            throw new IllegalArgumentException("operatorSelectedPattern must not be blank");
        }

        Path dir = Paths.get(feedbackImagesPath);
        Files.createDirectories(dir);

        String ext = getExtension(file.getOriginalFilename());
        String filename = UUID.randomUUID() + "." + ext;
        Path savedPath = dir.resolve(filename);
        file.transferTo(savedPath.toFile());

        long id = feedbackDb.insertFeedback(
                savedPath.toAbsolutePath().toString(),
                predictedPattern,
                predictedScore,
                secondBestScore,
                threshold,
                margin,
                operatorSelectedPattern.trim(),
                note);

        log.info("Feedback submitted: id={}, predicted={}, selected={}",
                id, predictedPattern, operatorSelectedPattern);

        return ResponseEntity.status(201).body(Map.of(
                "id", id,
                "status", "PENDING",
                "message", "Feedback submitted for admin review"));
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException(
                    "No file provided. Send a JPG or PNG image as multipart form-data with field name 'file'.");
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
        if (filename == null || filename.isBlank()) return "jpg";
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) return "jpg";
        return filename.substring(dot + 1).toLowerCase();
    }
}
