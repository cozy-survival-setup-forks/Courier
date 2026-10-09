package dev.courier;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One YAML file per player under data/. Reads happen on join (small file, negligible cost).
 * Writes are debounced: callers mark a player dirty and a repeating task flushes everyone
 * dirty every few seconds on an async thread, instead of writing on every change. A file is
 * written to a temporary name and moved into place, so a crash cannot leave half a file (which
 * would lose a cooldown). A write that fails is tried again on the next flush.
 */
final class Storage {

    private final JavaPlugin plugin;
    private final File dir;
    private final Map<UUID, PlayerData> cache = new ConcurrentHashMap<>();
    private final Set<UUID> dirty = ConcurrentHashMap.newKeySet();

    Storage(JavaPlugin plugin) {
        this.plugin = plugin;
        this.dir = new File(plugin.getDataFolder(), "data");
        dir.mkdirs();
    }

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

    /** Called on shutdown - flush must be synchronous there, no time for a repeating task. */
    void flushSync() {
        for (UUID id : dirty.toArray(UUID[]::new)) {
            PlayerData data = cache.get(id);
            if (data != null)
                save(id, data);
        }
        dirty.clear();
    }

    /** A player left: write what is pending and forget them, so the cache only holds players who are online. */
    void unload(UUID player) {
        PlayerData data = cache.remove(player);
        if (data != null && dirty.remove(player))
            save(player, data);
    }

    private PlayerData load(UUID player) {
        PlayerData data = new PlayerData();
        File file = new File(dir, player + ".yml");
        if (!file.exists())
            return data;
        YamlConfiguration yml = new YamlConfiguration();
        try {
            yml.load(file);
        } catch (Exception e) {
            // keep the broken file for the admin; the player starts clean and the new data goes to a fresh file
            File broken = new File(dir, player + ".yml.broken");
            if (file.renameTo(broken))
                plugin.getLogger().warning("The data of " + player + " could not be read (" + e.getMessage() + "), it was moved to " + broken.getName() + ".");
            return data;
        }
        data.cooldownUntil = yml.getLong("cooldown-until");
        data.completedTotal = yml.getInt("completed-total");
        data.failedTotal = yml.getInt("failed-total");
        data.missionId = yml.getString("mission.id");
        data.missionWorld = yml.getString("mission.world");
        data.missionX = yml.getDouble("mission.x");
        data.missionY = yml.getDouble("mission.y");
        data.missionZ = yml.getDouble("mission.z");
        data.missionStartedAt = yml.getLong("mission.started-at");
        data.missionExpiresAt = yml.getLong("mission.expires-at");
        data.missionResourceWorld = yml.getBoolean("mission.resource-world");
        return data;
    }

    private boolean save(UUID player, PlayerData data) {
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("cooldown-until", data.cooldownUntil);
        yml.set("completed-total", data.completedTotal);
        yml.set("failed-total", data.failedTotal);
        String missionId = data.missionId;
        if (missionId != null) {
            yml.set("mission.id", missionId);
            yml.set("mission.world", data.missionWorld);
            yml.set("mission.x", data.missionX);
            yml.set("mission.y", data.missionY);
            yml.set("mission.z", data.missionZ);
            yml.set("mission.started-at", data.missionStartedAt);
            yml.set("mission.expires-at", data.missionExpiresAt);
            yml.set("mission.resource-world", data.missionResourceWorld);
        }
        File target = new File(dir, player + ".yml");
        File tmp = new File(dir, player + ".yml.tmp");
        try {
            Files.writeString(tmp.toPath(), yml.saveToString(), StandardCharsets.UTF_8);
            try {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save data for " + player + ": " + e.getMessage());
            return false;
        }
    }
}
