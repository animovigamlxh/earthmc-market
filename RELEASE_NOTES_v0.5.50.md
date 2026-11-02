# v0.5.50 — Head location filters and favorites metadata

- Both Head Shops and Head Not For Sale / Ask screens now offer persistent Nation and Town selector buttons. Each click cycles through currently recorded choices and back to All.
- Town choices respect the selected nation. Nation change resets town; text, nation, town and favorites filters combine.
- Optional Group: Nation / Town sorting makes it easier to visit multiple heads at one destination.
- Each row displays Nation / Town next to its coordinates (or Unknown if not yet resolved); details also include both.
- Favoriting a head stores its last recorded dimension, XYZ, nation and town. Existing `head-favorites.json` files remain readable.
- Existing catalogue location resolver is reused. A limited set of historical missing locations is queued at screen open and refresh; records update asynchronously, and favorites' town/nation are refreshed for matching identities.
- No change to Jade-based placed-head name recognition, Go navigation or sale/ask classification.

Caveats: town/nation accuracy depends on EarthMC location API responses and saved head coordinates. A name-based favorite without unique profile identity still represents an item name, not a proven unique head identity. `Unknown` means no verified location is currently available. Compilation must be validated in the repository's Minecraft 26.2 GitHub Actions build environment.
