package com.example.src.dto;

/**
 * DTO for reading and writing runtime settings (e.g. similarity threshold and margin).
 */
public class SettingsDto {

    private double threshold;
    private double margin;

    public SettingsDto() {}

    public SettingsDto(double threshold) {
        this.threshold = threshold;
        this.margin = 0.03;
    }

    public SettingsDto(double threshold, double margin) {
        this.threshold = threshold;
        this.margin = margin;
    }

    public double getThreshold() { return threshold; }
    public void setThreshold(double threshold) { this.threshold = threshold; }
    public double getMargin() { return margin; }
    public void setMargin(double margin) { this.margin = margin; }
}
