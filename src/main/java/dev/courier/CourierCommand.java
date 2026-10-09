package dev.courier;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

final class CourierCommand implements CommandExecutor, TabCompleter {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final List<String> SUBS = List.of("start", "cancel", "reset", "info", "reload");

    private final CourierPlugin plugin;
    private final MissionManager manager;

    CourierCommand(CourierPlugin plugin, MissionManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(MM.deserialize("<gray>/courier start|cancel|reset|info|reload [player]"));
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "start" -> start(sender, args);
            case "cancel" -> cancel(sender, args);
            case "reset" -> reset(sender, args);
            case "info" -> info(sender, args);
            case "reload" -> reload(sender);
            default -> sender.sendMessage(MM.deserialize("<red>Unknown subcommand."));
        }
        return true;
    }

    private void start(CommandSender sender, String[] args) {
        if (args.length >= 2) {
            if (!sender.hasPermission("courier.admin")) {
                sender.sendMessage(manager.msg("no-permission"));
                return;
            }
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(manager.msg("player-not-found"));
                return;
            }
            manager.start(target, false);
            return;
        }
        if (!(sender instanceof Player self)) {
            sender.sendMessage(MM.deserialize("<red>Console must specify a player: /courier start <player>"));
            return;
        }
        if (!self.hasPermission("courier.use") || !plugin.config().allowSelfStart) {
            self.sendMessage(manager.msg("no-permission"));
            return;
        }
        manager.start(self, true);
    }

    private void cancel(CommandSender sender, String[] args) {
        if (!sender.hasPermission("courier.admin")) {
            sender.sendMessage(manager.msg("no-permission"));
            return;
        }
        Player target = args.length >= 2 ? Bukkit.getPlayerExact(args[1]) : null;
        if (target == null) {
            sender.sendMessage(manager.msg("player-not-found"));
            return;
        }
        if (!(sender instanceof Player admin)) {
            manager.fail(target, "cancelled", plugin.config().cooldownOnFail);
            sender.sendMessage(MM.deserialize("<yellow>Delivery for " + target.getName() + " cancelled."));
            return;
        }
        manager.cancel(admin, target);
    }

    private void reset(CommandSender sender, String[] args) {
        if (!sender.hasPermission("courier.admin")) {
            sender.sendMessage(manager.msg("no-permission"));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(manager.msg("player-not-found"));
            return;
        }
        OfflinePlayer target = Bukkit.getPlayerExact(args[1]);
        if (target == null)
            target = Bukkit.getOfflinePlayerIfCached(args[1]); // never asks Mojang from the main thread
        if (target == null) {
            sender.sendMessage(manager.msg("player-not-found"));
            return;
        }
        manager.reset(target.getUniqueId());
        sender.sendMessage(manager.msg("reset").replaceText(b -> b.matchLiteral("%player%").replacement(args[1])));
    }

    private void info(CommandSender sender, String[] args) {
        Player target;
        if (args.length >= 2) {
            if (!sender.hasPermission("courier.admin")) {
                sender.sendMessage(manager.msg("no-permission"));
                return;
            }
            target = Bukkit.getPlayerExact(args[1]);
        } else if (sender instanceof Player self) {
            target = self;
        } else {
            sender.sendMessage(MM.deserialize("<red>Console must specify a player: /courier info <player>"));
            return;
        }
        if (target == null) {
            sender.sendMessage(manager.msg("player-not-found"));
            return;
        }
        Mission mission = manager.activeMission(target.getUniqueId());
        PlayerData data = manager.data(target.getUniqueId());
        long now = System.currentTimeMillis();
        if (mission != null) {
            sender.sendMessage(manager.msg("info-active")
                .replaceText(b -> b.matchLiteral("%player%").replacement(target.getName()))
                .replaceText(b -> b.matchLiteral("%time_left%").replacement(TimeUtil.format(mission.expiresAt - now)))
                .replaceText(b -> b.matchLiteral("%world%").replacement(mission.world)));
        } else if (data.cooldownUntil > now) {
            sender.sendMessage(manager.msg("info-cooldown")
                .replaceText(b -> b.matchLiteral("%player%").replacement(target.getName()))
                .replaceText(b -> b.matchLiteral("%cooldown_left%").replacement(TimeUtil.format(data.cooldownUntil - now))));
        } else {
            sender.sendMessage(manager.msg("info-idle").replaceText(b -> b.matchLiteral("%player%").replacement(target.getName())));
        }
    }

    private void reload(CommandSender sender) {
        if (!sender.hasPermission("courier.admin")) {
            sender.sendMessage(manager.msg("no-permission"));
            return;
        }
        sender.sendMessage(manager.msg(plugin.reloadCourierConfig() ? "reloaded" : "reload-failed"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1)
            return SUBS.stream().filter(s -> s.startsWith(args[0].toLowerCase())).collect(Collectors.toList());
        if (args.length == 2 && !args[0].equalsIgnoreCase("reload"))
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase())).collect(Collectors.toList());
        return new ArrayList<>();
    }
}
