# Courier

Delivery missions for Paper 1.21.11. A player gets a package, carries it to a random marked spot before time runs out, and is paid when it arrives.

## What it does

- `/courier start` gives the player a package and picks a safe random location in one of the `dropoff` worlds. A column of particles marks the spot and the action bar shows time left and distance.
- **Finishing:** `complete-mode: INTERACT` (default) means right clicking with the package near the spot. `PLACE` means placing it as a block there, which needs a block as `item.material`; if the item cannot be placed Courier switches to INTERACT and says so in the console.
- **Rewards:** one tier is picked by `chance` (a weighted roll), and `always-rewards` is paid on top. A reward can pay money (Vault), give items and run console commands with `{player}`.
- **A cooldown** after each delivery (`cooldown`), after a failed one too unless `cooldown-on-fail: false`. `fail-on-death` and `fail-on-quit` are optional.
- **The package cannot be cheated:** every package belongs to one delivery. It cannot be dropped, put in chests, hoppers, item frames, armor stands, pots or bundles, crafted, renamed in an anvil or picked up by anybody else, and it is given back after a death while the delivery goes on. `blocked-commands-while-holding` stops commands such as `/ah sell` while carrying it.
- Safe locations are searched per world (`location-picker`) with a biome blacklist and optional WorldGuard regions to avoid. Resource worlds that regenerate move the drop-off instead of breaking the delivery.
- Deliveries and cooldowns are saved per player and survive restarts.

## Commands

| Command | What it does | Permission |
| --- | --- | --- |
| `/courier start` | Start your own delivery (if `allow-self-start`) | `courier.use` (everyone) |
| `/courier info` | Your delivery or cooldown | `courier.use` |
| `/courier start\|cancel\|info <player>` | Do it for someone else | `courier.admin` (op) |
| `/courier reset <player>` | Remove a cooldown | `courier.admin` |
| `/courier reload` | Reload config.yml. A mistake in the file cancels the reload and keeps the old settings | `courier.admin` |

## Placeholders (PlaceholderAPI)

`%courier_active%`, `%courier_can_start%`, `%courier_refusal_reason%`, `%courier_world%`, `%courier_x%`, `%courier_y%`, `%courier_z%`, `%courier_distance%`, `%courier_time_left%`, `%courier_time_left_seconds%`, `%courier_cooldown%`, `%courier_cooldown_seconds%`, `%courier_completed_total%`, `%courier_failed_total%`. A number placeholder is `-1` when there is no delivery.

`plugins/Courier/deluxemenus/courier_menu.yml` is an example menu for DeluxeMenus that uses them.

## Setup

1. Put the jar in `plugins/` and start the server once.
2. In `config.yml`, list your worlds in `dropoff.worlds` and give each one an entry in `location-picker.worlds` (center and radius). The console warns about a world without one.
3. Change `rewards`. Money needs Vault and an economy plugin.

## Files

`config.yml` holds everything, including the texts under `messages:` (MiniMessage). Texts missing from an older file use the built-in ones. Player data is in `data/<uuid>.yml`.

## Telemetry

On startup Courier sends a small anonymous beacon (plugin name/version, server software/version, online/max player counts, and a random ID with no player data) so we know which versions are in use. Turn it off with `metrics.enabled: false` in `config.yml`.

## Building

```
./gradlew build
```

The jar is in `build/libs/`.
