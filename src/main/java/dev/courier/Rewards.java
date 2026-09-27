package dev.courier;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.Random;

/** Picks one weighted reward tier and applies it, plus whatever always-rewards has configured. */
final class Rewards {

    private static final Random RANDOM = new Random();
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final Economy economy;

    Rewards(Economy economy) {
        this.economy = economy;
    }

    Config.RewardEntry pick(List<Config.RewardEntry> tiers) {
        if (tiers.isEmpty())
            return null;
        double total = tiers.stream().mapToDouble(Config.RewardEntry::chance).sum();
        if (total <= 0)
            return tiers.get(0);
        double roll = RANDOM.nextDouble() * total;
        double running = 0;
        for (Config.RewardEntry tier : tiers) {
            running += tier.chance();
            if (roll <= running)
                return tier;
        }
        return tiers.get(tiers.size() - 1);
    }

    void apply(Player player, Config.RewardEntry reward) {
        if (reward == null)
            return;
        if (reward.money() > 0 && economy != null)
            economy.depositPlayer(player, reward.money());
        for (Config.ItemDef def : reward.items()) {
            ItemStack item = new ItemStack(def.material(), def.amount());
            if (def.name() != null || (def.lore() != null && !def.lore().isEmpty())) {
                ItemMeta meta = item.getItemMeta();
                if (def.name() != null)
                    meta.displayName(MM.deserialize(def.name()));
                if (def.lore() != null && !def.lore().isEmpty())
                    meta.lore(def.lore().stream().map(MM::deserialize).toList());
                item.setItemMeta(meta);
            }
            player.getInventory().addItem(item).values().forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
        }
        for (String command : reward.commands())
            player.getServer().dispatchCommand(player.getServer().getConsoleSender(), command.replace("{player}", player.getName()));
        if (reward.message() != null && !reward.message().isBlank())
            player.sendMessage(MM.deserialize(reward.message()));
    }
}
