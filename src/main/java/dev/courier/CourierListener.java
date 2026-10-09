package dev.courier;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BundleMeta;

import java.util.UUID;

/** Every item-protection rule keyed off ItemTag, plus join/quit/death/place/interact mission events. */
final class CourierListener implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final CourierPlugin plugin;
    private final MissionManager manager;

    CourierListener(CourierPlugin plugin, MissionManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    private Config cfg() {
        return plugin.config();
    }

    private UUID tagOf(ItemStack item) {
        return manager.tag().missionId(item);
    }

    // ---- lifecycle ----

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        manager.restore(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        manager.onQuit(event.getPlayer());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Mission mission = manager.activeMission(event.getPlayer().getUniqueId());
        if (mission != null)
            event.getDrops().removeIf(item -> mission.id.equals(tagOf(item)));
        manager.onDeath(event.getPlayer());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> manager.resyncIfActive(player), 2L);
    }

    // ---- completion ----

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        UUID missionId = tagOf(event.getItemInHand());
        if (missionId == null)
            return;
        Player player = event.getPlayer();
        Mission mission = manager.activeMission(player.getUniqueId());
        boolean valid = mission != null && mission.id.equals(missionId) && cfg().completeMode == Config.CompleteMode.PLACE
            && manager.isWithinDropoff(player, mission);
        if (!valid) {
            if (cfg().blockPlaceOutsideDropoff)
                event.setCancelled(true);
            return;
        }
        var block = event.getBlockPlaced();
        var replaced = event.getBlockReplacedState();
        var placedType = block.getType();
        manager.complete(player);
        int delay = cfg().removeAfterSeconds;
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (block.getType() == placedType)
                replaced.update(true, false); // what stood there before comes back
        }, Math.max(1L, delay * 20L));
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || cfg().completeMode != Config.CompleteMode.INTERACT)
            return;
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK && event.getAction() != Action.RIGHT_CLICK_AIR)
            return;
        UUID missionId = tagOf(event.getItem());
        if (missionId == null)
            return;
        Player player = event.getPlayer();
        Mission mission = manager.activeMission(player.getUniqueId());
        if (mission == null || !mission.id.equals(missionId) || !manager.isWithinDropoff(player, mission))
            return;
        event.setCancelled(true);
        manager.complete(player);
    }

    // ---- protection ----

    /** Blocks that accept any item, which would let a player hide the package inside them. */
    private static final java.util.Set<org.bukkit.Material> STORES_ANY_ITEM = java.util.Set.of(
        org.bukkit.Material.DECORATED_POT, org.bukkit.Material.JUKEBOX, org.bukkit.Material.LECTERN,
        org.bukkit.Material.CHISELED_BOOKSHELF, org.bukkit.Material.COMPOSTER);

    @EventHandler(priority = org.bukkit.event.EventPriority.HIGH, ignoreCancelled = true)
    public void onStoreAttempt(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null
            || !STORES_ANY_ITEM.contains(event.getClickedBlock().getType()))
            return;
        if (tagOf(event.getItem()) != null)
            event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (cfg().blockDrop && tagOf(event.getItemDrop().getItemStack()) != null)
            event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDespawn(ItemDespawnEvent event) {
        if (tagOf(event.getEntity().getItemStack()) != null)
            event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        UUID missionId = tagOf(event.getItem().getItemStack());
        if (missionId == null)
            return;
        Mission mission = event.getEntity() instanceof Player p ? manager.activeMission(p.getUniqueId()) : null;
        if (mission == null || !mission.id.equals(missionId))
            event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        ItemStack current = event.getCurrentItem();
        ItemStack cursor = event.getCursor();
        boolean touchesTagged = tagOf(current) != null || tagOf(cursor) != null;
        if (!touchesTagged && event.getClick() == org.bukkit.event.inventory.ClickType.NUMBER_KEY
            && event.getWhoClicked() instanceof Player clicker && event.getHotbarButton() >= 0)
            touchesTagged = tagOf(clicker.getInventory().getItem(event.getHotbarButton())) != null;
        if (!touchesTagged && event.getClick() == org.bukkit.event.inventory.ClickType.SWAP_OFFHAND
            && event.getWhoClicked() instanceof Player clicker)
            touchesTagged = tagOf(clicker.getInventory().getItemInOffHand()) != null;
        if (!touchesTagged)
            return;

        if (cfg().blockCrafting && event.getSlotType() == org.bukkit.event.inventory.InventoryType.SlotType.RESULT
            && event.getInventory() instanceof org.bukkit.inventory.CraftingInventory) {
            event.setCancelled(true);
            return;
        }
        if (cfg().blockBundle && event.getClickedInventory() != null) {
            ItemStack clicked = event.getClickedInventory().getItem(event.getSlot());
            if (clicked != null && clicked.getItemMeta() instanceof BundleMeta) {
                event.setCancelled(true);
                return;
            }
        }
        if (!cfg().blockMoveToForeignInventory)
            return;
        boolean topIsForeign = !(event.getView().getTopInventory().getHolder() instanceof Player);
        boolean movesToTop = event.getClick().isShiftClick() || event.getClickedInventory() == event.getView().getTopInventory();
        if (topIsForeign && movesToTop)
            event.setCancelled(true);
    }

    /** Dragging a stack over the slots of a chest is not a click, so it needs its own check. */
    @EventHandler(ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (!cfg().blockMoveToForeignInventory || tagOf(event.getOldCursor()) == null)
            return;
        if (event.getView().getTopInventory().getHolder() instanceof Player)
            return;
        int top = event.getView().getTopInventory().getSize();
        for (int slot : event.getRawSlots())
            if (slot < top) {
                event.setCancelled(true);
                return;
            }
    }

    @EventHandler(ignoreCancelled = true)
    public void onHopperPickup(InventoryPickupItemEvent event) {
        if (cfg().blockHopper && tagOf(event.getItem().getItemStack()) != null)
            event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        if (cfg().blockItemFrame && tagOf(event.getPlayerItem()) != null)
            event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onHopperMove(InventoryMoveItemEvent event) {
        if (cfg().blockHopper && tagOf(event.getItem()) != null)
            event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        if (!cfg().blockCrafting)
            return;
        for (ItemStack item : event.getInventory().getMatrix())
            if (tagOf(item) != null) {
                event.setCancelled(true);
                return;
            }
    }

    @EventHandler(ignoreCancelled = true)
    public void onAnvil(PrepareAnvilEvent event) {
        if (!cfg().blockAnvil)
            return;
        for (ItemStack item : event.getInventory().getContents())
            if (tagOf(item) != null) {
                event.setResult(null);
                return;
            }
    }

    @EventHandler(ignoreCancelled = true)
    public void onItemFrame(PlayerInteractEntityEvent event) {
        if (!cfg().blockItemFrame)
            return;
        if (!(event.getRightClicked() instanceof org.bukkit.entity.ItemFrame
            || event.getRightClicked() instanceof org.bukkit.entity.ArmorStand
            || event.getRightClicked() instanceof org.bukkit.entity.Allay))
            return;
        Player player = event.getPlayer();
        if (tagOf(player.getInventory().getItemInMainHand()) != null || tagOf(player.getInventory().getItemInOffHand()) != null)
            event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFrameDamage(EntityDamageByEntityEvent event) {
        if (cfg().blockItemFrame && event.getEntity() instanceof org.bukkit.entity.ItemFrame
            && event.getDamager() instanceof Player player && manager.isHoldingTagged(player))
            event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (cfg().blockedCommandsWhileHolding.isEmpty())
            return;
        Player player = event.getPlayer();
        if (!manager.isHoldingTagged(player))
            return;
        String typed = event.getMessage().substring(1).strip().toLowerCase();
        int space = typed.indexOf(' ');
        int colon = typed.indexOf(':');
        if (colon > 0 && (space < 0 || colon < space))
            typed = typed.substring(colon + 1); // plugin:command is the same command
        for (String blocked : cfg().blockedCommandsWhileHolding) {
            if (typed.equals(blocked) || typed.startsWith(blocked + " ")) {
                event.setCancelled(true);
                player.sendMessage(MM.deserialize(cfg().message("command-blocked", "You can't use that while carrying a delivery.")));
                return;
            }
        }
    }
}
