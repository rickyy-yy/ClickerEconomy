package com.clickereconomy.storage;

import com.clickereconomy.ClickerPlugin;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class SqlClickerStorage implements ClickerStorage {

    private final ClickerPlugin  plugin;
    private final String         prefix;
    private final String         backend;
    private       HikariDataSource dataSource;

    public SqlClickerStorage(ClickerPlugin plugin) {
        this.plugin  = plugin;
        this.prefix  = plugin.getConfig().getString("storage.table-prefix", "ce_");
        this.backend = plugin.getConfig().getString("storage.backend", "mysql").toLowerCase();
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    @Override
    public void init() {
        HikariConfig cfg = buildHikariConfig();
        cfg.setPoolName("ClickerEconomy-Pool");
        cfg.setMaximumPoolSize(plugin.getConfig().getInt("storage.sql.pool-size", 5));
        cfg.setConnectionTimeout(plugin.getConfig().getLong("storage.sql.connection-timeout", 30000));
        cfg.setIdleTimeout(plugin.getConfig().getLong("storage.sql.idle-timeout", 600000));
        cfg.setMaxLifetime(plugin.getConfig().getLong("storage.sql.max-lifetime", 1800000));

        dataSource = new HikariDataSource(cfg);
        createTables();
    }

    @Override
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) dataSource.close();
    }

    // =========================================================================
    // HikariCP config
    // =========================================================================

    private HikariConfig buildHikariConfig() {
        HikariConfig cfg    = new HikariConfig();
        String host         = plugin.getConfig().getString("storage.sql.host", "localhost");
        int    port         = plugin.getConfig().getInt("storage.sql.port", 3306);
        String database     = plugin.getConfig().getString("storage.sql.database", "minecraft");
        String username     = plugin.getConfig().getString("storage.sql.username", "root");
        String password     = plugin.getConfig().getString("storage.sql.password", "");
        boolean useSSL      = plugin.getConfig().getBoolean("storage.sql.use-ssl", false);

        switch (backend) {
            case "h2":
                String h2Path = plugin.getDataFolder().getAbsolutePath()
                        + java.io.File.separator
                        + plugin.getConfig().getString("storage.h2-file", "h2data");
                cfg.setJdbcUrl("jdbc:h2:file:" + h2Path
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=VALUE");
                cfg.setDriverClassName("org.h2.Driver");
                break;

            case "mariadb":
                cfg.setJdbcUrl("jdbc:mariadb://" + host + ":" + port + "/" + database
                        + "?characterEncoding=utf8mb4&autoReconnect=true");
                cfg.setUsername(username);
                cfg.setPassword(password);
                cfg.setDriverClassName("org.mariadb.jdbc.Driver");
                break;

            case "postgresql":
                cfg.setJdbcUrl("jdbc:postgresql://" + host + ":" + port + "/" + database);
                cfg.setUsername(username);
                cfg.setPassword(password);
                cfg.setDriverClassName("org.postgresql.Driver");
                break;

            default: // mysql
                cfg.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database
                        + "?useSSL=" + useSSL
                        + "&allowPublicKeyRetrieval=true"
                        + "&autoReconnect=true"
                        + "&characterEncoding=utf8mb4");
                cfg.setUsername(username);
                cfg.setPassword(password);
                cfg.setDriverClassName("com.mysql.cj.jdbc.Driver");
                cfg.addDataSourceProperty("cachePrepStmts",          "true");
                cfg.addDataSourceProperty("prepStmtCacheSize",        "250");
                cfg.addDataSourceProperty("prepStmtCacheSqlLimit",    "2048");
                cfg.addDataSourceProperty("useServerPrepStmts",       "true");
                cfg.addDataSourceProperty("rewriteBatchedStatements",  "true");
                break;
        }
        return cfg;
    }

    // =========================================================================
    // Schema
    // =========================================================================

    private void createTables() {
        String clicks = "CREATE TABLE IF NOT EXISTS " + prefix + "clicks ("
                + "uuid VARCHAR(36) NOT NULL, "
                + "click_count BIGINT NOT NULL DEFAULT 0, "
                + "PRIMARY KEY (uuid))";

        String multiplier = "CREATE TABLE IF NOT EXISTS " + prefix + "multiplier ("
                + "id TINYINT NOT NULL DEFAULT 1, "
                + "value DOUBLE NOT NULL DEFAULT 1.0, "
                + "remaining BIGINT NOT NULL DEFAULT -1, "
                + "PRIMARY KEY (id))";

        try (Connection conn = dataSource.getConnection();
             Statement  stmt = conn.createStatement()) {
            stmt.execute(clicks);
            stmt.execute(multiplier);
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to create tables: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    // =========================================================================
    // Clicks
    // =========================================================================

    @Override
    public Map<UUID, Long> loadClicks() {
        Map<UUID, Long> result = new HashMap<>();
        String sql = "SELECT uuid, click_count FROM " + prefix + "clicks";
        try (Connection conn = dataSource.getConnection();
             Statement  stmt = conn.createStatement();
             ResultSet  rs   = stmt.executeQuery(sql)) {
            while (rs.next()) {
                try { result.put(UUID.fromString(rs.getString("uuid")), rs.getLong("click_count")); }
                catch (IllegalArgumentException ignored) {}
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to load clicks: " + e.getMessage());
        }
        return result;
    }

    @Override
    public void saveClicks(Map<UUID, Long> clickCounts) {
        String truncate = "DELETE FROM " + prefix + "clicks";
        String insert   = "INSERT INTO " + prefix + "clicks (uuid, click_count) VALUES (?, ?)";

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (Statement stmt = conn.createStatement()) { stmt.execute(truncate); }
                try (PreparedStatement ps = conn.prepareStatement(insert)) {
                    for (Map.Entry<UUID, Long> e : clickCounts.entrySet()) {
                        if (e.getValue() > 0) {
                            ps.setString(1, e.getKey().toString());
                            ps.setLong(2, e.getValue());
                            ps.addBatch();
                        }
                    }
                    ps.executeBatch();
                }
                conn.commit();
            } catch (SQLException ex) {
                conn.rollback();
                throw ex;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to save clicks: " + e.getMessage());
        }
    }

    // =========================================================================
    // Multiplier
    // =========================================================================

    @Override
    public MultiplierRecord loadMultiplier() {
        String sql = "SELECT value, remaining FROM " + prefix + "multiplier WHERE id = 1";
        try (Connection conn = dataSource.getConnection();
             Statement  stmt = conn.createStatement();
             ResultSet  rs   = stmt.executeQuery(sql)) {
            if (rs.next()) {
                return new MultiplierRecord(rs.getDouble("value"), rs.getLong("remaining"));
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to load multiplier: " + e.getMessage());
        }
        return null;
    }

    @Override
    public void saveMultiplier(double value, long remainingSeconds) {
        String sql = "INSERT INTO " + prefix + "multiplier (id, value, remaining) VALUES (1, ?, ?) "
                + "ON DUPLICATE KEY UPDATE value = VALUES(value), remaining = VALUES(remaining)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setDouble(1, value);
            ps.setLong(2, remainingSeconds);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to save multiplier: " + e.getMessage());
        }
    }

    @Override
    public void clearMultiplier() {
        String sql = "DELETE FROM " + prefix + "multiplier WHERE id = 1";
        try (Connection conn = dataSource.getConnection();
             Statement  stmt = conn.createStatement()) {
            stmt.execute(sql);
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to clear multiplier: " + e.getMessage());
        }
    }
}
