package com.example.src.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PathValidator to verify path traversal prevention.
 */
class PathValidatorTest {

    private PathValidator pathValidator;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        pathValidator = new PathValidator();
    }

    @Test
    void testValidPath() {
        Path result = pathValidator.validatePath(tempDir.toString(), "subdir", "file.txt");
        
        assertTrue(result.toString().contains("subdir"));
        assertTrue(result.toString().contains("file.txt"));
        assertTrue(result.startsWith(tempDir));
    }

    @Test
    void testPathTraversalWithDotDot() {
        SecurityException exception = assertThrows(SecurityException.class, () -> {
            pathValidator.validatePath(tempDir.toString(), "..", "etc", "passwd");
        });
        
        assertTrue(exception.getMessage().contains("illegal characters"));
        assertTrue(exception.getMessage().contains(".."));
    }

    @Test
    void testPathTraversalWithSlash() {
        SecurityException exception = assertThrows(SecurityException.class, () -> {
            pathValidator.validatePath(tempDir.toString(), "subdir/../../../etc/passwd");
        });
        
        assertTrue(exception.getMessage().contains("illegal characters"));
    }

    @Test
    void testPathTraversalWithBackslash() {
        SecurityException exception = assertThrows(SecurityException.class, () -> {
            pathValidator.validatePath(tempDir.toString(), "subdir\\..\\..\\etc");
        });
        
        assertTrue(exception.getMessage().contains("illegal characters"));
    }

    @Test
    void testNullSegment() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            pathValidator.validatePath(tempDir.toString(), "valid", null, "file.txt");
        });
        
        assertTrue(exception.getMessage().contains("cannot be null or blank"));
    }

    @Test
    void testBlankSegment() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            pathValidator.validatePath(tempDir.toString(), "valid", "  ", "file.txt");
        });
        
        assertTrue(exception.getMessage().contains("cannot be null or blank"));
    }

    @Test
    void testNullByteInSegment() {
        SecurityException exception = assertThrows(SecurityException.class, () -> {
            pathValidator.validatePath(tempDir.toString(), "file\0.txt");
        });
        
        assertTrue(exception.getMessage().contains("null or newline"));
    }

    @Test
    void testValidateExistingPath() throws IOException {
        // Create a test file
        Path subDir = tempDir.resolve("testdir");
        Files.createDirectories(subDir);
        Path testFile = subDir.resolve("test.jpg");
        Files.writeString(testFile, "test content");

        // Validate existing file
        Path result = pathValidator.validateExistingPath(tempDir.toString(), testFile.toString());
        
        assertTrue(Files.exists(result));
        assertEquals(testFile.toRealPath(), result);
    }

    @Test
    void testValidateNonExistingPath() {
        IOException exception = assertThrows(IOException.class, () -> {
            pathValidator.validateExistingPath(tempDir.toString(), 
                tempDir.resolve("nonexistent.txt").toString());
        });
        
        assertTrue(exception.getMessage().contains("does not exist"));
    }

    @Test
    void testValidateExistingPathOutsideBase() throws IOException {
        // Create a file outside the base directory
        Path outsideDir = tempDir.getParent().resolve("outside");
        Files.createDirectories(outsideDir);
        Path outsideFile = outsideDir.resolve("evil.txt");
        Files.writeString(outsideFile, "evil content");

        SecurityException exception = assertThrows(SecurityException.class, () -> {
            pathValidator.validateExistingPath(tempDir.toString(), outsideFile.toString());
        });
        
        assertTrue(exception.getMessage().contains("outside base directory"));
        
        // Cleanup
        Files.deleteIfExists(outsideFile);
        Files.deleteIfExists(outsideDir);
    }

    @Test
    void testValidatePathWithMultipleSegments() {
        Path result = pathValidator.validatePath(tempDir.toString(), 
            "images", "patterns", "PATTERN_001", "image.jpg");
        
        assertTrue(result.toString().contains("images"));
        assertTrue(result.toString().contains("patterns"));
        assertTrue(result.toString().contains("PATTERN_001"));
        assertTrue(result.toString().contains("image.jpg"));
        assertTrue(result.startsWith(tempDir));
    }

    @Test
    void testValidatePathNormalization() {
        // Even without ".." in segments, the resolved path should be normalized
        Path result = pathValidator.validatePath(tempDir.toString(), "a", "b");
        
        assertEquals(tempDir.resolve("a").resolve("b").normalize(), result);
    }

    @Test
    void testNullBaseDirectory() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            pathValidator.validatePath(null, "file.txt");
        });
        
        assertTrue(exception.getMessage().contains("Base directory cannot be null"));
    }

    @Test
    void testBlankBaseDirectory() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            pathValidator.validatePath("   ", "file.txt");
        });
        
        assertTrue(exception.getMessage().contains("Base directory cannot be null or blank"));
    }
}
