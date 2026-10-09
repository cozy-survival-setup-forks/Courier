package dev.courier;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Owns every active mission and the one shared repeating task that drives them all. */
final class MissionManager {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final CourierPlugin plugin;
    private final Storage storage;
    private final ItemTag tag;
    private final LocationPicker picker;
    private final Rewards rewards;
    private final ResourceWorldSupport resourceWorlds;
    private final Map<UUID, Mission> active = new ConcurrentHashMap<>();
    /** Players whose delivery is being set up (the location is searched asynchronously): they cannot start a second one meanwhile. */
    private final Set<UUID> starting = ConcurrentHashMap.newKeySet();
    /** When a mission whose world is gone may look for a new place again, so a failing search is not repeated every tick. */
    private final Map<UUID, Long> rerollAfter = new ConcurrentHashMap<>();
    private long tickCounter;

    MissionManager(CourierPlugin plugin, Storage storage, Supplier<Payout> economy) {
        this.plugin = plugin;
        this.storage = storage;
        this.tag = new ItemTag(plugin);
        this.picker = new LocationPicker(plugin);
        this.rewards = new Rewards(economy, plugin.getLogger());
        this.resourceWorlds = new ResourceWorldSupport(plugin, this);
    }

    Mission activeMission(UUID player) {
        return active.get(player);
    }

    PlayerData data(UUID player) {
        return storage.get(player);
    }

    ItemTag tag() {
        return tag;
    }

    /** Called on join. Restores a mission that survived a restart, or fails it if it expired while offline. */
    void restore(Player player) {
        PlayerData data = storage.get(player.getUniqueId());
        if (data.missionId == null)
            return;
        UUID missionId;
        try {
            missionId = UUID.fromString(data.missionId);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("The saved delivery of " + player.getName() + " is damaged and was dropped.");
            clearMission(data);
            storage.markDirty(player.getUniqueId());
            return;
        }
        Mission mission = new Mission(player.getUniqueId(), missionId, data.missionWorld == null ? "" : data.missionWorld,
            data.missionX, data.missionY, data.missionZ, data.missionStartedAt, data.missionExpiresAt, data.missionResourceWorld);
        if (mission.expired()) {
            active.put(player.getUniqueId(), mission);
            fail(player, "expired", plugin.config().cooldownOnFail);
            return;
        }
        active.put(player.getUniqueId(), mission);
        resync(player, mission);
    }

    /** After a death the package is gone from the inventory while the delivery goes on: give it back. */
    void resyncIfActive(Player player) {
        Mission mission = active.get(player.getUniqueId());
        if (mission != null && player.isOnline())
            resync(player, mission);
    }

    /** Puts exactly one tagged item back if it's missing, and removes any extra copies. */
    private void resync(Player player, Mission mission) {
        List<ItemStack> tagged = findTagged(player, mission.id);
        if (tagged.isEmpty()) {
            player.getInventory().addItem(taggedItem(mission.id));
        } else {
            for (int i = 1; i < tagged.size(); i++)
                tagged.get(i).setAmount(0);
        }
    }

    void start(Player target, boolean announce) {
        if (active.containsKey(target.getUniqueId()) || starting.contains(target.getUniqueId())) {
            target.sendMessage(msg("already-active"));
            return;
        }
        PlayerData data = storage.get(target.getUniqueId());
        long cooldownLeft = data.cooldownUntil - System.currentTimeMillis();
        if (cooldownLeft > 0) {
            target.sendMessage(msg("on-cooldown").replaceText(b -> b.matchLiteral("%cooldown%").replacement(TimeUtil.format(cooldownLeft))));
            return;
        }
        PlayerInventory inv = target.getInventory();
        if (inv.firstEmpty() == -1) {
            target.sendMessage(msg("inventory-full"));
            return;
        }

        List<String> pool = new ArrayList<>(plugin.config().dropoffWorlds);
        pool.addAll(plugin.config().resourceWorlds);
        starting.add(target.getUniqueId());
        picker.pick(pool).whenComplete((location, error) ->
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                starting.remove(target.getUniqueId());
                if (error != null) {
                    plugin.getLogger().warning("Looking for a drop-off for " + target.getName() + " failed: " + error);
                    if (target.isOnline())
                        target.sendMessage(msg("no-worlds-available"));
                    return;
                }
                onLocationFound(target, location);
            }));
    }

    private void onLocationFound(Player target, Location location) {
        if (!target.isOnline() || active.containsKey(target.getUniqueId()))
            return;
        if (target.getInventory().firstEmpty() == -1) {
            target.sendMessage(msg("inventory-full")); // it filled up while the place was being searched
            return;
        }
        if (location == null) {
            target.sendMessage(msg("no-worlds-available"));
            plugin.getLogger().warning(plugin.config().message("no-worlds-available-console", "").replace("%player%", target.getName()));
            return;
        }
        UUID missionId = UUID.randomUUID();
        long now = System.currentTimeMillis();
        boolean resourceWorld = plugin.config().resourceWorlds.contains(location.getWorld().getName());
        Mission mission = new Mission(target.getUniqueId(), missionId, location.getWorld().getName(),
            location.getX(), location.getY(), location.getZ(), now, now + plugin.config().missionDurationMs, resourceWorld);
        active.put(target.getUniqueId(), mission);

        PlayerData data = storage.get(target.getUniqueId());
        data.missionId = missionId.toString();
        data.missionWorld = mission.world;
        data.missionX = mission.x;
        data.missionY = mission.y;
        data.missionZ = mission.z;
        data.missionStartedAt = mission.startedAt;
        data.missionExpiresAt = mission.expiresAt;
        data.missionResourceWorld = resourceWorld;
        storage.markDirty(target.getUniqueId());

        target.getInventory().addItem(taggedItem(missionId));
        target.sendMessage(msg("started").replaceText(b -> b.matchLiteral("%time%").replacement(TimeUtil.format(plugin.config().missionDurationMs))));
    }

    void reroll(Mission mission) {
        Player player = plugin.getServer().getPlayer(mission.player);
        if (player == null)
            return;
        long now = System.currentTimeMillis();
        if (rerollAfter.getOrDefault(mission.player, 0L) > now)
            return;
        rerollAfter.put(mission.player, now + 30_000L);
        List<String> pool = new ArrayList<>(plugin.config().dropoffWorlds);
        pool.addAll(plugin.config().resourceWorlds);
        picker.pick(pool).thenAccept(location -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (location == null || !active.containsKey(mission.player))
                return;
            rerollAfter.remove(mission.player);
            mission.world = location.getWorld().getName();
            mission.x = location.getX();
            mission.y = location.getY();
            mission.z = location.getZ();
            PlayerData data = storage.get(mission.player);
            data.missionWorld = mission.world;
            data.missionX = mission.x;
            data.missionY = mission.y;
            data.missionZ = mission.z;
            storage.markDirty(mission.player);
            if (player.isOnline())
                player.sendMessage(msg("world-moved"));
        }));
    }

    void complete(Player player) {
        Mission mission = active.remove(player.getUniqueId());
        if (mission == null)
            return;
        removeTagged(player, mission.id);

        PlayerData data = storage.get(player.getUniqueId());
        data.completedTotal++;
        data.cooldownUntil = System.currentTimeMillis() + plugin.config().cooldownMs;
        clearMission(data);
        storage.markDirty(player.getUniqueId());

        Config.RewardEntry always = plugin.config().alwaysReward;
        Config.RewardEntry picked = rewards.pick(plugin.config().rewards);
        try {
            if (always != null)
                rewards.apply(player, always);
            rewards.apply(player, picked);
        } catch (RuntimeException e) {
            plugin.getLogger().warning("The reward for " + player.getName() + " failed part way: " + e);
        }

        player.sendMessage(msg("completed"));
        player.showTitle(Title.title(msg("completed"), net.kyori.adventure.text.Component.empty()));
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
    }

    void fail(Player player, String reasonKey, boolean applyCooldown) {
        Mission mission = active.remove(player.getUniqueId());
        if (mission == null)
            return;
        removeTagged(player, mission.id);

        PlayerData data = storage.get(player.getUniqueId());
        data.failedTotal++;
        if (applyCooldown)
            data.cooldownUntil = System.currentTimeMillis() + plugin.config().cooldownMs;
        clearMission(data);
        storage.markDirty(player.getUniqueId());

        player.sendMessage(msg(reasonKey));
    }

    void cancel(Player admin, Player target) {
        if (!active.containsKey(target.getUniqueId())) {
            admin.sendMessage(msg("no-mission").replaceText(b -> b.matchLiteral("%player%").replacement(target.getName())));
            return;
        }
        fail(target, "cancelled", plugin.config().cooldownOnFail);
        admin.sendMessage(msg("cancelled").replaceText(b -> b.matchLiteral("%player%").replacement(target.getName())));
    }

    void reset(UUID target) {
        PlayerData data = storage.get(target);
        data.cooldownUntil = 0;
        storage.markDirty(target);
    }

    private void clearMission(PlayerData data) {
        data.missionId = null;
        data.missionWorld = null;
    }

    void onQuit(Player player) {
        Mission mission = active.get(player.getUniqueId());
        if (mission != null && plugin.config().failOnQuit)
            fail(player, "failed-quit", plugin.config().cooldownOnFail);
        else
            active.remove(player.getUniqueId()); // authority goes back to storage until they rejoin
        starting.remove(player.getUniqueId());
        rerollAfter.remove(player.getUniqueId());
        storage.unload(player.getUniqueId());
    }

    void onDeath(Player player) {
        if (active.containsKey(player.getUniqueId()) && plugin.config().failOnDeath)
            fail(player, "failed-death", plugin.config().cooldownOnFail);
    }

    /** The one shared repeating task - iterates only players with an active mission. */
    void tick() {
        tickCounter += 10;
        boolean actionbarDue = tickCounter % 20 == 0;
        boolean particlesDue = plugin.config().particlesEnabled && tickCounter % plugin.config().particleIntervalTicks == 0;

        for (Mission mission : List.copyOf(active.values())) {
            Player player = plugin.getServer().getPlayer(mission.player);
            if (player == null)
                continue;
            if (mission.expired()) {
                fail(player, "expired", plugin.config().cooldownOnFail);
                continue;
            }
            if (plugin.getServer().getWorld(mission.world) == null) {
                reroll(mission); // the world is gone (regenerated or unloaded)
                continue;
            }
            if (actionbarDue)
                sendActionbar(player, mission);
            if (particlesDue)
                spawnParticles(player, mission);
        }
    }

    private void sendActionbar(Player player, Mission mission) {
        boolean sameWorld = player.getWorld().getName().equals(mission.world);
        String format = sameWorld ? plugin.config().actionbarFormat : plugin.config().actionbarFormatDifferentWorld;
        if (format.isBlank())
            return;
        String distance = sameWorld ? String.format("%.0f", player.getLocation().distance(missionLocation(mission))) : "-";
        var text = MM.deserialize(format
            .replace("%time_left%", TimeUtil.format(mission.expiresAt - System.currentTimeMillis()))
            .replace("%distance%", distance)
            .replace("%world%", mission.world));
        player.sendActionBar(text);
    }

    private void spawnParticles(Player player, Mission mission) {
        if (!player.getWorld().getName().equals(mission.world))
            return;
        Location center = missionLocation(mission);
        if (player.getLocation().distance(center) > plugin.config().particleViewDistance)
            return;
        Config cfg = plugin.config();
        for (double y = 0; y <= cfg.particleHeight; y += 0.5)
            player.spawnParticle(cfg.particleType, center.getX(), center.getY() + y, center.getZ(), 1, 0, 0, 0, 0);
        for (int i = 0; i < cfg.particlePointsPerRing; i++) {
            double angle = 2 * Math.PI * i / cfg.particlePointsPerRing;
            double px = center.getX() + Math.cos(angle) * cfg.particleRadius;
            double pz = center.getZ() + Math.sin(angle) * cfg.particleRadius;
            player.spawnParticle(cfg.particleType, px, center.getY() + 0.1, pz, 1, 0, 0, 0, 0);
        }
    }

    List<Mission> targeting(String world) {
        List<Mission> result = new ArrayList<>();
        for (Mission mission : active.values())
            if (mission.resourceWorld && mission.world.equals(world))
                result.add(mission);
        return result;
    }

    Location missionLocation(Mission mission) {
        var world = plugin.getServer().getWorld(mission.world);
        return world == null ? null : new Location(world, mission.x, mission.y, mission.z);
    }

    boolean isWithinDropoff(Player player, Mission mission) {
        Location loc = missionLocation(mission);
        return loc != null && player.getWorld().equals(loc.getWorld()) && player.getLocation().distance(loc) <= plugin.config().dropoffRadius;
    }

    private ItemStack taggedItem(UUID missionId) {
        Config cfg = plugin.config();
        ItemStack item = new ItemStack(cfg.itemMaterial);
        var meta = item.getItemMeta();
        meta.displayName(MM.deserialize(cfg.itemName));
        if (!cfg.itemLore.isEmpty())
            meta.lore(cfg.itemLore.stream().map(MM::deserialize).toList());
        item.setItemMeta(meta);
        return tag.tag(item, missionId);
    }

    private List<ItemStack> findTagged(Player player, UUID missionId) {
        List<ItemStack> found = new ArrayList<>();
        for (ItemStack item : player.getInventory().getContents())
            if (item != null && missionId.equals(tag.missionId(item)))
                found.add(item);
        if (missionId.equals(tag.missionId(player.getInventory().getItemInOffHand())))
            found.add(player.getInventory().getItemInOffHand());
        return found;
    }

    /** Strips every copy of this mission's item from inventory (incl. offhand), ender chest and cursor. */
    void removeTagged(Player player, UUID missionId) {
        strip(player.getInventory().getContents(), missionId);
        strip(player.getEnderChest().getContents(), missionId);
        ItemStack offhand = player.getInventory().getItemInOffHand();
        if (missionId.equals(tag.missionId(offhand)))
            player.getInventory().setItemInOffHand(null);
        ItemStack cursor = player.getItemOnCursor();
        if (missionId.equals(tag.missionId(cursor)))
            player.setItemOnCursor(null);
    }

    /** True if the player is holding a tagged item anywhere reachable in one action (inv, offhand, cursor). */
    boolean isHoldingTagged(Player player) {
        for (ItemStack item : player.getInventory().getContents())
            if (tag.isTagged(item))
                return true;
        return tag.isTagged(player.getInventory().getItemInOffHand()) || tag.isTagged(player.getItemOnCursor());
    }

    private void strip(ItemStack[] contents, UUID missionId) {
        for (ItemStack item : contents)
            if (item != null && missionId.equals(tag.missionId(item)))
                item.setAmount(0);
    }

    net.kyori.adventure.text.Component msg(String key) {
        return MM.deserialize(plugin.config().message(key, key));
    }
}
