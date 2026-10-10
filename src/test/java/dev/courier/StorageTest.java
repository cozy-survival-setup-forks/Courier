package dev.courier;

import dev.courier.safe.Db;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageTest {

    private static final Logger LOG = Logger.getAnonymousLogger();
    private static final UUID STEVE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ALEX = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @TempDir
    Path dir;

    private Storage open() throws Exception {
        Storage storage = new Storage(dir, LOG);
        storage.open(3);
        return storage;
    }

    private String url() {
        return "jdbc:sqlite:" + dir.resolve("courier.db").toAbsolutePath();
    }

    private void oldFile(UUID id, String text) throws Exception {
        Files.createDirectories(dir.resolve("data"));
        Files.writeString(dir.resolve("data").resolve(id + ".yml"), text);
    }

    private static final String STEVE_FILE = """
            cooldown-until: 1790000000000
            completed-total: 7
            failed-total: 2
            mission:
              id: 0f3c4d5e-0000-0000-0000-000000000001
              world: world_the_end
              x: 10.5
              y: 70.0
              z: -20.25
              started-at: 1789999000000
              expires-at: 1790000999999
              resource-world: true
            """;

    @Test
    void aPlayerComesBackAfterARestart() throws Exception {
        Storage storage = open();
        PlayerData data = storage.get(STEVE);
        data.completedTotal = 3;
        data.cooldownUntil = 123456789L;
        data.missionId = "abc";
        data.missionWorld = "world";
        data.missionX = 1.5;
        data.missionResourceWorld = true;
        storage.markDirty(STEVE);
        storage.close();

        Storage again = open();
        PlayerData back = again.get(STEVE);
        assertEquals(3, back.completedTotal);
        assertEquals(123456789L, back.cooldownUntil);
        assertEquals("abc", back.missionId);
        assertEquals(1.5, back.missionX);
        assertTrue(back.missionResourceWorld);
        assertEquals(0, again.get(ALEX).completedTotal);
        again.close();
    }

    @Test
    void oldPlayerFilesAreBroughtInOnceAndSetAside() throws Exception {
        oldFile(STEVE, STEVE_FILE);
        oldFile(ALEX, "cooldown-until: 5\ncompleted-total: 1\nfailed-total: 0\n");
        Storage storage = open();

        PlayerData steve = storage.get(STEVE);
        assertEquals(7, steve.completedTotal);
        assertEquals(2, steve.failedTotal);
        assertEquals(1790000000000L, steve.cooldownUntil);
        assertEquals("world_the_end", steve.missionWorld);
        assertEquals(-20.25, steve.missionZ);
        assertTrue(steve.missionResourceWorld);
        assertNull(storage.get(ALEX).missionId);
        assertEquals(2, storage.storedPlayers());
        assertFalse(Files.exists(dir.resolve("data").resolve(STEVE + ".yml")));
        assertTrue(Files.exists(dir.resolve("data").resolve(STEVE + ".yml.migrated")));
        storage.close();

        // a file that shows up later is not read again
        oldFile(STEVE, "completed-total: 999\n");
        Storage again = open();
        assertEquals(7, again.get(STEVE).completedTotal);
        again.close();
    }

    @Test
    void anUnreadablePlayerFileIsLeftAloneAndTheRestAreBroughtIn() throws Exception {
        oldFile(STEVE, STEVE_FILE);
        oldFile(ALEX, "completed-total: [unclosed\n");
        Storage storage = open();
        assertEquals(7, storage.get(STEVE).completedTotal);
        assertEquals(1, storage.storedPlayers());
        assertTrue(Files.exists(dir.resolve("data").resolve(ALEX + ".yml")), "the unreadable file stays where it is");
        assertEquals("completed-total: [unclosed\n", Files.readString(dir.resolve("data").resolve(ALEX + ".yml")));
        storage.close();
    }

    @Test
    void anImportThatStopsHalfWayLeavesTheFilesAndNothingElse() throws Exception {
        oldFile(STEVE, STEVE_FILE);
        oldFile(ALEX, "cooldown-until: 5\ncompleted-total: 1\nfailed-total: 0\n");
        try (Connection c = DriverManager.getConnection(url()); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            s.execute("CREATE TABLE players (uuid TEXT PRIMARY KEY, cooldown_until INTEGER NOT NULL, completed_total INTEGER NOT NULL, "
                    + "failed_total INTEGER NOT NULL, mission_id TEXT, mission_world TEXT, mission_x REAL NOT NULL, mission_y REAL NOT NULL, "
                    + "mission_z REAL NOT NULL, mission_started_at INTEGER NOT NULL, mission_expires_at INTEGER NOT NULL, "
                    + "mission_resource_world INTEGER NOT NULL)");
            s.execute("CREATE TRIGGER stop_here BEFORE INSERT ON players WHEN NEW.uuid='" + ALEX + "' BEGIN SELECT RAISE(ABORT, 'stopped'); END");
            s.execute("PRAGMA user_version=1");
        }
        Storage first = new Storage(dir, LOG);
        assertThrows(SQLException.class, () -> first.open(3));
        assertTrue(Files.exists(dir.resolve("data").resolve(STEVE + ".yml")));
        assertTrue(Files.exists(dir.resolve("data").resolve(ALEX + ".yml")));
        try (Connection c = DriverManager.getConnection(url()); Statement s = c.createStatement()) {
            try (var rs = s.executeQuery("SELECT COUNT(*) FROM players")) {
                rs.next();
                assertEquals(0, rs.getInt(1), "nothing was kept from the half import");
            }
            s.execute("DROP TRIGGER stop_here");
        }
        Storage second = open();
        assertEquals(2, second.storedPlayers());
        assertEquals(7, second.get(STEVE).completedTotal);
        second.close();
    }

    @Test
    void aDamagedDatabaseIsNotReplacedByAnEmptyOne() throws Exception {
        open().close();
        byte[] garbage = "this is not a database, not at all, not one bit".getBytes();
        Files.write(dir.resolve("courier.db"), garbage);
        Storage damaged = new Storage(dir, LOG);
        assertThrows(Db.CorruptException.class, () -> damaged.open(3));
        assertTrue(Arrays.equals(garbage, Files.readAllBytes(dir.resolve("courier.db"))));
    }

    @Test
    void aDamagedDatabaseComesBackFromTheNewestGoodBackup() throws Exception {
        Storage storage = open();
        storage.get(STEVE).completedTotal = 4;
        storage.markDirty(STEVE);
        storage.flush();
        assertTrue(storage.backup());
        storage.close();

        Files.write(dir.resolve("courier.db"), "garbage garbage garbage garbage".getBytes());
        Files.deleteIfExists(dir.resolve("courier.db-wal"));
        Storage restored = open();
        assertEquals(4, restored.get(STEVE).completedTotal);
        restored.close();
    }

    @Test
    void aDatabaseFromANewerVersionIsNotTouched() throws Exception {
        open().close();
        try (Connection c = DriverManager.getConnection(url()); Statement s = c.createStatement()) {
            s.execute("PRAGMA user_version=99");
        }
        byte[] before = Files.readAllBytes(dir.resolve("courier.db"));
        Storage storage = new Storage(dir, LOG);
        assertThrows(Db.NewerSchemaException.class, () -> storage.open(3));
        assertTrue(Arrays.equals(before, Files.readAllBytes(dir.resolve("courier.db"))));
    }

    @Test
    void aRewardLeftUnfinishedIsFlaggedAndNotRepeated() throws Exception {
        Storage storage = open();
        String id = storage.journal().begin("mission-reward", "player=Steve");
        storage.close(); // stopped before the reward was marked as paid

        Storage again = open();
        assertEquals(1, again.journal().unknown().size());
        assertTrue(again.journal().resolve(id));
        assertTrue(again.journal().unknown().isEmpty());
        again.close();
    }

    @Test
    void theServerIdIsKeptInTheDatabase() throws Exception {
        Storage storage = open();
        assertNull(storage.serverIdSlot().read());
        storage.serverIdSlot().write("0a1b2c3d-1111-2222-3333-444455556666");
        storage.close();
        Storage again = open();
        assertEquals("0a1b2c3d-1111-2222-3333-444455556666", again.serverIdSlot().read());
        again.close();
    }
}
