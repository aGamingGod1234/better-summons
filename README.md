# Better /summon

A small, focused Fabric server-side mod for Minecraft **1.21.11** (Java 21) that extends the vanilla `/summon` command with **repeat-count** and **random-count** variants so server operators and map-makers can spawn large groups of entities with a single command, without resorting to chained command blocks or external scripts.

The mod is deliberately minimal: it delegates all entity-spawning logic to Minecraft's own `SummonCommand.summon(...)`, so vanilla compatibility, NBT validation, and mob-cap behaviour stay identical. It only adds a convenient wrapper around how many times that vanilla call is made.

## What it adds

Six new syntax overloads on top of vanilla `/summon`:

| Syntax | What it does |
| --- | --- |
| `/summon <entity> <count>` | Summon `<entity>` `<count>` times at the command source's position. |
| `/summon <entity> random <min> <max>` | Summon `<entity>` a random number of times in `[min, max]` at the command source's position. |
| `/summon <entity> <pos> <count>` | Summon `<entity>` `<count>` times at `<pos>`. |
| `/summon <entity> <pos> random <min> <max>` | Summon `<entity>` a random number of times in `[min, max]` at `<pos>`. |
| `/summon <entity> <pos> <nbt> <count>` | Summon `<entity>` `<count>` times at `<pos>` using `<nbt>` as the base tag. |
| `/summon <entity> <pos> <nbt> random <min> <max>` | Summon `<entity>` a random number of times in `[min, max]` at `<pos>` using `<nbt>`. |

All other `/summon` forms fall through to vanilla behaviour unchanged.

## How it works

- **Delegation, not re-implementation.** Every spawn goes through vanilla `SummonCommand.summon(source, entityType, pos, nbt, initialize)`, so NBT validation, permissions, and side-effects (raids, boss bars, etc.) are identical to vanilla.
- **Batched spawning to protect TPS.** When the requested count is greater than `ENTITIES_PER_TICK` (100), the summon is split across multiple server ticks. A `SpawnTask` is queued and drained in `END_SERVER_TICK` at 100 entities per tick. The console receives a `Spawning <N> entities in batches of 100...` preview up front and a final `Executed /summon <entityId> <n>/<total>` report when the task completes (or stops partway, if vanilla rejects one of the calls).
- **Task queue is bounded.** A single command can spawn up to 10,000 entities; the queue is capped at 20 active tasks so a looping command block or rapid RCON burst can't balloon the backlog. Attempting to queue past the cap returns a command error instead of silently consuming memory.
- **Server-lifecycle aware.** Pending batches are dropped when the server stops, and the queue is reset on server start to discard stale `ServerCommandSource` references from prior integrated-server sessions. Each batch also re-verifies that the originating server is still running before processing the next tick's work.
- **Minimal allocation on the hot path.** When no `<nbt>` argument is supplied, the mod reuses a shared empty `NbtCompound` rather than deep-cloning an empty map per-entity every tick; this eliminates 100 throwaway allocations per tick when bulk-spawning.

## Guardrails

- **Permission-gated.** The entire command tree is gated behind `GAMEMASTERS_CHECK` (permission level 2), matching vanilla `/summon`.
- **Integer bounds on every count.** `count`, `min`, and `max` are all Brigadier `IntegerArgumentType.integer(1, 10_000)`, so a malformed argument can't request unbounded work.
- **Invalid range is a command error.** `random <min> <max>` with `min > max` returns a structured error, not silent fallback.
- **Queue-full is a command error.** Operators get explicit feedback when they hit the 20-task cap, rather than a silent drop.
- **Randomness.** Random counts use `ThreadLocalRandom.nextInt(min, max + 1)` (uniform, non-crypto). The upper-bound overflow case (`max == Integer.MAX_VALUE`) is rejected by the argument parser; the internal check is retained as defence-in-depth.

## Commands reference

The permission/permission-level is the same for every variant: op level 2 (`GAMEMASTERS`). Everything is a strict superset of vanilla `/summon`; the two-argument and three-argument vanilla forms (`/summon <entity>`, `/summon <entity> <pos>`, `/summon <entity> <pos> <nbt>`) continue to work exactly as before.

## Build

```powershell
./gradlew build
```

The remapped, production-ready jar is written to:

```
build/libs/better-summon-<version>.jar
```

There is also a `-sources.jar` next to it if you want the source attached in your IDE.

## Install

1. Build the jar (or grab a release jar).
2. Drop it into your Fabric `mods` folder (typically `%APPDATA%\.minecraft\mods` on Windows).
3. Launch Fabric for Minecraft `1.21.11`.

The mod runs server-side only; connecting clients do not need it installed.

## License

CC0-1.0.
