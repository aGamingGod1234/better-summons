# Better /summon (Fabric 1.21.11)

Extends vanilla `/summon` with repeat counts and secure-random repeat counts.

## Features

- `/summon <entity> <count>`
- `/summon <entity> random <min> <max>`
- `/summon <entity> <pos> <count>`
- `/summon <entity> <pos> random <min> <max>`
- `/summon <entity> <pos> <nbt> <count>`
- `/summon <entity> <pos> <nbt> random <min> <max>`

`random` uses `SecureRandom` (CSPRNG), not a predictable PRNG seed.

## Guardrails

- Repeat range is bounded to `1..10000` to protect server stability.
- Invalid ranges (`min > max`) return a clear command error.

## Build

```powershell
./gradlew build
```

Final remapped jar output:

- `build/libs/better-summon-<version>.jar`

## License

CC0-1.0
