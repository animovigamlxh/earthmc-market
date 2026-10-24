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
