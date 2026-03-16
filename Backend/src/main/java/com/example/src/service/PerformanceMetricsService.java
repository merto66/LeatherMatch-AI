package com.example.src.service;

import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

@Service
public class PerformanceMetricsService {

    public enum Stage {
        PREPROCESS,
        INFERENCE,
        NORMALIZE,
        SIMILARITY,
        TOTAL
    }

    private static final int WINDOW_SIZE = 200;

    private static class StageWindow {
        final LongAdder count = new LongAdder();
        final LongAdder totalNanos = new LongAdder();
        volatile long maxNanos = 0L;
        final long[] samples = new long[WINDOW_SIZE];
        int index = 0;
    }

    private final EnumMap<Stage, StageWindow> windows = new EnumMap<>(Stage.class);

    public PerformanceMetricsService() {
        for (Stage s : Stage.values()) {
            windows.put(s, new StageWindow());
        }
    }

    public void record(Stage stage, long durationNanos) {
        if (durationNanos < 0) {
            return;
        }
        StageWindow w = windows.get(stage);
        // update aggregates
        w.count.increment();
        w.totalNanos.add(durationNanos);
        if (durationNanos > w.maxNanos) {
            w.maxNanos = durationNanos;
        }
        // record into ring buffer (best-effort, not strictly threadsafe but good enough for metrics)
        int idx = w.index;
        w.samples[idx] = durationNanos;
        w.index = (idx + 1) % WINDOW_SIZE;
    }

    public Map<String, Object> getStats() {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        for (Stage stage : Stage.values()) {
            StageWindow w = windows.get(stage);
            long count = w.count.sum();
            double avgMs = count > 0 ? (w.totalNanos.sum() / 1_000_000.0) / count : 0.0;
            double p95Ms = computeP95Millis(w);
            double maxMs = w.maxNanos / 1_000_000.0;

            Map<String, Object> stageMap = new java.util.LinkedHashMap<>();
            stageMap.put("count", count);
            stageMap.put("avg_ms", avgMs);
            stageMap.put("p95_ms", p95Ms);
            stageMap.put("max_ms", maxMs);

            result.put(stage.name(), stageMap);
        }
        return result;
    }

    private double computeP95Millis(StageWindow w) {
        long[] copy = w.samples.clone();
        int effectiveCount = (int) Math.min(w.count.sum(), WINDOW_SIZE);
        if (effectiveCount == 0) {
            return 0.0;
        }
        java.util.Arrays.sort(copy);
        int start = WINDOW_SIZE - effectiveCount;
        int idx = start + (int) Math.floor(0.95 * (effectiveCount - 1));
        long nanos = copy[idx];
        return nanos / 1_000_000.0;
    }
}

