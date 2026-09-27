package dev.courier;

import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Async-safe random location scanning. The chunk is force-loaded via getChunkAtAsync, and the
 * actual block/biome reads run on that future's completion (off the main thread) - only the
 * final Location is handed back to the main thread by the caller.
 */
final class LocationPicker {

    private static final Random RANDOM = new Random();

    private static final java.util.Set<Material> UNSAFE = java.util.Set.of(
        Material.WATER, Material.LAVA, Material.POWDER_SNOW, Material.CACTUS, Material.MAGMA_BLOCK);

    private final CourierPlugin plugin;

    LocationPicker(CourierPlugin plugin) {
        this.plugin = plugin;
    }

    /** Tries each world in turn (randomised order), first valid location wins. Null if all fail. */
    CompletableFuture<Location> pick(List<String> worlds) {
        List<String> shuffled = new ArrayList<>(worlds);
        Collections.shuffle(shuffled);
        return tryWorlds(shuffled, 0);
    }

    private CompletableFuture<Location> tryWorlds(List<String> worlds, int index) {
        if (index >= worlds.size())
            return CompletableFuture.completedFuture(null);
        World world = plugin.getServer().getWorld(worlds.get(index));
        Config.WorldPicker picker = world != null ? plugin.config().locationPickers.get(world.getName()) : null;
        if (world == null || picker == null)
            return tryWorlds(worlds, index + 1);
        return attempt(world, picker, picker.maxAttempts())
            .thenCompose(loc -> loc != null ? CompletableFuture.completedFuture(loc) : tryWorlds(worlds, index + 1));
    }

    private CompletableFuture<Location> attempt(World world, Config.WorldPicker picker, int attemptsLeft) {
        if (attemptsLeft <= 0)
            return CompletableFuture.completedFuture(null);
        double angle = RANDOM.nextDouble() * Math.PI * 2;
        double dist = picker.minRadius() + RANDOM.nextDouble() * (picker.maxRadius() - picker.minRadius());
        int x = picker.centerX() + (int) (Math.cos(angle) * dist);
        int z = picker.centerZ() + (int) (Math.sin(angle) * dist);

        return world.getChunkAtAsync(x >> 4, z >> 4)
            .thenApplyAsync(chunk -> scan(world, x, z))
            .thenCompose(loc -> {
                if (loc != null && isAllowed(world, loc))
                    return CompletableFuture.completedFuture(loc);
                return attempt(world, picker, attemptsLeft - 1);
            });
    }

    private Location scan(World world, int x, int z) {
        return switch (world.getEnvironment()) {
            case NETHER -> scanDownward(world, x, z, Math.min(plugin.config().netherMaxY, plugin.config().scanStartY));
            case THE_END -> scanDownward(world, x, z, plugin.config().scanStartY);
            default -> scanOverworld(world, x, z);
        };
    }

    private Location scanOverworld(World world, int x, int z) {
        if (plugin.config().biomeBlacklist.contains(world.getBiome(x, world.getMinHeight() + 1, z)))
            return null;
        int y = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        if (!isSafeColumn(world, x, y, z))
            return null;
        return new Location(world, x + 0.5, y + 1, z + 0.5);
    }

    /** Nether/End have no reliable "highest block" - scan down from the ceiling for solid ground. */
    private Location scanDownward(World world, int x, int z, int startY) {
        int minY = world.getMinHeight();
        for (int y = Math.min(startY, world.getMaxHeight() - 1); y > minY + 2; y--) {
            if (isSafeColumn(world, x, y - 1, z))
                return new Location(world, x + 0.5, y, z + 0.5);
        }
        return null; // void column (common in the End) - caller just tries another spot
    }

    private boolean isSafeColumn(World world, int x, int groundY, int z) {
        Material ground = world.getBlockAt(x, groundY, z).getType();
        if (!ground.isSolid() || UNSAFE.contains(ground))
            return false;
        Material feet = world.getBlockAt(x, groundY + 1, z).getType();
        Material head = world.getBlockAt(x, groundY + 2, z).getType();
        if (!feet.isAir() || !head.isAir() || UNSAFE.contains(feet) || UNSAFE.contains(head))
            return false;
        return world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING) <= groundY + 2 || world.getEnvironment() != World.Environment.NORMAL;
    }

    private boolean isAllowed(World world, Location loc) {
        if (!world.getWorldBorder().isInside(loc))
            return false;
        Set<String> blacklist = plugin.config().blacklistedRegions;
        if (blacklist.isEmpty() || !plugin.getServer().getPluginManager().isPluginEnabled("WorldGuard"))
            return true;
        return !WorldGuardSupport.isBlacklisted(loc, blacklist);
    }
}
