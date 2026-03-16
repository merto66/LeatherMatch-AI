package com.example.src.dto;

/**
 * DTO returned by admin pattern endpoints.
 */
public class PatternDto {

    private long id;
    private String code;
    private String createdAt;
    private int referenceCount;
    private Long thumbnailReferenceId;

    public PatternDto() {}

    public PatternDto(long id, String code, String createdAt, int referenceCount) {
        this.id = id;
        this.code = code;
        this.createdAt = createdAt;
        this.referenceCount = referenceCount;
    }

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public int getReferenceCount() { return referenceCount; }
    public void setReferenceCount(int referenceCount) { this.referenceCount = referenceCount; }

    public Long getThumbnailReferenceId() { return thumbnailReferenceId; }
    public void setThumbnailReferenceId(Long thumbnailReferenceId) { this.thumbnailReferenceId = thumbnailReferenceId; }
}
