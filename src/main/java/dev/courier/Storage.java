package dev.courier;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One YAML file per player under data/. Reads happen on join (small file, negligible cost).
 * Writes are debounced: callers mark a player dirty and a repeating task flushes everyone
 * dirty every few seconds on an async thread, instead of writing on every change.
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
            if (data != null)
                save(id, data);
        }
    }

    /** Called on shutdown - flush must be synchronous there, no time for a repeating task. */
    void flushSync() {
        for (UUID id : dirty) {
            PlayerData data = cache.get(id);
            if (data != null)
                save(id, data);
        }
        dirty.clear();
    }

    void unload(UUID player) {
        PlayerData data = cache.remove(player);
        if (data != null)
            save(player, data);
        dirty.remove(player);
    }

    private PlayerData load(UUID player) {
        PlayerData data = new PlayerData();
        File file = new File(dir, player + ".yml");
        if (!file.exists())
            return data;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
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

    private void save(UUID player, PlayerData data) {
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("cooldown-until", data.cooldownUntil);
        yml.set("completed-total", data.completedTotal);
        yml.set("failed-total", data.failedTotal);
        if (data.missionId != null) {
            yml.set("mission.id", data.missionId);
            yml.set("mission.world", data.missionWorld);
            yml.set("mission.x", data.missionX);
            yml.set("mission.y", data.missionY);
            yml.set("mission.z", data.missionZ);
            yml.set("mission.started-at", data.missionStartedAt);
            yml.set("mission.expires-at", data.missionExpiresAt);
            yml.set("mission.resource-world", data.missionResourceWorld);
        }
        try {
            yml.save(new File(dir, player + ".yml"));
        } catch (Exception e) {
            plugin.getLogger().warning("Could not save data for " + player + ": " + e.getMessage());
        }
    }
}
