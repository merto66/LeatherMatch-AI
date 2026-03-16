package com.example.src.service;

import com.example.src.dto.MatchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Persists match results to a local SQLite database using plain JDBC.
 *
 * Table schema (auto-created on first startup, then columns added via migration):
 *   match_logs(
 *     id                 INTEGER PRIMARY KEY AUTOINCREMENT,
 *     created_at         TEXT    NOT NULL,
 *     predicted_pattern  TEXT,
 *     similarity_score   REAL,
 *     threshold          REAL,
 *     is_match           INTEGER,   -- 1 = true, 0 = false
 *     confidence         TEXT,
 *     processing_time_ms INTEGER,
 *     second_best_score  REAL,
 *     margin             REAL,
 *     decision           TEXT       -- MATCH | UNCERTAIN
 *   )
 *
 * SQLite JDBC driver (org.xerial:sqlite-jdbc) is loaded automatically from
 * the classpath when DriverManager.getConnection("jdbc:sqlite:...") is called.
 */
@Service
public class LogService {

    private static final Logger log = LoggerFactory.getLogger(LogService.class);

    private static final String TABLE_DDL =
            "CREATE TABLE IF NOT EXISTS match_logs (" +
            "  id                 INTEGER PRIMARY KEY AUTOINCREMENT," +
            "  created_at         TEXT    NOT NULL," +
            "  predicted_pattern  TEXT," +
            "  similarity_score   REAL," +
            "  threshold          REAL," +
            "  is_match           INTEGER," +
            "  confidence         TEXT," +
            "  processing_time_ms INTEGER" +
            ")";

    private static final String INSERT_SQL =
            "INSERT INTO match_logs " +
            "(created_at, predicted_pattern, similarity_score, threshold, is_match, confidence, processing_time_ms, second_best_score, margin, decision) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private final String dbPath;
    private final DataSource dataSource;

    public LogService(@Value("${sqlite.logs.path}") String dbPath,
                      DataSource dataSource) {
        this.dbPath = dbPath;
        this.dataSource = dataSource;
        initializeDatabase();
    }

    // -------------------------------------------------------------------------
    // Initialisation
    // -------------------------------------------------------------------------

    private void initializeDatabase() {
        // Ensure the parent directory exists (e.g. data/ next to the project)
        File dbFile = new File(dbPath);
        File parentDir = dbFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            boolean created = parentDir.mkdirs();
            if (!created) {
                log.warn("Could not create parent directory for SQLite database: {}", parentDir.getAbsolutePath());
            }
        }

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(TABLE_DDL);
            addColumnIfMissing(conn, "match_logs", "second_best_score", "REAL");
            addColumnIfMissing(conn, "match_logs", "margin", "REAL");
            addColumnIfMissing(conn, "match_logs", "decision", "TEXT");
            log.info("SQLite database ready: {}", dbPath);
        } catch (SQLException e) {
            log.error("Failed to initialize SQLite database at '{}': {}", dbPath, e.getMessage(), e);
            throw new RuntimeException("SQLite initialization failed: " + e.getMessage(), e);
        }
    }

    private void addColumnIfMissing(Connection conn, String table, String column, String type) throws SQLException {
        try {
            conn.createStatement().execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
            log.debug("Added column {} to {}", column, table);
        } catch (SQLException e) {
            if (e.getMessage() != null && e.getMessage().contains("duplicate column name")) {
                // Column already exists (e.g. after upgrade)
                return;
            }
            throw e;
        }
    }

    // -------------------------------------------------------------------------
    // Write
    // -------------------------------------------------------------------------

    /**
     * Persists a match result to the match_logs table asynchronously.
     * Errors are logged but not propagated so that a DB failure never breaks
     * the main API response. Executed on the logExecutor thread pool.
     */
    @Async("logExecutor")
    public CompletableFuture<Void> saveLog(MatchResult result) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(INSERT_SQL)) {

            stmt.setString(1, LocalDateTime.now().toString());
            stmt.setString(2, result.getPatternCode());
            stmt.setDouble(3, result.getSimilarityScore());
            stmt.setDouble(4, result.getThreshold());
            stmt.setInt(5, Boolean.TRUE.equals(result.getIsMatch()) ? 1 : 0);
            stmt.setString(6, result.getConfidence());
            stmt.setLong(7, result.getProcessingTimeMs());
            if (result.getSecondBestScore() != null) {
                stmt.setDouble(8, result.getSecondBestScore());
            } else {
                stmt.setNull(8, java.sql.Types.DOUBLE);
            }
            if (result.getMarginUsed() != null) {
                stmt.setDouble(9, result.getMarginUsed());
            } else {
                stmt.setNull(9, java.sql.Types.DOUBLE);
            }
            stmt.setString(10, result.getDecision());

            stmt.executeUpdate();
            log.debug("Log saved: pattern={}, score={}, confidence={}",
                    result.getPatternCode(),
                    String.format("%.4f", result.getSimilarityScore()),
                    result.getConfidence());

        } catch (SQLException e) {
            log.error("Failed to save match log: {}", e.getMessage(), e);
        }
        return CompletableFuture.completedFuture(null);
    }

    // -------------------------------------------------------------------------
    // Read
    // -------------------------------------------------------------------------

    /**
     * Returns the most recent {@code limit} log entries ordered by created_at DESC.
     * Each row is a LinkedHashMap preserving insertion order (matches column order).
     */
    public List<Map<String, Object>> getRecentLogs(int limit) {
        String sql = "SELECT * FROM match_logs ORDER BY created_at DESC LIMIT ?";
        List<Map<String, Object>> results = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setInt(1, limit);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id",               rs.getLong("id"));
                    row.put("createdAt",         rs.getString("created_at"));
                    row.put("predictedPattern",  rs.getString("predicted_pattern"));
                    row.put("similarityScore",   rs.getDouble("similarity_score"));
                    row.put("threshold",         rs.getDouble("threshold"));
                    row.put("isMatch",           rs.getInt("is_match") == 1);
                    row.put("confidence",        rs.getString("confidence"));
                    row.put("processingTimeMs",  rs.getLong("processing_time_ms"));
                    row.put("secondBestScore",   getDoubleOrNull(rs, "second_best_score"));
                    row.put("margin",            getDoubleOrNull(rs, "margin"));
                    row.put("decision",          rs.getString("decision"));
                    results.add(row);
                }
            }
        } catch (SQLException e) {
            log.error("Failed to fetch recent logs: {}", e.getMessage(), e);
        }

        return results;
    }

    private static Double getDoubleOrNull(ResultSet rs, String column) throws SQLException {
        try {
            double v = rs.getDouble(column);
            return rs.wasNull() ? null : v;
        } catch (SQLException e) {
            return null;
        }
    }

    /**
     * Returns paginated log entries with optional filters.
     *
     * @param limit    max rows to return
     * @param offset   rows to skip
     * @param pattern  if non-null, filter by predicted_pattern (exact match)
     * @param isMatch  if non-null, filter by is_match flag
     */
    public List<Map<String, Object>> getFilteredLogs(int limit, int offset,
                                                      String pattern, Boolean isMatch) {
        StringBuilder sql = new StringBuilder(
                "SELECT * FROM match_logs WHERE 1=1");

        List<Object> params = new ArrayList<>();
        if (pattern != null && !pattern.isBlank()) {
            sql.append(" AND predicted_pattern = ?");
            params.add(pattern);
        }
        if (isMatch != null) {
            sql.append(" AND is_match = ?");
            params.add(isMatch ? 1 : 0);
        }
        sql.append(" ORDER BY created_at DESC LIMIT ? OFFSET ?");
        params.add(limit);
        params.add(offset);

        List<Map<String, Object>> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                stmt.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id",               rs.getLong("id"));
                    row.put("createdAt",         rs.getString("created_at"));
                    row.put("predictedPattern",  rs.getString("predicted_pattern"));
                    row.put("similarityScore",   rs.getDouble("similarity_score"));
                    row.put("threshold",         rs.getDouble("threshold"));
                    row.put("isMatch",           rs.getInt("is_match") == 1);
                    row.put("confidence",        rs.getString("confidence"));
                    row.put("processingTimeMs",  rs.getLong("processing_time_ms"));
                    row.put("secondBestScore",   getDoubleOrNull(rs, "second_best_score"));
                    row.put("margin",            getDoubleOrNull(rs, "margin"));
                    row.put("decision",          rs.getString("decision"));
                    results.add(row);
                }
            }
        } catch (SQLException e) {
            log.error("Failed to fetch filtered logs: {}", e.getMessage(), e);
        }
        return results;
    }

    /**
     * Returns the total number of rows in match_logs.
     */
    public long getTotalLogCount() {
        String sql = "SELECT COUNT(*) FROM match_logs";
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            log.error("Failed to count logs: {}", e.getMessage(), e);
        }
        return 0;
    }

    /**
     * Returns aggregate accuracy statistics from match_logs.
     * Includes total count, matched count, and match rate ratio.
     */
    public Map<String, Object> getAccuracyStats() {
        String sql = "SELECT COUNT(*) AS total, COALESCE(SUM(is_match), 0) AS matched FROM match_logs";
        Map<String, Object> stats = new LinkedHashMap<>();

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            if (rs.next()) {
                long total   = rs.getLong("total");
                long matched = rs.getLong("matched");
                stats.put("totalLogs",      total);
                stats.put("matchedCount",   matched);
                stats.put("unmatchedCount", total - matched);
                stats.put("matchRate",      total > 0 ? (double) matched / total : 0.0);
            }
        } catch (SQLException e) {
            log.error("Failed to fetch accuracy stats: {}", e.getMessage(), e);
            stats.put("error", e.getMessage());
        }

        return stats;
    }

    private static final int METRICS_SAMPLE_SIZE = 1000;
    private static final double[] SCORE_BUCKETS = {0.0, 0.60, 0.70, 0.80, 0.90, 1.01};

    /**
     * Returns metrics derived from match_logs for monitoring and calibration.
     * Includes: total/match/uncertain counts and rates, avg/p95 latency,
     * score histogram, and per-pattern counts with average best score.
     */
    public Map<String, Object> getMatchMetrics() {
        Map<String, Object> out = new LinkedHashMap<>();
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            // --- Counts and rates (from full table) ---
            String countSql = "SELECT COUNT(*) AS total, COALESCE(SUM(is_match), 0) AS matched FROM match_logs";
            try (ResultSet rs = stmt.executeQuery(countSql)) {
                if (rs.next()) {
                    long total = rs.getLong("total");
                    long matched = rs.getLong("matched");
                    long uncertain = total - matched;
                    out.put("totalMatches", total);
                    out.put("matchCount", matched);
                    out.put("uncertainCount", uncertain);
                    out.put("matchRate", total > 0 ? (double) matched / total : 0.0);
                    out.put("uncertainRate", total > 0 ? (double) uncertain / total : 0.0);
                }
            }

            // --- Latency and score histogram from latest N rows ---
            String sampleSql = "SELECT similarity_score, processing_time_ms FROM match_logs " +
                    "ORDER BY created_at DESC LIMIT " + METRICS_SAMPLE_SIZE;
            List<Double> scores = new ArrayList<>();
            List<Long> latencies = new ArrayList<>();
            try (ResultSet rs = stmt.executeQuery(sampleSql)) {
                while (rs.next()) {
                    scores.add(rs.getDouble("similarity_score"));
                    long ms = rs.getLong("processing_time_ms");
                    latencies.add(ms >= 0 ? ms : 0L);
                }
            }

            Map<String, Object> latencyMap = new LinkedHashMap<>();
            if (!latencies.isEmpty()) {
                long sum = 0;
                for (long L : latencies) sum += L;
                latencyMap.put("avg_ms", (double) sum / latencies.size());
                long[] sorted = new long[latencies.size()];
                for (int i = 0; i < latencies.size(); i++) sorted[i] = latencies.get(i);
                Arrays.sort(sorted);
                int p95Idx = (int) Math.floor(0.95 * (sorted.length - 1));
                if (p95Idx < 0) p95Idx = 0;
                latencyMap.put("p95_ms", (double) sorted[p95Idx]);
                latencyMap.put("sampleSize", latencies.size());
            } else {
                latencyMap.put("avg_ms", 0.0);
                latencyMap.put("p95_ms", 0.0);
                latencyMap.put("sampleSize", 0);
            }
            out.put("latency", latencyMap);

            Map<String, String> histogram = new LinkedHashMap<>();
            for (int b = 0; b < SCORE_BUCKETS.length - 1; b++) {
                String label = String.format("%.2f-%.2f", SCORE_BUCKETS[b], SCORE_BUCKETS[b + 1] == 1.01 ? 1.0 : SCORE_BUCKETS[b + 1]);
                int count = 0;
                for (Double s : scores) {
                    if (s >= SCORE_BUCKETS[b] && s < SCORE_BUCKETS[b + 1]) count++;
                }
                histogram.put(label, String.valueOf(count));
            }
            out.put("scoreHistogram", histogram);

            // --- Per-pattern: count and avg best score ---
            String perPatternSql = "SELECT predicted_pattern AS pattern, COUNT(*) AS cnt, AVG(similarity_score) AS avg_score " +
                    "FROM match_logs WHERE predicted_pattern IS NOT NULL GROUP BY predicted_pattern ORDER BY cnt DESC";
            List<Map<String, Object>> perPattern = new ArrayList<>();
            try (ResultSet rs = stmt.executeQuery(perPatternSql)) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("pattern", rs.getString("pattern"));
                    row.put("count", rs.getLong("cnt"));
                    row.put("avgBestScore", rs.getDouble("avg_score"));
                    perPattern.add(row);
                }
            }
            out.put("perPattern", perPattern);

        } catch (SQLException e) {
            log.error("Failed to compute match metrics: {}", e.getMessage(), e);
            out.put("error", e.getMessage());
        }
        return out;
    }
}
