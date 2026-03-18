package com.example.src.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ImageValidator to verify file upload validation.
 */
class ImageValidatorTest {

    private ImageValidator imageValidator;

    // Valid JPEG magic bytes (FF D8 FF)
    private static final byte[] VALID_JPEG_HEADER = new byte[]{
        (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0,
        0x00, 0x10, 0x4A, 0x46, 0x49, 0x46
    };

    // Valid PNG magic bytes (89 50 4E 47 0D 0A 1A 0A)
    private static final byte[] VALID_PNG_HEADER = new byte[]{
        (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };

    @BeforeEach
    void setUp() {
        imageValidator = new ImageValidator();
    }

    @Test
    void testNullFile() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            imageValidator.validateImageFile(null);
        });
        
        assertTrue(exception.getMessage().contains("No file provided"));
    }

    @Test
    void testEmptyFile() {
        MockMultipartFile emptyFile = new MockMultipartFile(
            "file", "test.jpg", "image/jpeg", new byte[0]);
        
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            imageValidator.validateImageFile(emptyFile);
        });
        
        assertTrue(exception.getMessage().contains("empty (0 bytes)"));
    }

    @Test
    void testFileTooLarge() {
        byte[] largeContent = new byte[11 * 1024 * 1024]; // 11 MB
        MockMultipartFile largeFile = new MockMultipartFile(
            "file", "large.jpg", "image/jpeg", largeContent);
        
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            imageValidator.validateImageFile(largeFile);
        });
        
        assertTrue(exception.getMessage().contains("exceeds 10 MB limit"));
    }

    @Test
    void testInvalidExtension() {
        MockMultipartFile invalidFile = new MockMultipartFile(
            "file", "malware.exe", "application/exe", new byte[100]);
        
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            imageValidator.validateImageFile(invalidFile);
        });
        
        assertTrue(exception.getMessage().contains("Unsupported file format"));
        assertTrue(exception.getMessage().contains(".exe"));
    }

    @Test
    void testMagicByteMismatch_JpegExtensionPngContent() {
        // File claims to be JPEG but has PNG magic bytes
        MockMultipartFile mismatchFile = new MockMultipartFile(
            "file", "fake.jpg", "image/jpeg", VALID_PNG_HEADER);
        
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            imageValidator.validateImageFile(mismatchFile);
        });
        
        assertTrue(exception.getMessage().contains("magic byte mismatch"));
    }

    @Test
    void testMagicByteMismatch_PngExtensionJpegContent() {
        // File claims to be PNG but has JPEG magic bytes
        MockMultipartFile mismatchFile = new MockMultipartFile(
            "file", "fake.png", "image/png", VALID_JPEG_HEADER);
        
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            imageValidator.validateImageFile(mismatchFile);
        });
        
        assertTrue(exception.getMessage().contains("magic byte mismatch"));
    }

    @Test
    void testInvalidImageContent() {
        // Has correct JPEG magic bytes but is not a valid image
        byte[] fakeJpeg = new byte[200];
        System.arraycopy(VALID_JPEG_HEADER, 0, fakeJpeg, 0, VALID_JPEG_HEADER.length);
        // Fill rest with garbage
        for (int i = VALID_JPEG_HEADER.length; i < fakeJpeg.length; i++) {
            fakeJpeg[i] = (byte) (i % 256);
        }
        
        MockMultipartFile invalidImage = new MockMultipartFile(
            "file", "corrupt.jpg", "image/jpeg", fakeJpeg);
        
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            imageValidator.validateImageFile(invalidImage);
        });
        
        assertTrue(exception.getMessage().contains("not a valid image") || 
                   exception.getMessage().contains("ImageIO failed"));
    }

    @Test
    void testFilenameWithoutExtension() {
        MockMultipartFile noExtFile = new MockMultipartFile(
            "file", "noextension", "image/jpeg", new byte[100]);
        
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            imageValidator.validateImageFile(noExtFile);
        });
        
        assertTrue(exception.getMessage().contains("Unsupported file format"));
    }

    @Test
    void testFilenameWithMultipleDots() {
        // Should only consider the last extension
        byte[] content = new byte[100];
        System.arraycopy(VALID_JPEG_HEADER, 0, content, 0, VALID_JPEG_HEADER.length);
        
        MockMultipartFile multiDotFile = new MockMultipartFile(
            "file", "image.backup.jpg", "image/jpeg", content);
        
        // Should not throw - extension is .jpg
        assertDoesNotThrow(() -> {
            // This will fail at ImageIO parsing stage, but extension check passes
            try {
                imageValidator.validateImageFile(multiDotFile);
            } catch (IllegalArgumentException e) {
                // Expected: invalid image content
                assertFalse(e.getMessage().contains("Unsupported file format"));
            }
        });
    }

    @Test
    void testCaseSensitivityInExtension() {
        byte[] content = new byte[100];
        System.arraycopy(VALID_JPEG_HEADER, 0, content, 0, VALID_JPEG_HEADER.length);
        
        // Should accept .JPG, .Jpg, .JPEG, etc.
        MockMultipartFile upperCaseFile = new MockMultipartFile(
            "file", "IMAGE.JPG", "image/jpeg", content);
        
        assertDoesNotThrow(() -> {
            try {
                imageValidator.validateImageFile(upperCaseFile);
            } catch (IllegalArgumentException e) {
                // Expected: invalid image content, but NOT extension error
                assertFalse(e.getMessage().contains("Unsupported file format"));
            }
        });
    }

    @Test
    void testZeroByteFile() {
        MockMultipartFile zeroFile = new MockMultipartFile(
            "file", "empty.jpg", "image/jpeg", new byte[0]);
        
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            imageValidator.validateImageFile(zeroFile);
        });
        
        assertTrue(exception.getMessage().contains("empty") || 
                   exception.getMessage().contains("0 bytes"));
    }

    @Test
    void testFileWithOnlyMagicBytes() {
        // File with valid magic bytes but no actual image data
        MockMultipartFile onlyHeaderFile = new MockMultipartFile(
            "file", "header_only.jpg", "image/jpeg", VALID_JPEG_HEADER);
        
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            imageValidator.validateImageFile(onlyHeaderFile);
        });
        
        assertTrue(exception.getMessage().contains("not a valid image") || 
                   exception.getMessage().contains("ImageIO failed"));
    }

    @Test
    void testNullFilename() {
        MockMultipartFile nullNameFile = new MockMultipartFile(
            "file", null, "image/jpeg", new byte[100]);
        
        // Should handle gracefully and reject due to no extension
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            imageValidator.validateImageFile(nullNameFile);
        });
        
        assertTrue(exception.getMessage().contains("Unsupported file format"));
    }

    @Test
    void testBlankFilename() {
        MockMultipartFile blankNameFile = new MockMultipartFile(
            "file", "  ", "image/jpeg", new byte[100]);
        
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            imageValidator.validateImageFile(blankNameFile);
        });
        
        assertTrue(exception.getMessage().contains("Unsupported file format"));
    }

    @Test
    void testJpegVariants() {
        byte[] content = new byte[100];
        System.arraycopy(VALID_JPEG_HEADER, 0, content, 0, VALID_JPEG_HEADER.length);
        
        // Test .jpg
        MockMultipartFile jpgFile = new MockMultipartFile(
            "file", "image.jpg", "image/jpeg", content);
        
        // Test .jpeg
        MockMultipartFile jpegFile = new MockMultipartFile(
            "file", "image.jpeg", "image/jpeg", content);
        
        // Both should pass extension and magic byte checks (fail at ImageIO)
        assertDoesNotThrow(() -> {
            try {
                imageValidator.validateImageFile(jpgFile);
            } catch (IllegalArgumentException e) {
                assertFalse(e.getMessage().contains("Unsupported file format"));
                assertFalse(e.getMessage().contains("magic byte"));
            }
        });
        
        assertDoesNotThrow(() -> {
            try {
                imageValidator.validateImageFile(jpegFile);
            } catch (IllegalArgumentException e) {
                assertFalse(e.getMessage().contains("Unsupported file format"));
                assertFalse(e.getMessage().contains("magic byte"));
            }
        });
    }
}
