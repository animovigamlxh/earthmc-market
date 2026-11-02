# v0.5.51 — favhead identity fallback

- `/emprice favhead` no longer refuses a visible named head simply because profile UUID/skin data are missing.
- Existing skin/owner fingerprint is preferred when present; otherwise favorites use `location:<dimension>:<x>:<y>:<z>` as a stable local fallback.
- The GUI star button, Favorites-only filter, historical wishlist rows, and town/nation fallback now share the same location-based favorite identity.
- Different positions with the same display name can be favorited independently. Repeating `/emprice favhead` on the same framed head toggles it off.
- The quick command saves any known location metadata from the head catalogue.

## Notes
- A location-only favorite identifies a physical display at a coordinate, not a globally unique skin. If a head is moved to another frame, it must be favorited again.
- Item frames occupying exactly the same block position are not distinguishable by this fallback.
- Keep existing `config/earthmc-market/head-favorites.json`; do not delete previous favorites.
- Source-only release; GitHub Actions build validation is still needed.
