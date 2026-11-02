# EarthMC Market v0.5.46 — HeadDebug and Ask Heads fixes

- `/emprice headdebug`: aim at a placed player head block or item frame to inspect the client-visible block/entity type, profile accessors, whether the scan extracted a skin fingerprint, and location lookup state. Skin texture values and full UUIDs are not printed.
- Fixed a missing location lookup for heads found only through the incremental placed-block scan. These heads now request town/nation based on their own coordinates.
- Ask Heads T/N/Go buttons refresh their availability every second after asynchronous location lookup returns without recomputing the head-price join.
- GUI drawing truncates names/location/price status before the row action buttons, reducing text overlap.
- Cached GUI rows now reference saved heads by stable dimension and coordinates, avoiding ambiguous position/name matching.

## Test steps
1. Aim at a wall head and run `/emprice headdebug`.
2. Run `/emprice headscan` and wait for the `Block scan complete` message.
3. Open `/emprice headsask`; check whether T/N/Go activates when town/nation data is resolved.
4. Repeat `headdebug` on a wall head that remains `Player Head (placed)`; check whether profile is available and whether skin fingerprint is present.

This is source only; not yet built or tested against a live 26.2 client.
