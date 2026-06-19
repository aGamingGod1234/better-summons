# Better /summon

`Better /summon` is a small Fabric server-side mod for Minecraft `1.21.11` that lets operators and map makers spawn the same entity multiple times with a single command.

It keeps the normal `/summon` command intact and adds extra forms for:

- fixed repeat counts
- random repeat counts
- custom positions
- custom NBT data

This is useful for testing farms, building encounters, setting up maps, or quickly spawning mobs during admin work. The mod delegates each spawn to Minecraft's own `SummonCommand.summon(...)`, so vanilla validation, NBT handling, permissions, and side effects stay intact.

## What You Need

- Minecraft `1.21.11`
- [Fabric Loader](https://fabricmc.net/use/)
- [Fabric API](https://modrinth.com/mod/fabric-api)
- Java `21` or newer

> This mod is meant for worlds or servers where you already have permission to use `/summon`.

## Installation

1. Get the mod jar file named `better-summon-<version>.jar`.
2. Put it in your Minecraft `mods` folder.
3. Make sure `Fabric API` is also installed in the same `mods` folder.
4. Start Minecraft with the Fabric profile.

Common Windows mods folder:

```text
C:\Users\<YourUser>\AppData\Roaming\.minecraft\mods
```

The mod runs server-side only; connecting clients do not need it installed.

## What It Adds

Vanilla `/summon` still works the same. This mod adds the following extra command forms:

| Syntax | What it does |
| --- | --- |
| `/summon <entity> <count>` | Summon `<entity>` `<count>` times at the command source's position. |
| `/summon <entity> random <min> <max>` | Summon `<entity>` a random number of times in `[min, max]` at the command source's position. |
| `/summon <entity> <pos> <count>` | Summon `<entity>` `<count>` times at `<pos>`. |
| `/summon <entity> <pos> random <min> <max>` | Summon `<entity>` a random number of times in `[min, max]` at `<pos>`. |
| `/summon <entity> <pos> <nbt> <count>` | Summon `<entity>` `<count>` times at `<pos>` using `<nbt>` as the base tag. |
| `/summon <entity> <pos> <nbt> random <min> <max>` | Summon `<entity>` a random number of times in `[min, max]` at `<pos>` using `<nbt>`. |

The permission level is the same for every variant: op level 2 (`GAMEMASTERS`). The two-argument and three-argument vanilla forms (`/summon <entity>`, `/summon <entity> <pos>`, `/summon <entity> <pos> <nbt>`) continue to work exactly as before.

## Examples

| What you want to do | Command |
| --- | --- |
| Spawn 10 zombies | `/summon minecraft:zombie 10` |
| Spawn a random number of zombies from 5 to 20 | `/summon minecraft:zombie random 5 20` |
| Spawn 8 creepers at a specific location | `/summon minecraft:creeper 100 64 100 8` |
| Spawn 12 skeletons at your current position | `/summon minecraft:skeleton ~ ~ ~ 12` |
| Spawn 5 slimes with NBT data | `/summon minecraft:slime ~ ~ ~ {Size:4} 5` |
| Spawn a random number of no-AI villagers from 2 to 6 | `/summon minecraft:villager ~ ~ ~ {NoAI:1b} random 2 6` |

## How It Behaves

- You must have permission to use `/summon`, just like vanilla.
- Repeat counts can be between `1` and `10,000`.
- Random ranges must be valid, so `min` cannot be greater than `max`.
- Random counts use `ThreadLocalRandom.nextInt(min, max + 1)`, which is uniform and non-cryptographic.
- If one summon fails during a batch, the mod stops and tells you how many were completed.
- Position and NBT support work alongside the repeat-count features.

## How It Works

- **Delegation, not re-implementation.** Every spawn goes through vanilla `SummonCommand.summon(source, entityType, pos, nbt, initialize)`.
- **Batched spawning to protect TPS.** Requests above `ENTITIES_PER_TICK` (100) are split across server ticks through `END_SERVER_TICK`. Operators get an up-front batching message and a final completion report.
- **Task queue is bounded.** A single command can spawn up to 10,000 entities, and the active queue is capped at 20 tasks. Queue-full attempts return a command error instead of growing memory usage.
- **Server-lifecycle aware.** Pending batches are dropped when the server stops, and the queue is reset on server start to discard stale `ServerCommandSource` references from prior integrated-server sessions.
- **Minimal allocation on the hot path.** Empty NBT input reuses a shared empty `NbtCompound`; non-empty NBT is copied before each summon.

## Build

```powershell
./gradlew build
```

The remapped, production-ready jar is written to:

```text
build/libs/better-summon-<version>.jar
```

There is also a `-sources.jar` next to it if you want the source attached in your IDE.

## Good To Know

- Works well for admins, map makers, and testing setups.
- Useful in single-player with cheats enabled or on Fabric servers with operator permissions.
- It does not change normal mob behavior by itself. It only makes repeated summoning faster.

## License

`CC0-1.0`
