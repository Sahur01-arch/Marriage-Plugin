package com.ryushin.marriage.data;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;

public class Database {

    private final File dbFile;
    private Connection connection;
    private final Logger logger;

    public Database(File dbFile) {
        this.dbFile = dbFile;
        this.logger = Logger.getLogger("MarriagePlugin");
    }

    public void connect() {
        try {
            File parent = dbFile.getParentFile();

            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }

            String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();
            connection = DriverManager.getConnection(url);

            logger.info("[Database] Berhasil tersambung ke SQLite: " + dbFile.getName());
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "[Database] Gagal tersambung ke SQLite", e);
        }
    }

    public void initTables() {
        String sqlMarriage =
                "CREATE TABLE IF NOT EXISTS marriage (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "player1_uuid TEXT NOT NULL, " +
                "player2_uuid TEXT NOT NULL, " +
                "bond_xp INTEGER DEFAULT 0, " +
                "bond_level INTEGER DEFAULT 1, " +
                "married_at INTEGER NOT NULL" +
                ")";

        String sqlFamily =
                "CREATE TABLE IF NOT EXISTS family_relation (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "player_uuid TEXT NOT NULL, " +
                "related_uuid TEXT NOT NULL, " +
                "relation_type TEXT NOT NULL" +
                ")";

        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sqlMarriage);
            stmt.execute(sqlFamily);
            logger.info("[Database] Tabel berhasil di init");
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "[Database] Gagal membuat table", e);
        }
    }

    public void saveMarriage(Marriage marriage) {
        String sql =
                "INSERT INTO marriage " +
                "(player1_uuid, player2_uuid, bond_xp, bond_level, married_at) " +
                "VALUES (?, ?, ?, ?, ?)";

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, marriage.getPlayer1Uuid().toString());
            ps.setString(2, marriage.getPlayer2Uuid().toString());
            ps.setInt(3, marriage.getBondXp());
            ps.setInt(4, marriage.getBondLevel());
            ps.setLong(5, marriage.getMarriedAt());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "[Database] Gagal simpan marriage", e);
        }
    }

    public Marriage getMarriageByPlayer(UUID uuid) {
        String sql =
                "SELECT * FROM marriage " +
                "WHERE player1_uuid = ? OR player2_uuid = ?";

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, uuid.toString());

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new Marriage(
                            rs.getInt("id"),
                            UUID.fromString(rs.getString("player1_uuid")),
                            UUID.fromString(rs.getString("player2_uuid")),
                            rs.getInt("bond_xp"),
                            rs.getInt("bond_level"),
                            rs.getLong("married_at")
                    );
                }
            }

            return null;
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "[Database] Gagal ambil data marriage", e);
            return null;
        }
    }

    public void deleteMarriage(int marriageId) {
        String sql = "DELETE FROM marriage WHERE id = ?";

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, marriageId);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "[Database] Gagal hapus marriage", e);
        }
    }

    public void saveFamilyRelation(FamilyRelation relation) {
        String sql =
                "INSERT INTO family_relation " +
                "(player_uuid, related_uuid, relation_type) " +
                "VALUES (?, ?, ?)";

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, relation.getPlayerUuid().toString());
            ps.setString(2, relation.getRelatedUuid().toString());
            ps.setString(3, relation.getRelationType().name());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "[Database] Gagal simpan family relation", e);
        }
    }

    public List<FamilyRelation> getRelationsByPlayer(
            UUID uuid,
            RelationType type
    ) {
        List<FamilyRelation> hasil = new ArrayList<>();

        String sql =
                "SELECT * FROM family_relation " +
                "WHERE player_uuid = ? AND relation_type = ?";

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, type.name());

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    hasil.add(new FamilyRelation(
                            rs.getInt("id"),
                            UUID.fromString(rs.getString("player_uuid")),
                            UUID.fromString(rs.getString("related_uuid")),
                            RelationType.valueOf(rs.getString("relation_type"))
                    ));
                }
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "[Database] Gagal ambil family relations", e);
        }

        return hasil;
    }

    public int countChildren(UUID parentUuid) {
        return getRelationsByPlayer(parentUuid, RelationType.PARENT).size();
    }

    /**
     * Emergency patch:
     * Menghapus satu relasi keluarga berdasarkan kedua UUID dan tipe relasi.
     */
    public void deleteFamilyRelation(
            UUID playerUuid,
            UUID relatedUuid,
            RelationType relationType
    ) {
        String sql =
                "DELETE FROM family_relation " +
                "WHERE player_uuid = ? " +
                "AND related_uuid = ? " +
                "AND relation_type = ?";

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, playerUuid.toString());
            ps.setString(2, relatedUuid.toString());
            ps.setString(3, relationType.name());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "[Database] Gagal menghapus family relation", e);
        }
    }

    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
                logger.info("[Database] Koneksi ditutup");
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "[Database] Gagal menutup Koneksi.", e);
        }
    }
}
