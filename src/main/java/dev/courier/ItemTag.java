package dev.courier;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.UUID;

/** Tags a delivery item with the mission's UUID via PDC, so it can't be faked or duplicated. */
final class ItemTag {

    private final NamespacedKey key;

    ItemTag(Plugin plugin) {
        this.key = new NamespacedKey(plugin, "mission-id");
    }

    ItemStack tag(ItemStack item, UUID missionId) {
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, missionId.toString());
        item.setItemMeta(meta);
        return item;
    }

    UUID missionId(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta())
            return null;
        String raw = item.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
        if (raw == null)
            return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    boolean isTagged(ItemStack item) {
        return missionId(item) != null;
    }
}
