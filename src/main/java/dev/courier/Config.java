package dev.courier;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.block.Biome;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/** Typed view over config.yml, rebuilt on every reload. Values that make no sense are corrected and reported. */
final class Config {

    enum CompleteMode { PLACE, INTERACT }

    long missionDurationMs;
    long cooldownMs;
    boolean allowSelfStart;
    boolean failOnDeath;
    boolean failOnQuit;
    boolean cooldownOnFail;
    CompleteMode completeMode;

    Material itemMaterial;
    String itemName;
    List<String> itemLore;

    List<String> dropoffWorlds;
    List<String> resourceWorlds;
    int dropoffRadius;
    int removeAfterSeconds;

    boolean particlesEnabled;
    Particle particleType;
    double particleRadius;
    double particleHeight;
    int particlePointsPerRing;
    int particleIntervalTicks;
    double particleViewDistance;

    String actionbarFormat;
    String actionbarFormatDifferentWorld;

    Map<String, WorldPicker> locationPickers;
    Set<Biome> biomeBlacklist;
    int netherMaxY;
    int scanStartY;
    Set<String> blacklistedRegions;

    boolean blockDrop, blockPlaceOutsideDropoff, blockMoveToForeignInventory, blockHopper, blockCrafting, blockAnvil, blockBundle, blockItemFrame;
    List<String> blockedCommandsWhileHolding;

    List<RewardEntry> rewards;
    RewardEntry alwaysReward;

    boolean resourceWorldEnabled;
    String resourceWorldPluginName;

    Map<String, String> messages;

    record WorldPicker(int centerX, int centerZ, int minRadius, int maxRadius, int maxAttempts) {
    }

    record ItemDef(Material material, int amount, String name, List<String> lore) {
    }

    record RewardEntry(double chance, double money, List<String> commands, List<ItemDef> items, String message) {
    }

    /**
     * @param c        the file as the admin has it
     * @param defaults the file in the jar, which supplies every message the admin's file does not have yet
     */
    static Config load(FileConfiguration c, FileConfiguration defaults, Logger log) {
        Config cfg = new Config();
        cfg.missionDurationMs = Math.max(10_000L, duration(c, "mission-duration", 3_600_000L, log));
        cfg.cooldownMs = duration(c, "cooldown", 86_400_000L, log);
        cfg.allowSelfStart = c.getBoolean("allow-self-start", true);
        cfg.failOnDeath = c.getBoolean("fail-on-death", false);
        cfg.failOnQuit = c.getBoolean("fail-on-quit", false);
        cfg.cooldownOnFail = c.getBoolean("cooldown-on-fail", true);
        String mode = c.getString("complete-mode", "PLACE");
        if (!"PLACE".equalsIgnoreCase(mode) && !"INTERACT".equalsIgnoreCase(mode))
            log.warning("complete-mode is '" + mode + "', which is not PLACE or INTERACT. Using PLACE.");
        cfg.completeMode = "INTERACT".equalsIgnoreCase(mode) ? CompleteMode.INTERACT : CompleteMode.PLACE;

        cfg.itemMaterial = material("item.material", c.getString("item.material"), Material.PAPER, log);
        if (cfg.completeMode == CompleteMode.PLACE && !cfg.itemMaterial.isBlock()) {
            log.warning("complete-mode is PLACE but item.material " + cfg.itemMaterial + " cannot be placed as a block, so deliveries could never be finished. Using INTERACT (right click) instead.");
            cfg.completeMode = CompleteMode.INTERACT;
        }
        cfg.itemName = c.getString("item.name", "Delivery Package");
        cfg.itemLore = c.getStringList("item.lore");

        cfg.dropoffWorlds = c.getStringList("dropoff.worlds");
        cfg.resourceWorlds = c.getStringList("dropoff.resource-worlds");
        cfg.dropoffRadius = clamp(c.getInt("dropoff.radius", 5), 1, 100, "dropoff.radius", log);
        cfg.removeAfterSeconds = clamp(c.getInt("dropoff.remove-after", 0), 0, 3600, "dropoff.remove-after", log);

        cfg.particlesEnabled = c.getBoolean("particles.enabled", true);
        cfg.particleType = particle(c.getString("particles.type"), log);
        cfg.particleRadius = clamp(c.getDouble("particles.radius", 0.6), 0, 10, "particles.radius", log);
        cfg.particleHeight = clamp(c.getDouble("particles.height", 4), 0, 64, "particles.height", log);
        cfg.particlePointsPerRing = clamp(c.getInt("particles.points-per-ring", 16), 1, 64, "particles.points-per-ring", log);
        cfg.particleIntervalTicks = clamp(c.getInt("particles.interval-ticks", 10), 1, 200, "particles.interval-ticks", log);
        cfg.particleViewDistance = clamp(c.getDouble("particles.view-distance", 48), 1, 256, "particles.view-distance", log);

        cfg.actionbarFormat = c.getString("actionbar.format", "");
        cfg.actionbarFormatDifferentWorld = c.getString("actionbar.format-different-world", cfg.actionbarFormat);

        cfg.locationPickers = new HashMap<>();
        ConfigurationSection worldsSection = c.getConfigurationSection("location-picker.worlds");
        if (worldsSection != null) {
            for (String world : worldsSection.getKeys(false)) {
                ConfigurationSection w = worldsSection.getConfigurationSection(world);
                if (w == null)
                    continue;
                int min = Math.max(0, w.getInt("min-radius", 0));
                int max = Math.max(min, w.getInt("max-radius", 1000));
                cfg.locationPickers.put(world, new WorldPicker(
                    w.getInt("center-x"), w.getInt("center-z"), min, max,
                    clamp(w.getInt("max-attempts", 40), 1, 500, "location-picker.worlds." + world + ".max-attempts", log)));
            }
        }
        cfg.biomeBlacklist = resolveBiomes(c.getStringList("location-picker.biome-blacklist"), log);
        cfg.netherMaxY = c.getInt("location-picker.nether-max-y", 120);
        cfg.scanStartY = c.getInt("location-picker.scan-start-y", 250);
        cfg.blacklistedRegions = new HashSet<>(c.getStringList("location-picker.blacklisted-regions"));

        cfg.blockDrop = c.getBoolean("protection.block-drop", true);
        cfg.blockPlaceOutsideDropoff = c.getBoolean("protection.block-place-outside-dropoff", true);
        cfg.blockMoveToForeignInventory = c.getBoolean("protection.block-move-to-foreign-inventory", true);
        cfg.blockHopper = c.getBoolean("protection.block-hopper", true);
        cfg.blockCrafting = c.getBoolean("protection.block-crafting", true);
        cfg.blockAnvil = c.getBoolean("protection.block-anvil", true);
        cfg.blockBundle = c.getBoolean("protection.block-bundle", true);
        cfg.blockItemFrame = c.getBoolean("protection.block-item-frame", true);
        cfg.blockedCommandsWhileHolding = lower(c.getStringList("blocked-commands-while-holding"));

        cfg.rewards = new ArrayList<>();
        List<Map<?, ?>> rewardMaps = c.getMapList("rewards");
        for (int i = 0; i < rewardMaps.size(); i++)
            cfg.rewards.add(readReward(rewardMaps.get(i), "rewards[" + (i + 1) + "]", log));
        ConfigurationSection alwaysSection = c.getConfigurationSection("always-rewards");
        cfg.alwaysReward = alwaysSection != null
            ? readReward(alwaysSection.getValues(false), "always-rewards", log)
            : new RewardEntry(100, 0, List.of(), List.of(), null);

        cfg.resourceWorldEnabled = c.getBoolean("resourceworld.enabled", false);
        cfg.resourceWorldPluginName = c.getString("resourceworld.plugin-name", "");

        cfg.messages = new HashMap<>();
        for (FileConfiguration source : new FileConfiguration[]{defaults, c}) {
            ConfigurationSection msgSection = source == null ? null : source.getConfigurationSection("messages");
            if (msgSection != null)
                for (String key : msgSection.getKeys(false))
                    if (msgSection.isString(key))
                        cfg.messages.put(key, msgSection.getString(key));
        }
        return cfg;
    }

    private static RewardEntry readReward(Map<?, ?> m, String where, Logger log) {
        List<ItemDef> items = new ArrayList<>();
        if (m.get("items") instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> item))
                    continue;
                Material material = material(where + ".items.material", text(item.get("material")), null, log);
                if (material == null)
                    continue; // already reported
                items.add(new ItemDef(material, (int) Math.max(1, Math.min(64, number(item.get("amount"), 1))),
                    item.get("name") == null ? null : text(item.get("name")), strings(item.get("lore"))));
            }
        }
        double chance = Math.max(0, number(m.get("chance"), 100));
        double money = Math.max(0, number(m.get("money"), 0));
        String message = m.get("message") == null ? null : text(m.get("message"));
        return new RewardEntry(chance, money, strings(m.get("commands")), items, message);
    }

    /** A duration such as 1h30m. A value that is not one is reported and replaced. */
    private static long duration(FileConfiguration c, String path, long fallback, Logger log) {
        String value = c.getString(path);
        if (value != null && !value.isBlank() && !TimeUtil.valid(value))
            log.warning(path + " is '" + value + "', which is not a time like 30m, 1h or 1h30m. Using " + TimeUtil.format(fallback) + ".");
        return TimeUtil.parseMillis(value, fallback);
    }

    private static int clamp(int value, int min, int max, String path, Logger log) {
        if (value >= min && value <= max)
            return value;
        int fixed = Math.max(min, Math.min(max, value));
        log.warning(path + " is " + value + ", which is outside " + min + " to " + max + ". Using " + fixed + ".");
        return fixed;
    }

    private static double clamp(double value, double min, double max, String path, Logger log) {
        if (value >= min && value <= max)
            return value;
        double fixed = Double.isNaN(value) ? min : Math.max(min, Math.min(max, value));
        log.warning(path + " is " + value + ", which is outside " + min + " to " + max + ". Using " + fixed + ".");
        return fixed;
    }

    private static Set<Biome> resolveBiomes(List<String> names, Logger log) {
        Set<Biome> set = new HashSet<>();
        var registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.BIOME);
        for (String name : names) {
            NamespacedKey key = NamespacedKey.fromString(name.toLowerCase(Locale.ROOT));
            Biome biome = key != null ? registry.get(key) : null;
            if (biome != null)
                set.add(biome);
            else
                log.warning("location-picker.biome-blacklist has '" + name + "', which is not a biome on this server.");
        }
        return set;
    }

    /** The material of that name, the fallback when the name is missing, or null (after a warning) when it is wrong and there is no fallback. */
    private static Material material(String path, String name, Material fallback, Logger log) {
        if (name == null || name.isBlank())
            return fallback;
        Material material = Material.matchMaterial(name.trim());
        if (material != null && material.isItem() && !material.isAir())
            return material;
        log.warning(path + " is '" + name + "', which is not an item" + (fallback != null ? ". Using " + fallback + "." : ". It is skipped."));
        return fallback;
    }

    private static Particle particle(String name, Logger log) {
        if (name == null || name.isBlank())
            return Particle.END_ROD;
        try {
            Particle particle = Particle.valueOf(name.trim().toUpperCase(Locale.ROOT));
            if (particle.getDataType() != Void.class) {
                log.warning("particles.type " + particle + " needs extra data and cannot be used here. Using END_ROD.");
                return Particle.END_ROD;
            }
            return particle;
        } catch (IllegalArgumentException e) {
            log.warning("particles.type is '" + name + "', which is not a particle. Using END_ROD.");
            return Particle.END_ROD;
        }
    }

    private static double number(Object value, double fallback) {
        if (value instanceof Number n && Double.isFinite(n.doubleValue()))
            return n.doubleValue();
        if (value instanceof String s)
            try {
                double parsed = Double.parseDouble(s.trim());
                return Double.isFinite(parsed) ? parsed : fallback;
            } catch (NumberFormatException ignored) {
                // falls through
            }
        return fallback;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static List<String> strings(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> list)
            for (Object o : list)
                if (o != null)
                    out.add(String.valueOf(o));
        return out;
    }

    private static List<String> lower(List<String> values) {
        List<String> out = new ArrayList<>(values.size());
        for (String v : values)
            out.add(v.trim().toLowerCase(Locale.ROOT));
        return out;
    }

    String message(String key, String fallback) {
        return messages.getOrDefault(key, fallback);
    }
}
