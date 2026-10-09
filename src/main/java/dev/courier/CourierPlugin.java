package dev.courier;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class CourierPlugin extends JavaPlugin {

    private volatile Config config;
    private Storage storage;
    private MissionManager manager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!new File(getDataFolder(), "deluxemenus/courier_menu.yml").exists())
            saveResource("deluxemenus/courier_menu.yml", false);

        try {
            config = Config.load(strict(), jarConfig(), getLogger());
        } catch (IOException | InvalidConfigurationException e) {
            getLogger().severe("Courier cannot start: " + e.getMessage());
            getLogger().severe("Fix config.yml (the line is named above) or delete it to get a fresh one, then start the server again.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        storage = new Storage(this);
        manager = new MissionManager(this, storage, this::economy);
        getServer().getPluginManager().registerEvents(new CourierListener(this, manager), this);

        CourierCommand command = new CourierCommand(this, manager);
        getCommand("courier").setExecutor(command);
        getCommand("courier").setTabCompleter(command);

        boolean placeholders = getServer().getPluginManager().isPluginEnabled("PlaceholderAPI");
        if (placeholders)
            new CourierExpansion(this, manager).register();

        getServer().getScheduler().runTaskTimer(this, manager::tick, 10L, 10L);
        getServer().getScheduler().runTaskTimerAsynchronously(this, storage::flush, 100L, 100L);

        Metrics.start(this);
        Banner.print(this, "Safe travels.");
        summary(placeholders);
    }

    @Override
    public void onDisable() {
        if (storage != null)
            storage.flushSync();
    }

    /** config.yml as the admin has it, with a mistake in it reported instead of silently replaced by the defaults. */
    private FileConfiguration strict() throws IOException, InvalidConfigurationException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(new File(getDataFolder(), "config.yml"));
        return yaml;
    }

    private FileConfiguration jarConfig() throws IOException {
        try (var in = getResource("config.yml")) {
            return in == null ? new YamlConfiguration() : YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    /** Returns false (and keeps the current settings) when config.yml has a mistake. */
    boolean reloadCourierConfig() {
        try {
            config = Config.load(strict(), jarConfig(), getLogger());
        } catch (IOException | InvalidConfigurationException e) {
            getLogger().severe("The reload was cancelled and the old settings stay: " + e.getMessage());
            return false;
        }
        reloadConfig();
        return true;
    }

    private Payout economy() {
        return getServer().getPluginManager().isPluginEnabled("Vault") ? VaultPayout.find(getServer()) : null;
    }

    private void summary(boolean placeholders) {
        Config cfg = config;
        List<String> worlds = new ArrayList<>(cfg.dropoffWorlds);
        worlds.addAll(cfg.resourceWorlds);
        getLogger().info("Ready: " + cfg.rewards.size() + " reward tier(s), " + cfg.completeMode.name().toLowerCase() + " to finish, "
            + TimeUtil.format(cfg.missionDurationMs) + " per delivery, " + TimeUtil.format(cfg.cooldownMs) + " cooldown, drop-off worlds: "
            + (worlds.isEmpty() ? "none" : String.join(", ", worlds)));
        if (worlds.isEmpty())
            getLogger().warning("dropoff.worlds is empty, so no delivery can start.");
        for (String world : worlds) {
            if (!cfg.locationPickers.containsKey(world))
                getLogger().warning("World '" + world + "' has no entry in location-picker.worlds, so it is never used.");
        }
        if (cfg.rewards.isEmpty())
            getLogger().warning("rewards is empty, so a finished delivery only pays always-rewards.");
        boolean pays = cfg.alwaysReward.money() > 0 || cfg.rewards.stream().anyMatch(r -> r.money() > 0);
        if (pays && !getServer().getPluginManager().isPluginEnabled("Vault"))
            getLogger().warning("Rewards pay money but Vault is not installed, so no money will be paid.");
        getLogger().info("PlaceholderAPI " + (placeholders ? "found, placeholders are on" : "not found, no placeholders")
            + "; WorldGuard " + (getServer().getPluginManager().isPluginEnabled("WorldGuard") ? "found" : "not found"));
    }

    Config config() {
        return config;
    }
}
