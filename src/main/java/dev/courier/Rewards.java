package dev.courier;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.Random;
import java.util.function.Supplier;
import java.util.logging.Logger;

/** Picks one weighted reward tier and applies it, plus whatever always-rewards has configured. */
final class Rewards {

    private static final Random RANDOM = new Random();
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final Supplier<Economy> economy;
    private final Logger log;
    private boolean warnedNoEconomy;

    /** @param economy looked up at the moment of a reward, because the economy plugin can start after Courier */
    Rewards(Supplier<Economy> economy, Logger log) {
        this.economy = economy;
        this.log = log;
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
            if (roll < running)
                return tier;
        }
        return tiers.get(tiers.size() - 1);
    }

    /** One broken part of a reward (a command that throws) does not stop the rest of it. */
    void apply(Player player, Config.RewardEntry reward) {
        if (reward == null)
            return;
        if (reward.money() > 0) {
            Economy eco = economy.get();
            if (eco != null) {
                try {
                    eco.depositPlayer(player, reward.money());
                } catch (RuntimeException e) {
                    log.warning("Could not pay " + player.getName() + ": " + e);
                }
            } else if (!warnedNoEconomy) {
                warnedNoEconomy = true;
                log.warning("A reward pays money but there is no economy (Vault with an economy plugin), so no money is paid.");
            }
        }
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
        for (String command : reward.commands()) {
            try {
                player.getServer().dispatchCommand(player.getServer().getConsoleSender(), command.replace("{player}", player.getName()));
            } catch (RuntimeException e) {
                log.warning("A reward command failed (" + command + "): " + e.getMessage());
            }
        }
        if (reward.message() != null && !reward.message().isBlank())
            player.sendMessage(MM.deserialize(reward.message()));
    }
}
