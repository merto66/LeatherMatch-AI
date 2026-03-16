package com.example.src.dto;

import java.util.Map;

/**
 * Represents the result of a pattern matching operation.
 * Returned by the /api/match endpoint as JSON.
 *
 * Confidence levels:
 *   HIGH      - similarityScore >= 0.80
 *   MEDIUM    - similarityScore >= threshold but < 0.80
 *   UNCERTAIN - similarityScore < threshold (isMatch = false)
 *
 * Note: getIsMatch() is used instead of isMatch() so that Jackson serializes
 * the field as "isMatch" (strips the "get" prefix → "isMatch").
 */
public class MatchResult {

    private String patternCode;
    private double similarityScore;
    private Boolean isMatch;
    private String confidence;
    private long processingTimeMs;
    private Map<String, Double> allPatternScores;
    private double threshold;
    private Double secondBestScore;
    private Double marginUsed;
    private String decision;

    public MatchResult() {}

    // --- Getters ---

    public String getPatternCode() {
        return patternCode;
    }

    public double getSimilarityScore() {
        return similarityScore;
    }

    /** Jackson serializes getIsMatch() as "isMatch" in JSON output. */
    public Boolean getIsMatch() {
        return isMatch;
    }

    public String getConfidence() {
        return confidence;
    }

    public long getProcessingTimeMs() {
        return processingTimeMs;
    }

    public Map<String, Double> getAllPatternScores() {
        return allPatternScores;
    }

    public double getThreshold() {
        return threshold;
    }

    public Double getSecondBestScore() {
        return secondBestScore;
    }

    public Double getMarginUsed() {
        return marginUsed;
    }

    public String getDecision() {
        return decision;
    }

    // --- Setters ---

    public void setPatternCode(String patternCode) {
        this.patternCode = patternCode;
    }

    public void setSimilarityScore(double similarityScore) {
        this.similarityScore = similarityScore;
    }

    public void setIsMatch(Boolean isMatch) {
        this.isMatch = isMatch;
    }

    public void setConfidence(String confidence) {
        this.confidence = confidence;
    }

    public void setProcessingTimeMs(long processingTimeMs) {
        this.processingTimeMs = processingTimeMs;
    }

    public void setAllPatternScores(Map<String, Double> allPatternScores) {
        this.allPatternScores = allPatternScores;
    }

    public void setThreshold(double threshold) {
        this.threshold = threshold;
    }

    public void setSecondBestScore(Double secondBestScore) {
        this.secondBestScore = secondBestScore;
    }

    public void setMarginUsed(Double marginUsed) {
        this.marginUsed = marginUsed;
    }

    public void setDecision(String decision) {
        this.decision = decision;
    }

    @Override
    public String toString() {
        return "MatchResult{" +
                "patternCode='" + patternCode + '\'' +
                ", similarityScore=" + String.format("%.4f", similarityScore) +
                ", isMatch=" + isMatch +
                ", confidence='" + confidence + '\'' +
                ", processingTimeMs=" + processingTimeMs +
                ", threshold=" + threshold +
                ", secondBestScore=" + secondBestScore +
                ", marginUsed=" + marginUsed +
                ", decision='" + decision + '\'' +
                '}';
    }
}
