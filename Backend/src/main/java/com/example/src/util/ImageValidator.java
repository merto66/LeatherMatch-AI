package com.example.src.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Set;

/**
 * Image file validation utility to prevent malicious file uploads.
 * Validates file size, extension, magic bytes, and actual image content.
 */
@Component
public class ImageValidator {

    private static final Logger log = LoggerFactory.getLogger(ImageValidator.class);

    private static final long MAX_FILE_SIZE = 10L * 1024 * 1024; // 10 MB
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("jpg", "jpeg", "png");

    // Magic bytes for image format detection
    private static final byte[] JPEG_MAGIC = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PNG_MAGIC = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

    // Image dimension constraints
    private static final int MIN_DIMENSION = 50;
    private static final int MAX_DIMENSION = 10000;

    /**
     * Comprehensive validation: size, extension, magic bytes, and ImageIO parsing.
     *
     * @param file MultipartFile to validate
     * @throws IllegalArgumentException if validation fails
     * @throws IOException if file cannot be read
     */
    public void validateImageFile(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException(
                "No file provided. Send a JPG or PNG image as multipart form-data.");
        }

        String originalFilename = file.getOriginalFilename();
        log.debug("Validating image file: {} (size: {} bytes)", originalFilename, file.getSize());

        // 1. Size check
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException(
                String.format("File '%s' exceeds 10 MB limit (size: %d bytes)",
                              originalFilename, file.getSize()));
        }

        if (file.getSize() == 0) {
            throw new IllegalArgumentException("File is empty (0 bytes)");
        }

        // 2. Extension check
        String ext = getExtension(originalFilename);
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw new IllegalArgumentException(
                String.format("Unsupported file format '.%s'. Accepted formats: jpg, jpeg, png", ext));
        }

        // 3. Magic byte verification
        byte[] bytes = file.getBytes();
        if (!hasValidMagicBytes(bytes, ext)) {
            throw new IllegalArgumentException(
                String.format("File content does not match extension '.%s' (magic byte mismatch). " +
                              "The file may be corrupted or renamed from another format.", ext));
        }

        // 4. ImageIO parsing (catches corrupted/malicious files)
        BufferedImage image;
        try (ByteArrayInputStream bis = new ByteArrayInputStream(bytes)) {
            image = ImageIO.read(bis);
            if (image == null) {
                throw new IllegalArgumentException(
                    "File is not a valid image (ImageIO failed to parse). " +
                    "The file may be corrupted or not a real image.");
            }
        }

        // 5. Dimension validation
        int width = image.getWidth();
        int height = image.getHeight();

        if (width < MIN_DIMENSION || height < MIN_DIMENSION) {
            throw new IllegalArgumentException(
                String.format("Image dimensions too small (%dx%d). Minimum: %dx%d",
                              width, height, MIN_DIMENSION, MIN_DIMENSION));
        }

        if (width > MAX_DIMENSION || height > MAX_DIMENSION) {
            throw new IllegalArgumentException(
                String.format("Image dimensions too large (%dx%d). Maximum: %dx%d",
                              width, height, MAX_DIMENSION, MAX_DIMENSION));
        }

        log.debug("Image validated successfully: {} ({}x{}, {} bytes)",
                  originalFilename, width, height, file.getSize());
    }

    /**
     * Checks if the file starts with valid magic bytes for the claimed format.
     */
    private boolean hasValidMagicBytes(byte[] data, String ext) {
        if (data == null || data.length < 8) {
            return false;
        }

        if (ext.equals("jpg") || ext.equals("jpeg")) {
            // JPEG files start with FF D8 FF
            return data.length >= 3 &&
                   data[0] == JPEG_MAGIC[0] &&
                   data[1] == JPEG_MAGIC[1] &&
                   data[2] == JPEG_MAGIC[2];
        }

        if (ext.equals("png")) {
            // PNG files start with 89 50 4E 47 0D 0A 1A 0A
            if (data.length < 8) return false;
            for (int i = 0; i < PNG_MAGIC.length; i++) {
                if (data[i] != PNG_MAGIC[i]) {
                    return false;
                }
            }
            return true;
        }

        return false;
    }

    /**
     * Extracts file extension from filename.
     */
    private String getExtension(String filename) {
        if (filename == null || filename.isBlank()) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        return filename.substring(dot + 1).toLowerCase();
    }
}
