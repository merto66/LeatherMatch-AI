package com.example.src.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Path validation utility to prevent path traversal attacks.
 * Ensures that all file operations stay within allowed base directories.
 */
@Component
public class PathValidator {

    private static final Logger log = LoggerFactory.getLogger(PathValidator.class);

    /**
     * Validates that the resolved path is within the allowed base directory.
     * Prevents path traversal attacks like "../../../etc/passwd".
     *
     * @param baseDir The allowed base directory (e.g., "C:/LeatherMatch/Leather_Images")
     * @param userInput User-provided path components (e.g., pattern code, filename)
     * @return Validated, normalized Path
     * @throws SecurityException if path escapes base directory or contains illegal characters
     * @throws IllegalArgumentException if path segments are null/blank
     */
    public Path validatePath(String baseDir, String... userInput) {
        if (baseDir == null || baseDir.isBlank()) {
            throw new IllegalArgumentException("Base directory cannot be null or blank");
        }

        Path base = Paths.get(baseDir).toAbsolutePath().normalize();

        // Ensure base directory exists or can be created
        if (!Files.exists(base)) {
            log.debug("Base directory does not exist yet: {}", base);
        }

        Path resolved = base;

        for (String segment : userInput) {
            if (segment == null || segment.isBlank()) {
                throw new IllegalArgumentException("Path segment cannot be null or blank");
            }

            // Block dangerous characters that could be used for traversal
            if (segment.contains("..") || segment.contains("/") || segment.contains("\\")) {
                throw new SecurityException(
                    String.format("Path segment contains illegal characters: '%s'. " +
                                  "Path traversal patterns (.., /, \\) are not allowed.", segment));
            }

            // Block other potentially dangerous characters
            if (segment.contains("\0") || segment.contains("\n") || segment.contains("\r")) {
                throw new SecurityException(
                    String.format("Path segment contains null or newline characters: '%s'", segment));
            }

            resolved = resolved.resolve(segment);
        }

        // Normalize and check containment
        Path normalized = resolved.normalize();

        if (!normalized.startsWith(base)) {
            throw new SecurityException(
                String.format("Path traversal detected: resolved path '%s' escapes base directory '%s'",
                              normalized, base));
        }

        log.debug("Path validated successfully: {}", normalized);
        return normalized;
    }

    /**
     * Validates an existing file path (e.g., from database).
     * Ensures the path exists and is within the allowed base directory.
     *
     * @param baseDir The allowed base directory
     * @param storedPath Path stored in database or configuration
     * @return Validated, canonical Path
     * @throws SecurityException if path is outside base directory
     * @throws IOException if path does not exist or cannot be resolved
     */
    public Path validateExistingPath(String baseDir, String storedPath) throws IOException {
        if (baseDir == null || baseDir.isBlank()) {
            throw new IllegalArgumentException("Base directory cannot be null or blank");
        }
        if (storedPath == null || storedPath.isBlank()) {
            throw new IllegalArgumentException("Stored path cannot be null or blank");
        }

        Path base = Paths.get(baseDir).toAbsolutePath().normalize();
        Path file = Paths.get(storedPath);

        // Check if file exists before calling toRealPath
        if (!Files.exists(file)) {
            throw new IOException("File does not exist: " + storedPath);
        }

        Path canonical = file.toRealPath();

        if (!canonical.startsWith(base)) {
            throw new SecurityException(
                String.format("Stored path '%s' is outside base directory '%s'", canonical, base));
        }

        log.debug("Existing path validated successfully: {}", canonical);
        return canonical;
    }

    /**
     * Validates a path without enforcing that it exists (useful for paths being created).
     * Still performs containment and traversal checks.
     *
     * @param baseDir The allowed base directory
     * @param segments Path segments to validate
     * @return Validated, normalized Path (may not exist yet)
     */
    public Path validateNewPath(String baseDir, String... segments) {
        return validatePath(baseDir, segments);
    }
}
