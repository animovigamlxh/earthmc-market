# EarthMC Market Scanner v0.2.0 — Fabric 26.2

Experimental client-only price comparison mod. Does not require server installation.

## Commands

* `/emprice add <item> <buy|sell> <gold> <amount> <shop>` — record one observed quote at your current location. Here `sell` means shop sells to you; `buy` means shop buys from you.
* `/emprice find <item>` — rank available quotes and show the best hypothetical arbitrage spread.
* `/emprice import` — import a community export from `config/earthmc-market/import.json`.
* `/emprice recent` — show the last eight observations, including automatic captures.
* `/emprice reload` — reload the local JSON database without restarting Minecraft.
* `/emprice clear` — remove all stored quotes (back up the config file first).

All prices are normalized to gold per item, not gold per stack. Recorded offers are observations, **not** live stock or confirmed executable trades.

## Import format

```json
[
  {"item":"diamond","type":"sell","price":8,"amount":16,"shop":"ExampleStore","x":100,"y":64,"z":200},
  {"item":"diamond","type":"buy","price":12,"amount":16,"shop":"Buyer","x":300,"y":64,"z":400}
]
```

Import price means gold for `amount` items. Optional `source` can identify the provider. This format is an adapter contract, **not** a claim that an external site offers a compatible API. Avoid importing files from untrusted parties.

## Automatic game observation

Left-click an EarthMC QuickShop chest to request its information. The mod attempts to recognize the Chinese shop-info block from system chat, grouping owner, priced item, per-transaction amount, gold price, BUY/SELL direction, inventory/space and full/out-of-stock warnings. A simple English `QuickShop: ...` line is also recognized. Entries are stored with the player's **approximate position at inspection time**, not yet the precise chest block coordinate. No purchases or network scans are performed. Server-specific message variations may not match; add quotes manually when necessary.

The `find` command excludes observed zero-stock or full shops when estimating potential arbitrage spreads. Other observations may have become stale: always confirm stock and price at the shop before trading. Enchanted books and variants are keyed by their displayed shop names (e.g. `风爆 I`), not merely `enchanted_book`.

For debugging, open a shop and type `/emprice recent`. This version has **no GUI** and no automatic third-party site sync; use JSON import for contributed public data.

## Data

Stored locally in `config/earthmc-market/prices.json`. Nothing is uploaded. No EarthMC API keys are required or saved. This release does not scrape third-party sites; data from a publicly provided/authorized export can be imported.

## Build

Minecraft 26.2 requires JDK 25. Run `gradle build` with Gradle 9.5.1 and Fabric Loom 1.17, then install the produced mod JAR and matching Fabric API. Use a Fabric 26.2 profile.

### GitHub Actions (no local Java installation)

1. Create an empty GitHub repository, preferably **private**, and upload the **contents** of this `earthmc-market` folder at repository root. Keep `.github/workflows/build.yml` and `src` intact.
2. Commit to the `main` branch; the workflow runs automatically on changes, or navigate to **Actions → Build EarthMC Market 26.2 → Run workflow**.
3. Open the latest run. A green check means compilation passed. Download artifact `earthmc-market-26.2-jar` under **Artifacts**, unzip it, and install its JAR (not a sources/dev JAR).
4. If the run is red, open **Build Fabric mod** and copy the last relevant compile error to the developer. This first release has not been compile-tested locally yet.

The workflow does not publish a release or access your Minecraft credentials. It runs on GitHub's hosted runner. Do not commit account tokens or EarthMC API keys to a repository.


## v0.2.1 commands / persistence check

- `/emprice stats` shows saved observation totals and the database path.
- `/emprice backup` writes `config/earthmc-market/prices-backup.json` without changing the active database.
- `/emprice recent` confirms that scans survived a full game restart.
- `/emprice reload` rereads the database while in game.
- `/emprice find <item>` reports a spread only if positive. This is still based on potentially stale observations.

**Important:** Auto-scan coordinates are the player's position when the inspection message arrived, not the exact chest block. Walking around a shop may create duplicate records. Avoid treating a displayed profit as guaranteed: inspect stock, permissions, taxes, and prices immediately before a transaction.

## v0.2.2 查询修复

`/emprice find` 现在接收完整名称（包含空格、中文），例如 `/emprice find 风爆 I` 或 `/emprice find 珠光蛙明灯`。如果没有精确匹配，会查询包含输入关键字的报价。模糊查询返回多个物品时，不进行跨物品套利计算。

## v0.2.3: EarthMC town/nation lookup

The mod now queries the official `POST https://api.earthmc.net/v4/location` endpoint
with `[x,z]` for each newly scanned QuickShop. This looks up the **shop's
physical location**, not the shop owner's resident town/nation. It runs
asynchronously, caches responses by 16x16 claim chunk for six hours, and stores
`town`, `nation`, `locationStatus`, and `locationAccuracy` alongside the quote.

`/emprice find <item>` and `/emprice recent` show town and nation beside the
owner and price. `/emprice locate` retries up to 10 older/unresolved scanned
quotes. The `player-approx` tag means a nearby player's coordinates were used
rather than a block target; claim ownership may be wrong near a town border.
`target-block` means the client's current aimed block was close enough to use.
`Town/Nation pending` means the API result is still unknown; it is *not*
permission to conclude the shop is in the wilderness. Quotes are never deleted
because of an API outage. Only overworld Earth coords should be assumed to
map to the EarthMC Towny claims; do not rely on the lookup in other dimensions.

Town/nation fields are snapshots and can change as claims move.

## v0.3.0 in-game GUI (first release)

Use `/emprice gui` to open the searchable price screen. Search matches items, shop owners,
towns and nations. The filter cycles ALL -> SELL -> BUY; Price toggles ascending / descending;
Prev / Next pages through saved records. This is a **read-only** view of the existing local
`config/earthmc-market/prices.json` database. No trades or travel commands are performed.

Caveats: Price data is historical. City/Nation labels are only as reliable as the recorded
coordinates and location API response. This first GUI is deliberately compact and does not yet
include profitable-route ranking, live stock verification or interactive map markers.
