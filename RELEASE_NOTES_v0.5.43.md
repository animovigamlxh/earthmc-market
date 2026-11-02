# EarthMC Market v0.5.43 — placed heads diagnostic scan

- `/emprice headscan` starts a **bounded 33x33x21 block-state scan**, processed at no more than 450 positions per game tick, with counts on completion. Also scans item frames via the existing scanner.
- Supports `PLAYER_HEAD` and `PLAYER_WALL_HEAD` even when no `SkullBlockEntity` appears in the chunk map.
- Maintains unknown-profile skulls as `Player Head (placed)` and saves coordinates. Only retrieves expensive skull data when a matching block is found.
- Placed heads default to Ask Heads, unless manually marked Sale. Preserves the entire historical catalogue and favorites.
- Prevents pruning valid placed-head records solely because no block entity was enumerated.
- Only scans a local radius ±16 X/Z and ±10 Y, and only already-loaded chunks. It does not scan distant parts of the world.

**Not fully Gradle-compiled or live-server validated.**
