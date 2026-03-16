package com.example.src.dto;

/**
 * Standard error response returned by GlobalExceptionHandler for all error cases.
 * Serialized to JSON by Jackson.
 */
public class ErrorResponse {

    private String error;
    private String message;
    private long timestamp;
    private int status;

    public ErrorResponse() {}

    public ErrorResponse(String error, String message, int status) {
        this.error = error;
        this.message = message;
        this.status = status;
        this.timestamp = System.currentTimeMillis();
    }

    // --- Getters ---

    public String getError() {
        return error;
    }

    public String getMessage() {
        return message;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public int getStatus() {
        return status;
    }

    // --- Setters ---

    public void setError(String error) {
        this.error = error;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    @Override
    public String toString() {
        return "ErrorResponse{" +
                "error='" + error + '\'' +
                ", message='" + message + '\'' +
                ", status=" + status +
                '}';
    }
}
