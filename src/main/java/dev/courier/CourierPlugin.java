package dev.courier;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

public final class CourierPlugin extends JavaPlugin {

    private volatile Config config;
    private Storage storage;
    private MissionManager manager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResource("deluxemenus/courier_menu.yml", false);
        config = Config.load(getConfig());

        Economy economy = null;
        if (getServer().getPluginManager().isPluginEnabled("Vault")) {
            RegisteredServiceProvider<Economy> provider = getServer().getServicesManager().getRegistration(Economy.class);
            economy = provider != null ? provider.getProvider() : null;
        }

        storage = new Storage(this);
        manager = new MissionManager(this, storage, economy);
        getServer().getPluginManager().registerEvents(new CourierListener(this, manager), this);

        CourierCommand command = new CourierCommand(this, manager);
        getCommand("courier").setExecutor(command);
        getCommand("courier").setTabCompleter(command);

        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI"))
            new CourierExpansion(this, manager).register();

        getServer().getScheduler().runTaskTimer(this, manager::tick, 10L, 10L);
        getServer().getScheduler().runTaskTimerAsynchronously(this, storage::flush, 100L, 100L);
    }

    @Override
    public void onDisable() {
        if (storage != null)
            storage.flushSync();
    }

    void reloadCourierConfig() {
        reloadConfig();
        config = Config.load(getConfig());
    }

    Config config() {
        return config;
    }
}
