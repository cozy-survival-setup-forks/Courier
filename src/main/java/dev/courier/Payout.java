package dev.courier;

import org.bukkit.entity.Player;

/** Pays money. The only implementation uses Vault, which is why it is kept apart: Courier must start without Vault. */
interface Payout {

    /** True when the money was paid. */
    boolean pay(Player player, double amount);
}
