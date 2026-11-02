# EarthMC Market v0.5.42 — Ask Heads discovery and navigation

- Discover **placed player head blocks** (floor/wall skull block entities) in nearby loaded chunks, plus existing item frames. Uses block entity maps; does not sweep every block coordinate.
- Save town/nation/wilderness location lookup per observed head asynchronously using existing EarthLocationResolver, independent of whether a QuickShop payment sign exists. Lookup may take time and may fail if API is unavailable.
- `T`, `N`, and `Go` on Ask Heads use that location when present, fall back to a candidate payment sign location, and track the head itself on arrival.
- `Nearby: seen now` (default): only same-dimension heads observed within the last minute within 48 blocks. `History: all`: previous saved head listings. No old history files are deleted.
- Missing sale price means **sale status unknown**, not confirmed not-for-sale. Manual Ask/Sale classification remains available.
- Keeps original historical collections, favorites, and price records; **do not delete** `config/earthmc-market/`.

Limitations: A Minecraft player-head block may lack a custom name, unlike the head item before placement. Names then use any readable owner profile or a generic description. Location lookups require EarthMC API; wilderness entries cannot provide town/nation teleports. No local Gradle/Fabric dependencies were installed for a verified compile, so validate in GitHub Actions.
