# Better /summon

`Better /summon` is a small Fabric mod for Minecraft `1.21.11` that lets you spawn the same entity multiple times with a single command.

It keeps the normal `/summon` command intact and adds extra forms for:

- fixed repeat counts
- random repeat counts
- custom positions
- custom NBT data

This is useful for testing farms, building encounters, setting up maps, or quickly spawning mobs during admin work.

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

## What It Adds

Vanilla `/summon` still works the same.

This mod adds the following extra command forms:

```text
/summon <entity> <count>
/summon <entity> random <min> <max>
/summon <entity> <pos> <count>
/summon <entity> <pos> random <min> <max>
/summon <entity> <pos> <nbt> <count>
/summon <entity> <pos> <nbt> random <min> <max>
```

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
- If one summon fails during a batch, the mod stops and tells you how many were completed.
- Position and NBT support work alongside the repeat-count features.

## Good To Know

- Works well for admins, map makers, and testing setups.
- Useful in single-player with cheats enabled or on Fabric servers with operator permissions.
- It does not change normal mob behavior by itself. It only makes repeated summoning faster.

## License

`CC0-1.0`
