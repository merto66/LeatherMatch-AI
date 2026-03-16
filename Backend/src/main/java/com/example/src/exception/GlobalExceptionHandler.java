package com.example.src.exception;

import ai.onnxruntime.OrtException;
import com.example.src.dto.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.io.IOException;

/**
 * Centralized exception handler for all REST controllers.
 *
 * Maps each exception type to a meaningful HTTP status code and a structured
 * ErrorResponse body, so clients always receive consistent JSON error payloads.
 *
 * Error codes (used in the "error" field of the response body):
 *   INVALID_INPUT         - bad request from the client (wrong file type, missing file, etc.)
 *   FILE_TOO_LARGE        - uploaded file exceeds the configured multipart limit
 *   IMAGE_PROCESSING_ERROR- image could not be loaded or preprocessed
 *   MODEL_INFERENCE_ERROR - ONNX model threw an exception during inference
 *   INTERNAL_ERROR        - any other unexpected server-side exception
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // -------------------------------------------------------------------------
    // Client errors (4xx)
    // -------------------------------------------------------------------------

    /**
     * Handles validation failures: null file, empty file, wrong format, oversized file
     * caught by the controller before the multipart framework sees the size.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Invalid input: {}", ex.getMessage());
        ErrorResponse body = new ErrorResponse("INVALID_INPUT", ex.getMessage(), HttpStatus.BAD_REQUEST.value());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
     * Handles multipart file upload that exceeds spring.servlet.multipart.max-file-size.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleMaxUploadSize(MaxUploadSizeExceededException ex) {
        log.warn("File upload rejected - size exceeds limit: {}", ex.getMessage());
        ErrorResponse body = new ErrorResponse(
                "FILE_TOO_LARGE",
                "Uploaded file exceeds the maximum allowed size of 10 MB",
                HttpStatus.BAD_REQUEST.value());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    // -------------------------------------------------------------------------
    // Image / IO errors (422 Unprocessable Entity)
    // -------------------------------------------------------------------------

    /**
     * Handles failures to load, read, or preprocess an image file.
     */
    @ExceptionHandler(IOException.class)
    public ResponseEntity<ErrorResponse> handleIOException(IOException ex) {
        log.error("Image I/O error: {}", ex.getMessage(), ex);
        ErrorResponse body = new ErrorResponse(
                "IMAGE_PROCESSING_ERROR",
                "Could not process the uploaded image: " + ex.getMessage(),
                HttpStatus.UNPROCESSABLE_ENTITY.value());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
    }

    // -------------------------------------------------------------------------
    // Model inference errors (500)
    // -------------------------------------------------------------------------

    /**
     * Handles exceptions thrown by ONNX Runtime during model inference.
     */
    @ExceptionHandler(OrtException.class)
    public ResponseEntity<ErrorResponse> handleOrtException(OrtException ex) {
        log.error("ONNX model inference failed: {}", ex.getMessage(), ex);
        ErrorResponse body = new ErrorResponse(
                "MODEL_INFERENCE_ERROR",
                "ONNX model inference failed: " + ex.getMessage(),
                HttpStatus.INTERNAL_SERVER_ERROR.value());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    /**
     * Handles internal state issues such as an empty or unloaded database.
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalState(IllegalStateException ex) {
        log.error("Internal state error: {}", ex.getMessage(), ex);
        ErrorResponse body = new ErrorResponse(
                "INTERNAL_ERROR",
                "Server encountered an internal state problem: " + ex.getMessage(),
                HttpStatus.INTERNAL_SERVER_ERROR.value());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    // -------------------------------------------------------------------------
    // Catch-all (500)
    // -------------------------------------------------------------------------

    /**
     * Catches any exception not handled by a more specific handler above.
     * Logs the full stack trace for debugging but returns a generic message to the client.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(Exception ex) {
        log.error("Unhandled exception: {}", ex.getMessage(), ex);
        ErrorResponse body = new ErrorResponse(
                "INTERNAL_ERROR",
                "An unexpected server error occurred. Check server logs for details.",
                HttpStatus.INTERNAL_SERVER_ERROR.value());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
