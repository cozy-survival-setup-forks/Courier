package dev.courier;

import dev.courier.safe.Doctor;
import dev.courier.safe.FileBackups;
import dev.courier.safe.Guard;
import dev.courier.safe.Health;
import dev.courier.safe.Prep;
import dev.courier.safe.ServerId;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public final class CourierPlugin extends JavaPlugin {

    private static final int CONFIG_VERSION = 1;

    private final List<Prep.Spec> files = List.of(
            new Prep.Spec("config.yml", "config-version", CONFIG_VERSION, Prep.configMigrator(CONFIG_VERSION), rules -> {
                rules.range("backup.interval-hours", 1, 168);
                rules.range("backup.keep", 1, 90);
            }));

    private volatile Config config;
    private Storage storage;
    private BukkitTask backupTask;
    private MissionManager manager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!new File(getDataFolder(), "deluxemenus/courier_menu.yml").exists())
            saveResource("deluxemenus/courier_menu.yml", false);
        Health.storage("SQLite courier.db for players' cooldowns, totals and active deliveries; YAML for config.yml");
        Prep.startup(this, files);

        try {
            config = Config.load(strict(), jarConfig(), getLogger());
        } catch (IOException | InvalidConfigurationException e) {
            getLogger().severe("Courier cannot start: " + e.getMessage());
            getLogger().severe("Fix config.yml (the line is named above) or delete it to get a fresh one, then start the server again.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        storage = new Storage(getDataFolder().toPath(), getLogger());
        try {
            storage.open(backupKeep());
        } catch (IOException | SQLException e) {
            // the player data is never replaced by an empty set: the plugin stays off until it is sorted out
            getLogger().severe("Courier cannot use its player data and is switching itself off so nothing is reset or paid twice: " + e.getMessage());
            Health.failure("player data could not be opened: " + e.getMessage());
            storage = null;
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
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

        boolean beacon = getConfig().getBoolean("metrics.enabled", true);
        Metrics.start(this, ServerId.resolve(getDataFolder().toPath(), storage.serverIdSlot(), beacon, getLogger()));
        scheduleBackups();
        Banner.print(this, "Safe travels.");
        summary(placeholders);
    }

    @Override
    public void onDisable() {
        if (backupTask != null)
            backupTask.cancel();
        if (storage != null)
            storage.close();
    }

    private int backupKeep() {
        return Math.max(1, Math.min(90, getConfig().getInt("backup.keep", 7)));
    }

    /** (Re)starts the timer of the database copies from backup.interval-hours and backup.keep. */
    private void scheduleBackups() {
        int hours = Math.max(1, Math.min(168, getConfig().getInt("backup.interval-hours", 6)));
        storage.configureBackups(backupKeep());
        if (backupTask != null)
            backupTask.cancel();
        backupTask = getServer().getScheduler().runTaskTimerAsynchronously(this, storage::backup, 20L * 60, hours * 3600L * 20L);
    }

    /** The text of /courier doctor. */
    List<String> doctor() {
        List<String> extra = new ArrayList<>(Prep.versionLines(this, files));
        extra.add("Player database: courier.db, schema " + storage.schemaVersion() + " (this plugin writes " + Storage.SCHEMA + ")");
        extra.add("Stored players: " + storage.storedPlayers());
        String newest = storage.newestBackup();
        extra.add("Newest database copy on disk: " + (newest == null ? "none yet" : newest));
        extra.add("Pending writes: " + storage.pendingWrites());
        try {
            List<String> unknown = storage.journal() == null ? List.of() : storage.journal().unknown();
            extra.add("Rewards that may or may not have been paid (not repeated): " + unknown.size());
            unknown.forEach(line -> extra.add("  " + line + "   (after checking: /courier doctor resolve <id>)"));
        } catch (SQLException e) {
            extra.add("Reward record could not be read: " + e.getMessage());
        }
        return Doctor.report(getName(), getPluginMeta().getVersion(), extra);
    }

    boolean resolvePayout(String id) {
        try {
            return storage.journal() != null && storage.journal().resolve(id);
        } catch (SQLException e) {
            getLogger().severe("Could not update the reward record: " + e.getMessage());
            return false;
        }
    }

    /** /courier backup now: a checked copy of the database and of config.yml. */
    boolean backupNow() {
        storage.flushSync();
        boolean database = storage.backup();
        boolean settings = FileBackups.snapshot(getDataFolder().toPath(), new ArrayList<>(Prep.fileNames(files)), 5, getLogger());
        return database && settings;
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
        List<Guard.Problem> problems = Prep.validate(this, files);
        if (!problems.isEmpty()) {
            Prep.logRejected(this, problems);
            return false;
        }
        try {
            config = Config.load(strict(), jarConfig(), getLogger());
        } catch (IOException | InvalidConfigurationException e) {
            getLogger().severe("The reload was cancelled and the old settings stay: " + e.getMessage());
            return false;
        }
        reloadConfig();
        scheduleBackups();
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
