package com.example.src.service;

import com.example.src.dto.PatternDto;
import com.example.src.dto.ReferenceImageDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.ByteBuffer;
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
 * Manages the admin SQLite tables using plain JDBC (same approach as LogService).
 *
 * Tables (auto-created on first startup, in the shared logs.db):
 *   patterns(id, code UNIQUE, created_at)
 *   reference_images(id, pattern_id, image_path, embedding_blob, embedding_dim, created_at)
 *   settings(key TEXT PRIMARY KEY, value TEXT)
 *
 * Embeddings are stored as raw IEEE 754 float bytes (4 bytes per dimension).
 * Use floatArrayToBytes() / bytesToFloatArray() for serialization.
 */
@Service
public class AdminDatabaseService {

    private static final Logger log = LoggerFactory.getLogger(AdminDatabaseService.class);

    private static final String DDL_PATTERNS =
            "CREATE TABLE IF NOT EXISTS patterns (" +
            "  id         INTEGER PRIMARY KEY AUTOINCREMENT," +
            "  code       TEXT    NOT NULL UNIQUE," +
            "  created_at TEXT    NOT NULL" +
            ")";

    private static final String DDL_REFERENCE_IMAGES =
            "CREATE TABLE IF NOT EXISTS reference_images (" +
            "  id             INTEGER PRIMARY KEY AUTOINCREMENT," +
            "  pattern_id     INTEGER NOT NULL REFERENCES patterns(id) ON DELETE CASCADE," +
            "  image_path     TEXT    NOT NULL," +
            "  embedding_blob BLOB    NOT NULL," +
            "  embedding_dim  INTEGER NOT NULL," +
            "  created_at     TEXT    NOT NULL" +
            ")";

    private static final String DDL_SETTINGS =
            "CREATE TABLE IF NOT EXISTS settings (" +
            "  key   TEXT PRIMARY KEY," +
            "  value TEXT NOT NULL" +
            ")";

    private static final String DDL_PRAGMA_FK = "PRAGMA foreign_keys = ON";

    private static final String[] THUMBNAIL_CANDIDATES = {"_thumbnail.jpg", "_thumbnail.jpeg", "_thumbnail.png"};

    private final String dbPath;
    private final DataSource dataSource;
    private final String leatherImagesPath;

    public AdminDatabaseService(@Value("${sqlite.admin.path}") String dbPath,
                                @Value("${leather.images.path}") String leatherImagesPath,
                                DataSource dataSource) {
        this.dbPath = dbPath;
        this.leatherImagesPath = leatherImagesPath;
        this.dataSource = dataSource;
        initializeTables();
    }

    // -------------------------------------------------------------------------
    // Initialisation
    // -------------------------------------------------------------------------

    private void migrateAddThumbnailReferenceId(Connection conn) throws SQLException {
        // Check if column already exists (PRAGMA table_info)
        try (var rs = conn.createStatement().executeQuery("PRAGMA table_info(patterns)")) {
            while (rs.next()) {
                if ("thumbnail_reference_id".equals(rs.getString("name"))) {
                    log.info("Column thumbnail_reference_id already exists in patterns");
                    return;
                }
            }
        }
        conn.createStatement().execute(
            "ALTER TABLE patterns ADD COLUMN thumbnail_reference_id INTEGER REFERENCES reference_images(id)");
        log.info("Added thumbnail_reference_id column to patterns");
    }

    private void initializeTables() {
        File dbFile = new File(dbPath);
        File parentDir = dbFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }

        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(DDL_PRAGMA_FK);
            stmt.execute(DDL_PATTERNS);
            stmt.execute(DDL_REFERENCE_IMAGES);
            stmt.execute(DDL_SETTINGS);
            migrateAddThumbnailReferenceId(conn);
            log.info("Admin SQLite tables ready: {}", dbPath);
        } catch (SQLException e) {
            log.error("Failed to initialize admin tables: {}", e.getMessage(), e);
            throw new RuntimeException("Admin DB initialization failed: " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Connection helper
    // -------------------------------------------------------------------------

    private Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    // -------------------------------------------------------------------------
    // Patterns CRUD
    // -------------------------------------------------------------------------

    /**
     * Inserts a pattern row only if no row with that code already exists.
     * Used at startup to register legacy JSON patterns so they appear in the admin panel.
     * Safe to call multiple times (idempotent).
     */
    public void ensurePatternExists(String code) {
        String sql = "INSERT OR IGNORE INTO patterns (code, created_at) VALUES (?, ?)";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, code.trim().toUpperCase());
            stmt.setString(2, LocalDateTime.now().toString());
            stmt.executeUpdate();
        } catch (SQLException e) {
            log.warn("ensurePatternExists failed for '{}': {}", code, e.getMessage());
        }
    }

    public PatternDto createPattern(String code) {
        String sql = "INSERT INTO patterns (code, created_at) VALUES (?, ?)";
        String now = LocalDateTime.now().toString();
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, code.trim().toUpperCase());
            stmt.setString(2, now);
            stmt.executeUpdate();
            try (ResultSet keys = stmt.getGeneratedKeys()) {
                if (keys.next()) {
                    long id = keys.getLong(1);
                    return new PatternDto(id, code.trim().toUpperCase(), now, 0);
                }
            }
        } catch (SQLException e) {
            if (e.getMessage() != null && e.getMessage().contains("UNIQUE")) {
                throw new IllegalArgumentException("Pattern code already exists: " + code);
            }
            log.error("Failed to create pattern '{}': {}", code, e.getMessage(), e);
            throw new RuntimeException("Failed to create pattern: " + e.getMessage(), e);
        }
        throw new RuntimeException("Insert did not return a generated key");
    }

    public List<PatternDto> getAllPatterns() {
        String sql =
                "SELECT p.id, p.code, p.created_at, p.thumbnail_reference_id, COUNT(r.id) AS ref_count " +
                "FROM patterns p LEFT JOIN reference_images r ON r.pattern_id = p.id " +
                "GROUP BY p.id ORDER BY p.code";
        List<PatternDto> list = new ArrayList<>();
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                long thumbId = rs.getLong("thumbnail_reference_id");
                if (rs.wasNull()) thumbId = -1;
                PatternDto dto = new PatternDto(
                        rs.getLong("id"),
                        rs.getString("code"),
                        rs.getString("created_at"),
                        rs.getInt("ref_count"));
                dto.setThumbnailReferenceId(thumbId > 0 ? thumbId : null);
                list.add(dto);
            }
        } catch (SQLException e) {
            log.error("Failed to list patterns: {}", e.getMessage(), e);
        }
        return list;
    }

    public Optional<PatternDto> getPatternById(long id) {
        String sql =
                "SELECT p.id, p.code, p.created_at, p.thumbnail_reference_id, COUNT(r.id) AS ref_count " +
                "FROM patterns p LEFT JOIN reference_images r ON r.pattern_id = p.id " +
                "WHERE p.id = ? GROUP BY p.id";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, id);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    long thumbId = rs.getLong("thumbnail_reference_id");
                    if (rs.wasNull()) thumbId = -1;
                    PatternDto dto = new PatternDto(
                            rs.getLong("id"),
                            rs.getString("code"),
                            rs.getString("created_at"),
                            rs.getInt("ref_count"));
                    dto.setThumbnailReferenceId(thumbId > 0 ? thumbId : null);
                    return Optional.of(dto);
                }
            }
        } catch (SQLException e) {
            log.error("Failed to get pattern {}: {}", id, e.getMessage(), e);
        }
        return Optional.empty();
    }

    /**
     * Returns pattern by code (case-insensitive match).
     */
    public Optional<PatternDto> getPatternByCode(String code) {
        if (code == null || code.isBlank()) return Optional.empty();
        String sql =
                "SELECT p.id, p.code, p.created_at, p.thumbnail_reference_id, COUNT(r.id) AS ref_count " +
                "FROM patterns p LEFT JOIN reference_images r ON r.pattern_id = p.id " +
                "WHERE UPPER(TRIM(p.code)) = UPPER(TRIM(?)) GROUP BY p.id";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, code);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    long thumbId = rs.getLong("thumbnail_reference_id");
                    if (rs.wasNull()) thumbId = -1;
                    PatternDto dto = new PatternDto(
                            rs.getLong("id"),
                            rs.getString("code"),
                            rs.getString("created_at"),
                            rs.getInt("ref_count"));
                    dto.setThumbnailReferenceId(thumbId > 0 ? thumbId : null);
                    return Optional.of(dto);
                }
            }
        } catch (SQLException e) {
            log.error("Failed to get pattern by code '{}': {}", code, e.getMessage(), e);
        }
        return Optional.empty();
    }

    /**
     * Deletes a pattern and all its reference_images (cascade via FK).
     * Returns the list of image file paths that were deleted from DB (caller may delete from disk).
     */
    public List<String> deletePattern(long patternId) {
        List<String> imagePaths = getImagePathsForPattern(patternId);
        String sql = "DELETE FROM patterns WHERE id = ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, patternId);
            stmt.executeUpdate();
            log.info("Deleted pattern id={} and {} references", patternId, imagePaths.size());
        } catch (SQLException e) {
            log.error("Failed to delete pattern {}: {}", patternId, e.getMessage(), e);
            throw new RuntimeException("Failed to delete pattern: " + e.getMessage(), e);
        }
        return imagePaths;
    }

    // -------------------------------------------------------------------------
    // Reference Images CRUD
    // -------------------------------------------------------------------------

    public ReferenceImageDto addReferenceImage(long patternId, String patternCode,
                                                String imagePath, float[] embedding) {
        String sql =
                "INSERT INTO reference_images (pattern_id, image_path, embedding_blob, embedding_dim, created_at) " +
                "VALUES (?, ?, ?, ?, ?)";
        String now = LocalDateTime.now().toString();
        byte[] blob = floatArrayToBytes(embedding);
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setLong(1, patternId);
            stmt.setString(2, imagePath);
            stmt.setBytes(3, blob);
            stmt.setInt(4, embedding.length);
            stmt.setString(5, now);
            stmt.executeUpdate();
            try (ResultSet keys = stmt.getGeneratedKeys()) {
                if (keys.next()) {
                    long id = keys.getLong(1);
                    return new ReferenceImageDto(id, patternId, patternCode, imagePath, embedding.length, now);
                }
            }
        } catch (SQLException e) {
            log.error("Failed to add reference image: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to add reference image: " + e.getMessage(), e);
        }
        throw new RuntimeException("Insert did not return a generated key");
    }

    public List<ReferenceImageDto> getReferencesForPattern(long patternId) {
        String sql =
                "SELECT r.id, r.pattern_id, p.code, r.image_path, r.embedding_dim, r.created_at " +
                "FROM reference_images r JOIN patterns p ON p.id = r.pattern_id " +
                "WHERE r.pattern_id = ? ORDER BY r.created_at";
        List<ReferenceImageDto> list = new ArrayList<>();
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, patternId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    list.add(new ReferenceImageDto(
                            rs.getLong("id"),
                            rs.getLong("pattern_id"),
                            rs.getString("code"),
                            rs.getString("image_path"),
                            rs.getInt("embedding_dim"),
                            rs.getString("created_at")));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to list references for pattern {}: {}", patternId, e.getMessage(), e);
        }
        return list;
    }

    /**
     * Sets which reference image is used as the pattern thumbnail.
     */
    public void setThumbnailReference(long patternId, long referenceId) {
        String sql = "UPDATE patterns SET thumbnail_reference_id = ? WHERE id = ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, referenceId);
            stmt.setLong(2, patternId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to set thumbnail for pattern {}: {}", patternId, e.getMessage(), e);
            throw new RuntimeException("Failed to set thumbnail: " + e.getMessage(), e);
        }
    }

    /**
     * Returns the image path for the pattern's thumbnail.
     * Priority: 1) _thumbnail.jpg/.jpeg/.png in pattern folder (file-based, set by admin), 2) first reference.
     */
    public Optional<String> getThumbnailImagePathForPattern(String patternCode) {
        if (patternCode == null || patternCode.isBlank()) return Optional.empty();
        Path patternDir = Paths.get(leatherImagesPath, patternCode.trim());
        for (String name : THUMBNAIL_CANDIDATES) {
            Path thumbFile = patternDir.resolve(name);
            if (Files.exists(thumbFile)) {
                return Optional.of(thumbFile.toAbsolutePath().toString());
            }
        }
        var patternOpt = getPatternByCode(patternCode);
        if (patternOpt.isEmpty()) return Optional.empty();
        List<ReferenceImageDto> refs = getReferencesForPattern(patternOpt.get().getId());
        if (refs.isEmpty()) return Optional.empty();
        return Optional.of(refs.get(0).getImagePath());
    }

    /**
     * Returns the stored image_path for a single reference row.
     * Used by the image-streaming endpoint.
     */
    public Optional<String> getImagePathForReference(long referenceId) {
        String sql = "SELECT image_path FROM reference_images WHERE id = ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, referenceId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(rs.getString("image_path"));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to get image path for reference {}: {}", referenceId, e.getMessage(), e);
        }
        return Optional.empty();
    }

    public Optional<float[]> getEmbeddingForReference(long referenceId) {
        String sql = "SELECT embedding_blob, embedding_dim FROM reference_images WHERE id = ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, referenceId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    byte[] blob = rs.getBytes("embedding_blob");
                    return Optional.of(bytesToFloatArray(blob));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to get embedding for reference {}: {}", referenceId, e.getMessage(), e);
        }
        return Optional.empty();
    }

    /**
     * Deletes a single reference image row. Returns the image_path for disk cleanup.
     * Clears thumbnail_reference_id if this reference was the thumbnail.
     */
    public Optional<String> deleteReference(long referenceId) {
        String selectSql = "SELECT image_path, pattern_id FROM reference_images WHERE id = ?";
        String clearThumbSql = "UPDATE patterns SET thumbnail_reference_id = NULL WHERE thumbnail_reference_id = ?";
        String deleteSql = "DELETE FROM reference_images WHERE id = ?";
        try (Connection conn = getConnection()) {
            String imagePath = null;
            try (PreparedStatement sel = conn.prepareStatement(selectSql)) {
                sel.setLong(1, referenceId);
                try (ResultSet rs = sel.executeQuery()) {
                    if (rs.next()) {
                        imagePath = rs.getString("image_path");
                    }
                }
            }
            if (imagePath == null) {
                return Optional.empty();
            }
            try (PreparedStatement clear = conn.prepareStatement(clearThumbSql)) {
                clear.setLong(1, referenceId);
                clear.executeUpdate();
            }
            try (PreparedStatement del = conn.prepareStatement(deleteSql)) {
                del.setLong(1, referenceId);
                del.executeUpdate();
            }
            return Optional.of(imagePath);
        } catch (SQLException e) {
            log.error("Failed to delete reference {}: {}", referenceId, e.getMessage(), e);
            throw new RuntimeException("Failed to delete reference: " + e.getMessage(), e);
        }
    }

    /**
     * Returns all embeddings grouped by pattern code — used by DatabaseService at startup merge.
     * Key = patternCode, Value = list of (referenceId, embedding) pairs.
     */
    public Map<String, List<EmbeddingEntry>> loadAllEmbeddings() {
        String sql =
                "SELECT r.id, p.code, r.embedding_blob " +
                "FROM reference_images r JOIN patterns p ON p.id = r.pattern_id " +
                "ORDER BY p.code";
        Map<String, List<EmbeddingEntry>> result = new LinkedHashMap<>();
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                long id = rs.getLong("id");
                String code = rs.getString("code");
                float[] embedding = bytesToFloatArray(rs.getBytes("embedding_blob"));
                result.computeIfAbsent(code, k -> new ArrayList<>())
                      .add(new EmbeddingEntry(id, embedding));
            }
        } catch (SQLException e) {
            log.error("Failed to load all embeddings: {}", e.getMessage(), e);
        }
        log.info("Loaded {} admin-managed patterns from SQLite", result.size());
        return result;
    }

    /**
     * Returns all embeddings for a single pattern — used to rebuild cache after a deletion.
     */
    public List<float[]> loadEmbeddingsForPattern(String patternCode) {
        String sql =
                "SELECT r.embedding_blob " +
                "FROM reference_images r JOIN patterns p ON p.id = r.pattern_id " +
                "WHERE p.code = ? ORDER BY r.id";
        List<float[]> list = new ArrayList<>();
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, patternCode);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    list.add(bytesToFloatArray(rs.getBytes("embedding_blob")));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to load embeddings for pattern '{}': {}", patternCode, e.getMessage(), e);
        }
        return list;
    }

    // -------------------------------------------------------------------------
    // Settings
    // -------------------------------------------------------------------------

    public Optional<String> getSetting(String key) {
        String sql = "SELECT value FROM settings WHERE key = ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, key);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(rs.getString("value"));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to get setting '{}': {}", key, e.getMessage(), e);
        }
        return Optional.empty();
    }

    public void setSetting(String key, String value) {
        String sql = "INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, key);
            stmt.setString(2, value);
            stmt.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to set setting '{}': {}", key, e.getMessage(), e);
            throw new RuntimeException("Failed to persist setting: " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private List<String> getImagePathsForPattern(long patternId) {
        String sql = "SELECT image_path FROM reference_images WHERE pattern_id = ?";
        List<String> paths = new ArrayList<>();
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, patternId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    paths.add(rs.getString("image_path"));
                }
            }
        } catch (SQLException e) {
            log.warn("Could not list image paths for pattern {}: {}", patternId, e.getMessage());
        }
        return paths;
    }

    private static byte[] floatArrayToBytes(float[] floats) {
        ByteBuffer buf = ByteBuffer.allocate(floats.length * Float.BYTES);
        for (float f : floats) {
            buf.putFloat(f);
        }
        return buf.array();
    }

    private static float[] bytesToFloatArray(byte[] bytes) {
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        float[] floats = new float[bytes.length / Float.BYTES];
        for (int i = 0; i < floats.length; i++) {
            floats[i] = buf.getFloat();
        }
        return floats;
    }

    // -------------------------------------------------------------------------
    // Inner class
    // -------------------------------------------------------------------------

    /**
     * Pairs a database reference ID with the deserialized embedding vector.
     */
    public static class EmbeddingEntry {
        private final long id;
        private final float[] embedding;

        public EmbeddingEntry(long id, float[] embedding) {
            this.id = id;
            this.embedding = embedding;
        }

        public long getId() { return id; }
        public float[] getEmbedding() { return embedding; }
    }
}
