package dev.courier;

import dev.courier.safe.Db;
import dev.courier.safe.DbBackups;
import dev.courier.safe.Health;
import dev.courier.safe.Journal;
import dev.courier.safe.SafeIo;
import dev.courier.safe.ServerId;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * One row per player in {@code courier.db} (SQLite). A player is read when first needed and kept in memory while they
 * are online. Writes are debounced: callers mark a player dirty and a repeating task writes everyone dirty every few
 * seconds on an async thread. {@link #saveNow} writes one player at once, for the moments that must be on disk before
 * a reward is paid. A write that fails is tried again on the next flush.
 * The old one-file-per-player layout under {@code data/} is brought in once, in a single transaction, and checked.
 */
final class Storage {

    static final int SCHEMA = 1;
    private static final String BACKUP_PREFIX = "courier";

    private final Logger log;
    private final File legacyDir;
    private final Path dbFile;
    private final Path backupDir;
    private final Map<UUID, PlayerData> cache = new ConcurrentHashMap<>();
    private final Set<UUID> dirty = ConcurrentHashMap.newKeySet();

    private Db db;
    private Journal journal;
    private DbBackups backups;
    private int importedPlayers;
    private int unreadableFiles;

    Storage(Path folder, Logger log) {
        this.log = log;
        this.legacyDir = folder.resolve("data").toFile();
        this.dbFile = folder.resolve("courier.db");
        this.backupDir = folder.resolve("backups");
    }

    /**
     * Opens the database (a damaged one is replaced by the newest backup that verifies), brings in the old player
     * files once, and prepares the payout record.
     */
    void open(int backupKeep) throws IOException, SQLException {
        db = Db.openRecovering(dbFile, SCHEMA, backupDir, BACKUP_PREFIX, true, log);
        try {
            db.tx(c -> {
                try (Statement s = c.createStatement()) {
                    s.execute("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
                    s.execute("CREATE TABLE IF NOT EXISTS players (uuid TEXT PRIMARY KEY, cooldown_until INTEGER NOT NULL, "
                            + "completed_total INTEGER NOT NULL, failed_total INTEGER NOT NULL, mission_id TEXT, mission_world TEXT, "
                            + "mission_x REAL NOT NULL, mission_y REAL NOT NULL, mission_z REAL NOT NULL, "
                            + "mission_started_at INTEGER NOT NULL, mission_expires_at INTEGER NOT NULL, mission_resource_world INTEGER NOT NULL)");
                }
            });
            if (db.userVersion() < SCHEMA)
                db.setUserVersion(SCHEMA);
            journal = new Journal(db);
            journal.recover(log);
            importLegacy();
        } catch (SQLException | RuntimeException e) {
            db.close();
            db = null;
            throw e;
        }
        configureBackups(backupKeep);
        Health.file("courier.db", importedPlayers > 0 ? "in use, " + importedPlayers + " player files were brought in" : "in use");
    }

    void configureBackups(int keep) {
        if (db != null)
            backups = new DbBackups(db, backupDir, BACKUP_PREFIX, keep, log);
    }

    boolean backup() {
        return backups == null || backups.run();
    }

    Journal journal() {
        return journal;
    }

    String newestBackup() {
        List<Path> found = DbBackups.list(backupDir, BACKUP_PREFIX);
        return found.isEmpty() ? null : found.get(0).getFileName().toString();
    }

    int schemaVersion() {
        try {
            return db == null ? SCHEMA : db.userVersion();
        } catch (SQLException e) {
            return -1;
        }
    }

    int storedPlayers() {
        try {
            return db == null ? 0 : db.read(c -> scalar(c, "SELECT COUNT(*) FROM players")).intValue();
        } catch (SQLException e) {
            return -1;
        }
    }

    int pendingWrites() {
        return dirty.size();
    }

    ServerId.Slot serverIdSlot() {
        return new ServerId.Slot() {
            @Override
            public String read() throws SQLException {
                return db.read(c -> metaValue(c, "server-id"));
            }

            @Override
            public void write(String id) throws SQLException {
                db.tx(c -> putMeta(c, "server-id", id));
            }
        };
    }

    // ---- the working copy

    PlayerData get(UUID player) {
        return cache.computeIfAbsent(player, this::load);
    }

    void markDirty(UUID player) {
        dirty.add(player);
    }

    void flush() {
        if (dirty.isEmpty())
            return;
        UUID[] toSave = dirty.toArray(UUID[]::new);
        dirty.removeAll(java.util.Arrays.asList(toSave));
        for (UUID id : toSave) {
            PlayerData data = cache.get(id);
            if (data != null && !save(id, data))
                dirty.add(id);
        }
    }

    /** Writes one player at once. Returns false when it could not be written. */
    boolean saveNow(UUID id, PlayerData data) {
        if (db == null)
            return true;
        boolean ok = save(id, data);
        if (ok)
            dirty.remove(id);
        return ok;
    }

    /** Called on shutdown - flush must be synchronous there, no time for a repeating task. */
    void flushSync() {
        for (UUID id : dirty.toArray(UUID[]::new)) {
            PlayerData data = cache.get(id);
            if (data != null)
                save(id, data);
        }
        dirty.clear();
    }

    /** Writes what is left and closes the database. Waits for a write that is running, since they share one lock. */
    void close() {
        if (db == null)
            return;
        flushSync();
        db.close();
        db = null;
    }

    /** A player left: write what is pending and forget them, so the cache only holds players who are online. */
    void unload(UUID player) {
        PlayerData data = cache.remove(player);
        if (data != null && dirty.remove(player))
            save(player, data);
    }

    // ---- the database

    private PlayerData load(UUID player) {
        PlayerData data = new PlayerData();
        if (db == null)
            return data;
        try {
            db.read(c -> {
                try (PreparedStatement ps = c.prepareStatement("SELECT cooldown_until, completed_total, failed_total, mission_id, "
                        + "mission_world, mission_x, mission_y, mission_z, mission_started_at, mission_expires_at, mission_resource_world "
                        + "FROM players WHERE uuid=?")) {
                    ps.setString(1, player.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            data.cooldownUntil = rs.getLong(1);
                            data.completedTotal = rs.getInt(2);
                            data.failedTotal = rs.getInt(3);
                            data.missionId = rs.getString(4);
                            data.missionWorld = rs.getString(5);
                            data.missionX = rs.getDouble(6);
                            data.missionY = rs.getDouble(7);
                            data.missionZ = rs.getDouble(8);
                            data.missionStartedAt = rs.getLong(9);
                            data.missionExpiresAt = rs.getLong(10);
                            data.missionResourceWorld = rs.getInt(11) != 0;
                        }
                    }
                }
                return null;
            });
        } catch (SQLException e) {
            // the player plays on with an empty record, but it is never written over what is stored
            data.unsafe = true;
            log.severe("The data of " + player + " could not be read (" + e.getMessage() + "). It is left as it is and nothing is saved for them this session.");
            Health.failure("player data could not be read: " + e.getMessage());
        }
        return data;
    }

    private boolean save(UUID player, PlayerData data) {
        if (db == null || data.unsafe)
            return true;
        try {
            db.tx(c -> write(c, player, data));
            return true;
        } catch (SQLException e) {
            log.warning("Could not save data for " + player + ": " + e.getMessage());
            Health.failure("player data could not be saved: " + e.getMessage());
            return false;
        }
    }

    private static void write(Connection c, UUID player, PlayerData data) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO players (uuid, cooldown_until, completed_total, failed_total, "
                + "mission_id, mission_world, mission_x, mission_y, mission_z, mission_started_at, mission_expires_at, mission_resource_world) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(uuid) DO UPDATE SET cooldown_until=excluded.cooldown_until, "
                + "completed_total=excluded.completed_total, failed_total=excluded.failed_total, mission_id=excluded.mission_id, "
                + "mission_world=excluded.mission_world, mission_x=excluded.mission_x, mission_y=excluded.mission_y, "
                + "mission_z=excluded.mission_z, mission_started_at=excluded.mission_started_at, "
                + "mission_expires_at=excluded.mission_expires_at, mission_resource_world=excluded.mission_resource_world")) {
            bind(ps, player.toString(), data);
            ps.executeUpdate();
        }
    }

    private static void bind(PreparedStatement ps, String uuid, PlayerData data) throws SQLException {
        final boolean active = data.missionId != null;
        ps.setString(1, uuid);
        ps.setLong(2, data.cooldownUntil);
        ps.setInt(3, data.completedTotal);
        ps.setInt(4, data.failedTotal);
        ps.setString(5, active ? data.missionId : null);
        ps.setString(6, active ? data.missionWorld : null);
        ps.setDouble(7, active ? data.missionX : 0);
        ps.setDouble(8, active ? data.missionY : 0);
        ps.setDouble(9, active ? data.missionZ : 0);
        ps.setLong(10, active ? data.missionStartedAt : 0);
        ps.setLong(11, active ? data.missionExpiresAt : 0);
        ps.setInt(12, active && data.missionResourceWorld ? 1 : 0);
    }

    // ---- bringing in the old data/<uuid>.yml files

    private record Old(File file, UUID id, PlayerData data) {
    }

    private void importLegacy() throws SQLException {
        File[] files = legacyDir.isDirectory() ? legacyDir.listFiles((d, n) -> n.endsWith(".yml")) : null;
        if (files == null || files.length == 0)
            return;
        if (metaValue("import") != null) {
            // brought in earlier; only the renames were left
            for (File f : files)
                setAside(f);
            return;
        }
        if (scalar("SELECT COUNT(*) FROM players") > 0) {
            log.severe("data/ holds player files, but courier.db already has players and they were not brought in from those files. "
                    + "The files were left alone and courier.db is used.");
            return;
        }
        List<Old> parsed = new ArrayList<>();
        for (File f : files) {
            String name = f.getName().substring(0, f.getName().length() - 4);
            UUID id;
            try {
                id = UUID.fromString(name);
            } catch (IllegalArgumentException e) {
                continue; // not a player file
            }
            YamlConfiguration yml = new YamlConfiguration();
            try {
                yml.load(f);
            } catch (Exception e) {
                unreadableFiles++;
                log.severe("data/" + f.getName() + " could not be read (" + e.getMessage() + "). It was left where it is and was not brought in: "
                        + "that player starts with a clean record.");
                continue;
            }
            PlayerData d = new PlayerData();
            d.cooldownUntil = yml.getLong("cooldown-until");
            d.completedTotal = yml.getInt("completed-total");
            d.failedTotal = yml.getInt("failed-total");
            d.missionId = yml.getString("mission.id");
            d.missionWorld = yml.getString("mission.world");
            d.missionX = yml.getDouble("mission.x");
            d.missionY = yml.getDouble("mission.y");
            d.missionZ = yml.getDouble("mission.z");
            d.missionStartedAt = yml.getLong("mission.started-at");
            d.missionExpiresAt = yml.getLong("mission.expires-at");
            d.missionResourceWorld = yml.getBoolean("mission.resource-world");
            parsed.add(new Old(f, id, d));
        }
        long completed = 0, failed = 0, cooldowns = 0, missions = 0;
        for (Old o : parsed) {
            completed += o.data.completedTotal;
            failed += o.data.failedTotal;
            cooldowns += o.data.cooldownUntil;
            if (o.data.missionId != null)
                missions++;
        }
        final long wantCompleted = completed, wantFailed = failed, wantCooldowns = cooldowns, wantMissions = missions;
        db.tx(c -> {
            // one transaction: a stop in the middle leaves nothing behind and the next start does all of it again
            for (Old o : parsed)
                write(c, o.id, o.data);
            long n = scalar(c, "SELECT COUNT(*) FROM players");
            long comp = scalar(c, "SELECT COALESCE(SUM(completed_total),0) FROM players");
            long fail = scalar(c, "SELECT COALESCE(SUM(failed_total),0) FROM players");
            long cool = scalar(c, "SELECT COALESCE(SUM(cooldown_until),0) FROM players");
            long mis = scalar(c, "SELECT COUNT(*) FROM players WHERE mission_id IS NOT NULL");
            if (n != parsed.size() || comp != wantCompleted || fail != wantFailed || cool != wantCooldowns || mis != wantMissions)
                throw new SQLException("what was written does not match the player files (players " + n + "/" + parsed.size()
                        + ", completed " + comp + "/" + wantCompleted + ", failed " + fail + "/" + wantFailed
                        + ", cooldowns " + cool + "/" + wantCooldowns + ", missions " + mis + "/" + wantMissions + ")");
            putMeta(c, "import", "done " + n + " players, " + comp + " completed, " + fail + " failed, " + mis + " missions, "
                    + unreadableFiles + " files not readable");
        });
        importedPlayers = parsed.size();
        log.info("The player files in data/ were brought into courier.db and checked: " + parsed.size() + " players, "
                + completed + " completed deliveries, " + failed + " failed, " + missions + " active missions"
                + (unreadableFiles > 0 ? ", " + unreadableFiles + " file(s) could not be read and were left in data/" : "") + ".");
        for (Old o : parsed)
            setAside(o.file);
    }

    private void setAside(File file) {
        // a player file that could not be read stays as it is, so it is not renamed here
        Path from = file.toPath();
        Path to = from.resolveSibling(file.getName() + ".migrated");
        try {
            if (!SafeIo.parses(from))
                return;
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.warning("Could not rename data/" + file.getName() + ": " + e.getMessage() + " (it will not be read again).");
        }
    }

    // ---- small helpers

    private String metaValue(String key) throws SQLException {
        return db.read(c -> metaValue(c, key));
    }

    private static String metaValue(Connection c, String key) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT value FROM meta WHERE key=?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static void putMeta(Connection c, String key, String value) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO meta (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }

    private static long scalar(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    private long scalar(String sql) throws SQLException {
        return db.read(c -> scalar(c, sql));
    }
}
