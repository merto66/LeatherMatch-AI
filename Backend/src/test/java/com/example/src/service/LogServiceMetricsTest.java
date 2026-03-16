package com.example.src.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for LogService.getMatchMetrics() using an in-memory SQLite DB.
 */
@SpringBootTest
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:sqlite:file:${java.io.tmpdir}/LeatherMatchMetricsTest.db",
    "sqlite.logs.path=${java.io.tmpdir}/LeatherMatchMetricsTest.db",
    "sqlite.admin.path=${java.io.tmpdir}/LeatherMatchMetricsTest.db"
})
class LogServiceMetricsTest {

    @Autowired
    private LogService logService;

    @Autowired
    private DataSource dataSource;

    @Test
    void getMatchMetrics_returnsCountsRatesAndLatencyFromLogs() throws Exception {
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.execute(
                "INSERT INTO match_logs (created_at, predicted_pattern, similarity_score, threshold, is_match, confidence, processing_time_ms, second_best_score, margin, decision) " +
                "VALUES " +
                "('2025-01-01T12:00:00', 'P1', 0.85, 0.70, 1, 'HIGH', 50, 0.72, 0.03, 'MATCH'), " +
                "('2025-01-01T12:00:01', 'P2', 0.65, 0.70, 0, 'UNCERTAIN', 80, 0.60, 0.03, 'UNCERTAIN'), " +
                "('2025-01-01T12:00:02', 'P1', 0.90, 0.70, 1, 'HIGH', 60, 0.75, 0.03, 'MATCH')"
            );
        }

        Map<String, Object> metrics = logService.getMatchMetrics();

        assertFalse(metrics.containsKey("error"), "metrics should not contain error: " + metrics.get("error"));

        assertEquals(3L, metrics.get("totalMatches"));
        assertEquals(2L, metrics.get("matchCount"));
        assertEquals(1L, metrics.get("uncertainCount"));
        assertEquals(2.0 / 3.0, (Double) metrics.get("matchRate"), 1e-6);
        assertEquals(1.0 / 3.0, (Double) metrics.get("uncertainRate"), 1e-6);

        @SuppressWarnings("unchecked")
        Map<String, Object> latency = (Map<String, Object>) metrics.get("latency");
        assertNotNull(latency);
        assertTrue((Double) latency.get("avg_ms") > 0);
        assertTrue((Double) latency.get("p95_ms") >= (Double) latency.get("avg_ms"));
        assertEquals(3, latency.get("sampleSize"));

        @SuppressWarnings("unchecked")
        Map<String, String> histogram = (Map<String, String>) metrics.get("scoreHistogram");
        assertNotNull(histogram);
        assertFalse(histogram.isEmpty());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> perPattern = (List<Map<String, Object>>) metrics.get("perPattern");
        assertNotNull(perPattern);
        assertEquals(2, perPattern.size()); // P1 and P2
        long p1Count = perPattern.stream()
            .filter(m -> "P1".equals(m.get("pattern")))
            .map(m -> ((Number) m.get("count")).longValue())
            .findFirst()
            .orElse(0L);
        assertEquals(2L, p1Count);
    }

    @Test
    void getMatchMetrics_emptyTable_returnsZeroRatesAndEmptySample() throws Exception {
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.execute("DELETE FROM match_logs");
        }
        Map<String, Object> metrics = logService.getMatchMetrics();

        assertEquals(0L, metrics.get("totalMatches"));
        assertEquals(0L, metrics.get("matchCount"));
        assertEquals(0L, metrics.get("uncertainCount"));
        assertEquals(0.0, (Double) metrics.get("matchRate"), 1e-6);
        assertEquals(0.0, (Double) metrics.get("uncertainRate"), 1e-6);

        @SuppressWarnings("unchecked")
        Map<String, Object> latency = (Map<String, Object>) metrics.get("latency");
        assertNotNull(latency);
        assertEquals(0.0, latency.get("avg_ms"));
        assertEquals(0, latency.get("sampleSize"));
    }
}
