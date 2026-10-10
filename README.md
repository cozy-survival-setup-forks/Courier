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

`config.yml` holds everything, including the texts under `messages:` (MiniMessage). Texts missing from an older file use the built-in ones. Player data (cooldowns, totals and the active delivery) is in `courier.db`. Older `data/<uuid>.yml` files are brought in once at the first start, in one step and checked (players, completed and failed totals, cooldowns and active deliveries must match), and then renamed to `<uuid>.yml.migrated`. A file that cannot be read is left where it is. A finished delivery is saved and written to a reward record before the reward is paid, so a stop in the middle never pays twice: a reward that may have been missed is listed in `/courier doctor` and the console, and `/courier doctor resolve <id>` clears it after you have checked.

## Keeping your files safe

- `config.yml` start with a `config-version` / `lang-version` number. After an update, new settings are added to your files with their comments, and nothing you changed is touched. The old file is kept next to it as `<name>.<date>.bak` (the newest 5). A setting is only removed when the changelog says so.
- A value with a mistake (a negative time, an item that does not exist, text where a number belongs) is named in the console by file and key. On a reload, the settings in use stay as they were.
- Files are written to a temporary file and moved into place, with the previous version kept as `.bak`. A file that cannot be read is restored from its `.bak`, and the unreadable one is kept as `.broken-<time>`.
- A file or database that was made by a newer version of the plugin is left alone and a warning is logged.
- `courier.db` is a SQLite database in WAL mode. It is checked when the plugin starts, a copy is made on a schedule (`backup.interval-hours`, `backup.keep` in `config.yml`, in the `backups` folder) and every copy is opened and checked before older ones are removed. A damaged database is replaced by the newest copy that checks out, or, where nothing may be lost, the plugin stays off and the file is left untouched.
- The backup is a consistent snapshot, not a copy of the open file.
- `/courier doctor` shows the health of the files, versions, last backup and recent save failures (no player data). `/courier backup now` makes a checked backup right away. Both need the admin permission.

## Telemetry

On startup Courier sends a small anonymous beacon (plugin name/version, server software/version, online/max player counts, and a random ID with no player data) so we know which versions are in use. Turn it off with `metrics.enabled: false` in `config.yml`. The random ID is kept as `server-id` in `courier.db` (older versions kept it in a `.server-id` file, which is moved over unchanged). The address and the interval are fixed in the plugin and are not settings.

## Building

```
./gradlew build
```

The jar is in `build/libs/`.
