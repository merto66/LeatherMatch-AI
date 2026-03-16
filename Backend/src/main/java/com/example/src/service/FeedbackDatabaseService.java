package com.example.src.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Manages the match_feedback table in SQLite (shared logs.db).
 * Stores operator corrections for wrong matches, pending admin review.
 */
@Service
public class FeedbackDatabaseService {

    private static final Logger log = LoggerFactory.getLogger(FeedbackDatabaseService.class);

    private static final String DDL_MATCH_FEEDBACK =
            "CREATE TABLE IF NOT EXISTS match_feedback (" +
            "  id                        INTEGER PRIMARY KEY AUTOINCREMENT," +
            "  created_at                TEXT    NOT NULL," +
            "  uploaded_image_path       TEXT    NOT NULL," +
            "  predicted_pattern         TEXT," +
            "  predicted_score           REAL," +
            "  second_best_score         REAL," +
            "  threshold                 REAL," +
            "  margin                    REAL," +
            "  operator_selected_pattern TEXT    NOT NULL," +
            "  note                      TEXT," +
            "  status                    TEXT    NOT NULL DEFAULT 'PENDING'," +
            "  reviewed_at               TEXT," +
            "  reviewed_by               TEXT" +
            ")";

    private final DataSource dataSource;

    public FeedbackDatabaseService(DataSource dataSource) {
        this.dataSource = dataSource;
        initializeTable();
    }

    private void initializeTable() {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(DDL_MATCH_FEEDBACK);
            log.info("match_feedback table ready");
        } catch (SQLException e) {
            log.error("Failed to initialize match_feedback table: {}", e.getMessage(), e);
            throw new RuntimeException("Feedback DB initialization failed: " + e.getMessage(), e);
        }
    }

    private Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    /**
     * Inserts a new feedback row with status PENDING.
     */
    public long insertFeedback(String imagePath, String predictedPattern, double predictedScore,
                               Double secondBestScore, double threshold, double margin,
                               String operatorSelectedPattern, String note) {
        String sql = "INSERT INTO match_feedback " +
                "(created_at, uploaded_image_path, predicted_pattern, predicted_score, second_best_score, " +
                "threshold, margin, operator_selected_pattern, note, status) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING')";
        String now = LocalDateTime.now().toString();
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, now);
            stmt.setString(2, imagePath);
            stmt.setString(3, predictedPattern);
            stmt.setDouble(4, predictedScore);
            if (secondBestScore != null) {
                stmt.setDouble(5, secondBestScore);
            } else {
                stmt.setNull(5, java.sql.Types.DOUBLE);
            }
            stmt.setDouble(6, threshold);
            stmt.setDouble(7, margin);
            stmt.setString(8, operatorSelectedPattern);
            stmt.setString(9, note != null ? note : "");
            stmt.executeUpdate();
            try (ResultSet keys = stmt.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
        } catch (SQLException e) {
            log.error("Failed to insert feedback: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to insert feedback: " + e.getMessage(), e);
        }
        throw new RuntimeException("Insert did not return a generated key");
    }

    /**
     * Returns paginated feedback list with optional status filter.
     */
    public List<Map<String, Object>> getFeedbackList(String status, int limit, int offset) {
        StringBuilder sql = new StringBuilder(
                "SELECT * FROM match_feedback WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            params.add(status);
        }
        sql.append(" ORDER BY created_at DESC LIMIT ? OFFSET ?");
        params.add(limit);
        params.add(offset);

        List<Map<String, Object>> results = new ArrayList<>();
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                stmt.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    results.add(rowToMap(rs));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to fetch feedback list: {}", e.getMessage(), e);
        }
        return results;
    }

    /**
     * Returns total count of feedback rows (optionally filtered by status).
     */
    public long getFeedbackCount(String status) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM match_feedback WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            params.add(status);
        }
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                stmt.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        } catch (SQLException e) {
            log.error("Failed to count feedback: {}", e.getMessage(), e);
        }
        return 0;
    }

    public Optional<Map<String, Object>> getFeedbackById(long id) {
        String sql = "SELECT * FROM match_feedback WHERE id = ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, id);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(rowToMap(rs));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to get feedback {}: {}", id, e.getMessage(), e);
        }
        return Optional.empty();
    }

    /**
     * Updates status, reviewed_at, and reviewed_by for a feedback row.
     */
    public boolean updateStatus(long id, String status, String reviewedBy) {
        String sql = "UPDATE match_feedback SET status = ?, reviewed_at = ?, reviewed_by = ? WHERE id = ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, status);
            stmt.setString(2, LocalDateTime.now().toString());
            stmt.setString(3, reviewedBy != null ? reviewedBy : "");
            stmt.setLong(4, id);
            int updated = stmt.executeUpdate();
            return updated > 0;
        } catch (SQLException e) {
            log.error("Failed to update feedback status {}: {}", id, e.getMessage(), e);
            return false;
        }
    }

    public Optional<String> getImagePathForFeedback(long id) {
        String sql = "SELECT uploaded_image_path FROM match_feedback WHERE id = ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, id);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(rs.getString("uploaded_image_path"));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to get image path for feedback {}: {}", id, e.getMessage(), e);
        }
        return Optional.empty();
    }

    private static Map<String, Object> rowToMap(ResultSet rs) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", rs.getLong("id"));
        row.put("createdAt", rs.getString("created_at"));
        row.put("uploadedImagePath", rs.getString("uploaded_image_path"));
        row.put("predictedPattern", rs.getString("predicted_pattern"));
        row.put("predictedScore", rs.getDouble("predicted_score"));
        row.put("secondBestScore", getDoubleOrNull(rs, "second_best_score"));
        row.put("threshold", rs.getDouble("threshold"));
        row.put("margin", rs.getDouble("margin"));
        row.put("operatorSelectedPattern", rs.getString("operator_selected_pattern"));
        row.put("note", rs.getString("note"));
        row.put("status", rs.getString("status"));
        row.put("reviewedAt", rs.getString("reviewed_at"));
        row.put("reviewedBy", rs.getString("reviewed_by"));
        return row;
    }

    private static Double getDoubleOrNull(ResultSet rs, String column) throws SQLException {
        try {
            double v = rs.getDouble(column);
            return rs.wasNull() ? null : v;
        } catch (SQLException e) {
            return null;
        }
    }
}
