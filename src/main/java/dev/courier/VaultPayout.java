package dev.courier;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

/** Only ever loaded after Vault has been found to be enabled. */
final class VaultPayout implements Payout {

    private final Economy economy;

    private VaultPayout(Economy economy) {
        this.economy = economy;
    }

    /** The payout of the economy plugin that is registered with Vault right now, or null when there is none. */
    static Payout find(Server server) {
        RegisteredServiceProvider<Economy> provider = server.getServicesManager().getRegistration(Economy.class);
        return provider == null ? null : new VaultPayout(provider.getProvider());
    }

    @Override
    public boolean pay(Player player, double amount) {
        return economy.depositPlayer(player, amount).transactionSuccess();
    }
}
