# EarthMC Market 0.5.44 — placed skull identity & manual labels

- Improved `SkullBlockEntity` owner profile retrieval: block components first; then methods and fields as fallbacks.
- Prefer textures (SHA-256) for the identity fingerprint, with UUID/name fallbacks where available. Identity is provisional without skin textures; a generic HeadDatabase owner name cannot uniquely identify a skin.
- Removed the misleading `HeadDatabase's Head` placeholder for placed skulls; show `Player Head (placed)` when no distinct owner is known.
- Added an `R` button to Head Shops and Ask Heads for per-location manual labeling. Labels are saved separately as `manualName` in `head-frames.json` and survive rescans of the same head. Reset clears the local label.
- Preserves existing head history and favorites. Does not attempt server-side rename or external HeadDatabase ID lookup.

Limitations: servers may send only skin textures or omit the original shop name; a fingerprint alone cannot reconstruct a commercial head's title. The 26.2 toolchain and EarthMC runtime were unavailable during packaging; GitHub Actions compilation and live testing are required.
