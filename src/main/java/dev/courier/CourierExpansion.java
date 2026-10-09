package dev.courier;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Every value here is an O(1) read off MissionManager's live in-memory maps - never DB/IO -
 * so it's safe for DeluxeMenus to poll on every menu refresh. Numeric placeholders return -1
 * (or 0 for *_seconds) instead of blank/"N/A" when there's nothing active, since DeluxeMenus
 * requirement math breaks on non-numeric values.
 */
final class CourierExpansion extends PlaceholderExpansion {

    private final CourierPlugin plugin;
    private final MissionManager manager;

    CourierExpansion(CourierPlugin plugin, MissionManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "courier";
    }

    @Override
    public @NotNull String getAuthor() {
        return "Groovified";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onPlaceholderRequest(Player player, @NotNull String params) {
        if (player == null || !player.isOnline())
            return "";
        Mission mission = manager.activeMission(player.getUniqueId());
        PlayerData data = manager.data(player.getUniqueId());
        long now = System.currentTimeMillis();

        return switch (params) {
            case "active" -> String.valueOf(mission != null);
            case "can_start" -> String.valueOf(canStart(player, mission, data, now));
            case "refusal_reason" -> refusalReason(player, mission, data, now);
            case "world" -> mission != null ? mission.world : "";
            case "x" -> mission != null ? String.valueOf((int) mission.x) : "-1";
            case "y" -> mission != null ? String.valueOf((int) mission.y) : "-1";
            case "z" -> mission != null ? String.valueOf((int) mission.z) : "-1";
            case "distance" -> distance(player, mission);
            case "time_left" -> mission != null ? TimeUtil.format(mission.expiresAt - now) : "-1";
            case "time_left_seconds" -> mission != null ? String.valueOf(Math.max(0, (mission.expiresAt - now) / 1000)) : "-1";
            case "cooldown" -> data.cooldownUntil > now ? TimeUtil.format(data.cooldownUntil - now) : "";
            case "cooldown_seconds" -> String.valueOf(Math.max(0, (data.cooldownUntil - now) / 1000));
            case "completed_total" -> String.valueOf(data.completedTotal);
            case "failed_total" -> String.valueOf(data.failedTotal);
            default -> null;
        };
    }

    private boolean canStart(Player player, Mission mission, PlayerData data, long now) {
        if (mission != null || data.cooldownUntil > now)
            return false;
        if (!plugin.config().allowSelfStart || !player.hasPermission("courier.use"))
            return false;
        return player.getInventory().firstEmpty() != -1;
    }

    private String refusalReason(Player player, Mission mission, PlayerData data, long now) {
        if (mission != null)
            return plugin.config().message("already-active", "You already have an active delivery.");
        if (data.cooldownUntil > now)
            return plugin.config().message("on-cooldown", "You're on cooldown.").replace("%cooldown%", TimeUtil.format(data.cooldownUntil - now));
        if (!plugin.config().allowSelfStart || !player.hasPermission("courier.use"))
            return plugin.config().message("no-permission", "You don't have permission to do that.");
        if (player.getInventory().firstEmpty() == -1)
            return plugin.config().message("inventory-full", "Your inventory is full.");
        return "";
    }

    private String distance(Player player, Mission mission) {
        if (mission == null)
            return "-1";
        if (!player.getWorld().getName().equals(mission.world))
            return "-1";
        return String.valueOf((int) player.getLocation().distance(manager.missionLocation(mission)));
    }

    @Override
    public boolean canRegister() {
        return true;
    }
}
