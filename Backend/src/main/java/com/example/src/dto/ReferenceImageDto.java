package com.example.src.dto;

/**
 * DTO returned by admin reference-image endpoints.
 */
public class ReferenceImageDto {

    private long id;
    private long patternId;
    private String patternCode;
    private String imagePath;
    private int embeddingDim;
    private String createdAt;

    public ReferenceImageDto() {}

    public ReferenceImageDto(long id, long patternId, String patternCode,
                              String imagePath, int embeddingDim, String createdAt) {
        this.id = id;
        this.patternId = patternId;
        this.patternCode = patternCode;
        this.imagePath = imagePath;
        this.embeddingDim = embeddingDim;
        this.createdAt = createdAt;
    }

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public long getPatternId() { return patternId; }
    public void setPatternId(long patternId) { this.patternId = patternId; }

    public String getPatternCode() { return patternCode; }
    public void setPatternCode(String patternCode) { this.patternCode = patternCode; }

    public String getImagePath() { return imagePath; }
    public void setImagePath(String imagePath) { this.imagePath = imagePath; }

    public int getEmbeddingDim() { return embeddingDim; }
    public void setEmbeddingDim(int embeddingDim) { this.embeddingDim = embeddingDim; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
}
