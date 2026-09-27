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

/** Typed view over config.yml, rebuilt on every reload. */
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

    static Config load(FileConfiguration c) {
        Config cfg = new Config();
        cfg.missionDurationMs = TimeUtil.parseMillis(c.getString("mission-duration"), 3_600_000L);
        cfg.cooldownMs = TimeUtil.parseMillis(c.getString("cooldown"), 86_400_000L);
        cfg.allowSelfStart = c.getBoolean("allow-self-start", true);
        cfg.failOnDeath = c.getBoolean("fail-on-death", false);
        cfg.failOnQuit = c.getBoolean("fail-on-quit", false);
        cfg.cooldownOnFail = c.getBoolean("cooldown-on-fail", true);
        cfg.completeMode = "INTERACT".equalsIgnoreCase(c.getString("complete-mode")) ? CompleteMode.INTERACT : CompleteMode.PLACE;

        cfg.itemMaterial = materialOr(c.getString("item.material"), Material.PAPER);
        cfg.itemName = c.getString("item.name", "Delivery Package");
        cfg.itemLore = c.getStringList("item.lore");

        cfg.dropoffWorlds = c.getStringList("dropoff.worlds");
        cfg.resourceWorlds = c.getStringList("dropoff.resource-worlds");
        cfg.dropoffRadius = c.getInt("dropoff.radius", 5);
        cfg.removeAfterSeconds = c.getInt("dropoff.remove-after", 0);

        cfg.particlesEnabled = c.getBoolean("particles.enabled", true);
        cfg.particleType = particleOr(c.getString("particles.type"), Particle.END_ROD);
        cfg.particleRadius = c.getDouble("particles.radius", 0.6);
        cfg.particleHeight = c.getDouble("particles.height", 4);
        cfg.particlePointsPerRing = c.getInt("particles.points-per-ring", 16);
        cfg.particleIntervalTicks = Math.max(1, c.getInt("particles.interval-ticks", 10));
        cfg.particleViewDistance = c.getDouble("particles.view-distance", 48);

        cfg.actionbarFormat = c.getString("actionbar.format", "");
        cfg.actionbarFormatDifferentWorld = c.getString("actionbar.format-different-world", cfg.actionbarFormat);

        cfg.locationPickers = new HashMap<>();
        ConfigurationSection worldsSection = c.getConfigurationSection("location-picker.worlds");
        if (worldsSection != null) {
            for (String world : worldsSection.getKeys(false)) {
                ConfigurationSection w = worldsSection.getConfigurationSection(world);
                if (w == null)
                    continue;
                cfg.locationPickers.put(world, new WorldPicker(
                    w.getInt("center-x"), w.getInt("center-z"),
                    w.getInt("min-radius", 0), w.getInt("max-radius", 1000),
                    w.getInt("max-attempts", 40)));
            }
        }
        cfg.biomeBlacklist = resolveBiomes(c.getStringList("location-picker.biome-blacklist"));
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
        for (int i = 0; i < rewardMaps.size(); i++) {
            ConfigurationSection s = c.getConfigurationSection("rewards." + i);
            if (s != null)
                cfg.rewards.add(readReward(s));
        }
        ConfigurationSection alwaysSection = c.getConfigurationSection("always-rewards");
        cfg.alwaysReward = alwaysSection != null ? readReward(alwaysSection) : new RewardEntry(100, 0, List.of(), List.of(), null);

        cfg.resourceWorldEnabled = c.getBoolean("resourceworld.enabled", false);
        cfg.resourceWorldPluginName = c.getString("resourceworld.plugin-name", "");

        cfg.messages = new HashMap<>();
        ConfigurationSection msgSection = c.getConfigurationSection("messages");
        if (msgSection != null)
            for (String key : msgSection.getKeys(false))
                cfg.messages.put(key, msgSection.getString(key));

        return cfg;
    }

    private static RewardEntry readReward(ConfigurationSection s) {
        List<ItemDef> items = new ArrayList<>();
        List<Map<?, ?>> itemMaps = s.getMapList("items");
        for (int i = 0; i < itemMaps.size(); i++) {
            ConfigurationSection is = s.getConfigurationSection("items." + i);
            if (is == null)
                continue;
            items.add(new ItemDef(
                materialOr(is.getString("material"), Material.STONE),
                Math.max(1, is.getInt("amount", 1)),
                is.getString("name"),
                is.getStringList("lore")));
        }
        return new RewardEntry(s.getDouble("chance", 100), s.getDouble("money", 0), s.getStringList("commands"), items, s.getString("message"));
    }

    private static Set<Biome> resolveBiomes(List<String> names) {
        Set<Biome> set = new HashSet<>();
        var registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.BIOME);
        for (String name : names) {
            NamespacedKey key = NamespacedKey.fromString(name.toLowerCase(Locale.ROOT));
            Biome biome = key != null ? registry.get(key) : null;
            if (biome != null)
                set.add(biome);
        }
        return set;
    }

    private static Material materialOr(String name, Material fallback) {
        if (name == null)
            return fallback;
        try {
            return Material.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static Particle particleOr(String name, Particle fallback) {
        if (name == null)
            return fallback;
        try {
            return Particle.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static List<String> lower(List<String> values) {
        List<String> out = new ArrayList<>(values.size());
        for (String v : values)
            out.add(v.toLowerCase(Locale.ROOT));
        return out;
    }

    String message(String key, String fallback) {
        return messages.getOrDefault(key, fallback);
    }
}
