# EarthMC Market v0.5.51 — Sell Mode / Chest Tracker

## How to use

Install both EarthMC Market and **Chest Tracker (Unofficial port) 2.8.4+26.2** in your Fabric Minecraft 26.2 client.

- `/emprice sellmode` opens Sell Mode.
- Or open `/emprice gui` and click **Sell Mode**.
- **Refresh** reads the Chest Tracker memory bank again; it does **not** rescan world blocks.
- **Boxes** lists every remembered container holding that item, including containers in abandoned towns and other players' towns. It displays saved location, count, and snapshot date.
- **Go** reuses the normal EarthMC shop-travel flow, preparing `/t spawn` or `/n spawn` for the best matching BUY quote.
- Search and pagination use a static snapshot: no continuous scans or disk writes during typing or paging.

## Scope & limitations

- **No ownership checks**. All Chest Tracker memories in the currently loaded bank count.
- Memories are historical: a remembered box may be inaccessible, emptied, or moved. The GUI does **not** claim live inventory.
- EarthMC buy quotes are also snapshots; a buyer may run out of capacity since the last scan.
- Matches are exact item identifiers. Special component variants (customized items, enchantments, potion contents, etc.) are not auto-matched because doing so can give false sale suggestions.
- Estimates reflect the **single best quoted buyer**, respect its known capacity, and round down to full QuickShop trade lots. Unknown capacity is marked `?` / `~`.
- Multiple buyer shops are not combined in this first version.
- All expensive matching happens when opening or refreshing, not every GUI frame or every 30 seconds.
- The source ZIP includes the **user-supplied Chest Tracker JAR in `libs/` solely as a compile-only dependency**. Chest Tracker must still be installed as a separate mod at runtime; it is not bundled into the finished EarthMC Market JAR.
- Requires GitHub Actions / Gradle build and real-server testing; no complete Minecraft 26.2 environment was available for local compilation.
