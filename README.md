# Better /summon (Fabric 1.21.11)

Better /summon extends vanilla `/summon` with repeat-count variants for faster entity spawning workflows.

## Features

- `/summon <entity> <count>`
- `/summon <entity> random <min> <max>`
- `/summon <entity> <pos> <count>`
- `/summon <entity> <pos> random <min> <max>`
- `/summon <entity> <pos> <nbt> <count>`
- `/summon <entity> <pos> <nbt> random <min> <max>`

## Behavior

- The mod keeps vanilla summon validation by calling Minecraft's internal `SummonCommand.summon(...)`.
- `random` uses `ThreadLocalRandom` (fast, non-cryptographic — appropriate for game logic).
- Spawns above 100 entities are batched across server ticks (100/tick) to protect TPS.
- Long-running batched spawns emit periodic progress updates.
- Tasks can be inspected with `/summonstatus` and aborted with `/summoncancel`.
- If a repeated summon fails partway through, execution stops and reports partial completion with the failure reason.

## Guardrails

- Allowed repeat range: `1..10000`.
- Invalid random ranges (`min > max`) return a command error.
- Batched spawns are aborted automatically if the command source becomes invalid (e.g., player disconnect, world unload).
- Requires operator-level permissions consistent with vanilla summon usage.

## Build

```powershell
./gradlew build
```

Output jar:

- `build/libs/better-summon-<version>.jar`

## Install

1. Build the mod.
2. Copy the jar to your mods folder, usually:
   `C:\Users\<YourUser>\AppData\Roaming\.minecraft\mods`
3. Start Fabric `1.21.11`.

## License

CC0-1.0
