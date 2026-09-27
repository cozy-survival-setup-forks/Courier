package dev.courier;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import org.bukkit.Location;

import java.util.Set;

/** Only ever touched from behind an isPluginEnabled("WorldGuard") check - see LocationPicker. */
final class WorldGuardSupport {

    private WorldGuardSupport() {
    }

    static boolean isBlacklisted(Location location, Set<String> blacklistedRegions) {
        var regions = WorldGuard.getInstance().getPlatform().getRegionContainer()
            .createQuery().getApplicableRegions(BukkitAdapter.adapt(location));
        for (ProtectedRegion region : regions) {
            if (blacklistedRegions.contains(region.getId()))
                return true;
        }
        return false;
    }
}
