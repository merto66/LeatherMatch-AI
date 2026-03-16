package com.example.src.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Manages runtime-configurable settings, primarily the similarity threshold.
 *
 * The threshold is kept in an AtomicReference<Double> so it can be read
 * concurrently by matching requests without any locking, while still being
 * updated atomically by admin operations.
 *
 * Persistence: stored in the SQLite settings table via AdminDatabaseService
 * under key "similarity.threshold".  Falls back to the value from
 * application.properties on first startup.
 */
@Service
public class SettingsService {

    private static final Logger log = LoggerFactory.getLogger(SettingsService.class);

    static final String KEY_THRESHOLD = "similarity.threshold";
    static final String KEY_MARGIN = "similarity.margin";

    private final AdminDatabaseService adminDb;
    private final double configThreshold;
    private final double configMargin;

    private final AtomicReference<Double> threshold = new AtomicReference<>();
    private final AtomicReference<Double> margin = new AtomicReference<>();

    public SettingsService(AdminDatabaseService adminDb,
                           @Value("${similarity.threshold:0.70}") double configThreshold,
                           @Value("${similarity.margin:0.03}") double configMargin) {
        this.adminDb = adminDb;
        this.configThreshold = configThreshold;
        this.configMargin = configMargin;
    }

    @PostConstruct
    public void init() {
        double loadedThreshold = adminDb.getSetting(KEY_THRESHOLD)
                .map(Double::parseDouble)
                .orElse(configThreshold);
        threshold.set(loadedThreshold);
        log.info("Similarity threshold loaded: {}", loadedThreshold);

        double loadedMargin = adminDb.getSetting(KEY_MARGIN)
                .map(Double::parseDouble)
                .orElse(configMargin);
        margin.set(loadedMargin);
        log.info("Similarity margin loaded: {}", loadedMargin);
    }

    /**
     * Returns the current similarity threshold. Lock-free, safe for hot path.
     */
    public double getThreshold() {
        return threshold.get();
    }

    /**
     * Updates the threshold in memory and persists it to SQLite immediately.
     * Takes effect for all subsequent matching requests without a restart.
     *
     * @param value new threshold value; must be in range [0.0, 1.0]
     */
    public void setThreshold(double value) {
        if (value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(
                    "Threshold must be between 0.0 and 1.0, got: " + value);
        }
        threshold.set(value);
        adminDb.setSetting(KEY_THRESHOLD, String.valueOf(value));
        log.info("Similarity threshold updated to {}", value);
    }

    /**
     * Returns the current similarity margin (top-2 gap rule). Lock-free, safe for hot path.
     */
    public double getMargin() {
        return margin.get();
    }

    /**
     * Updates the margin in memory and persists it to SQLite.
     * Takes effect for all subsequent matching requests without a restart.
     *
     * @param value new margin value; must be in range [0.0, 1.0]
     */
    public void setMargin(double value) {
        if (value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(
                    "Margin must be between 0.0 and 1.0, got: " + value);
        }
        margin.set(value);
        adminDb.setSetting(KEY_MARGIN, String.valueOf(value));
        log.info("Similarity margin updated to {}", value);
    }
}
