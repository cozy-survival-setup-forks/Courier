package dev.courier;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldUnloadEvent;

/**
 * Resource-world dropoffs go stale the moment their world unloads/regenerates. Missions never
 * cache a World reference for this reason - MissionManager.tick() re-checks
 * Bukkit.getWorld(name) == null every second regardless, so this listener is just the fast path
 * (instant reroll instead of waiting up to a second for the next tick).
 *
 * No fork-specific reset event is hooked here: resourceworld.plugin-name is read but there's no
 * known concrete event class to bind to across forks, and guessing one would just be dead code
 * that silently never fires. The tick-loop poll above covers the same case either way.
 */
final class ResourceWorldSupport implements Listener {

    private final MissionManager manager;

    ResourceWorldSupport(CourierPlugin plugin, MissionManager manager) {
        this.manager = manager;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler
    public void onUnload(WorldUnloadEvent event) {
        for (Mission mission : manager.targeting(event.getWorld().getName()))
            manager.reroll(mission);
    }
}
