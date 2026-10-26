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

## v0.3.1 GUI fix
`/emprice gui` queues the screen opening for a later client tick, after Minecraft closes its chat input. If the GUI does not appear, check for the `Opening Market GUI...` message and ensure exactly one EarthMC Market JAR is installed.

## v0.4.0 — Read-only local sign scanning

- `/emprice scan` searches a 32-block horizontal radius and ±12 blocks vertically around the player, only in already-loaded chunks. The search is spread across ticks rather than freezing the client.
- `/emprice autoscan on` repeats the search every 30 seconds. `/emprice autoscan off` disables it (it does not cancel an already-started pass).
- Signs must match four lines: owner, `出售`/`收购` and optional numeric suffix, item name, and `每64x 1G` or similar.
- Scan source is `earthmc-sign`; availability is `sign-unverified` or `sign-red-unverified`, **not** verified stock. These offers are deliberately excluded from guaranteed-positive-spread calculations until checked by left click.
- Deduplication uses block X/Y/Z plus text side. Existing QuickShop click-based scanning and GUI still work.
- Sign location uses exact sign block coordinates. Location lookups are throttled to one every 1.5 seconds; lots of sign records can take some time to enrich. `/emprice locate` can retry unresolved records.
- This scanner does **not** click chests/signs, issue transactions, request remote chunks, or read server-side unseen data. A plugin creating virtual sign text rather than synced sign block entities may not be discovered by this approach.
- `autoscan` setting is intentionally session-only in v0.4.0. Switch it on after joining the server.

## v0.4.2 — Batched location lookups and persistent cache

The sign scanner now queues location lookups, deduplicates them by Minecraft
16×16 chunk, and sends up to 40 different locations in each `POST /v4/location`.
Only one request is in flight at a time. Requests are spaced by at least
2 seconds. HTTP 429 leaves all queued locations intact and waits for the
server `Retry-After` response (or uses exponential backoff).

Successful results are kept for 6 hours and written to
`config/earthmc-market/location-cache.json`; rejected/failed results are not
persisted. An old failed quote can be retried with `/emprice locate` (up to
10 old records per command), but fresh sign scans are queued automatically.
`/emprice locdebug` shows queued chunk count, requests and backoff seconds.

The town/nation identifies the **sign's in-world claim**, not the sign owner's
resident town. Location results and sign prices remain time-limited snapshots.
Do not assume availability or permission to teleport.

**Important:** The previous v0.4.0 README line stating one location API request
per 1.5 seconds is obsolete as of v0.4.2; the new batch worker manages request
pacing. The first scan may still require time to enrich all entries.


## v0.4.3 GUI pricing & stock
The GUI now shows the original offer `1G / 64x` as well as gold per item, and a stock/status line. SELL is current inventory and BUY is remaining purchase space **only when returned by a QuickShop inspection**. Sign-only quotes have no confirmed quantity and show `stock ? (sign)`. Quotes are snapshots and may go stale.


## v0.4.4: GUI destination shortcut

The Market GUI now shows two buttons on each quote row: **T** (town) and **N** (nation).
Clicking one pre-fills `/t spawn <town>` or `/n spawn <nation>` in the chat input and closes the Market GUI.
Press **Enter** to send the command; no travel command is sent automatically.
Buttons are disabled when the API has not resolved the location. Only use confirmed, accurate shop locations for travel; player-approx locations can indicate a neighbouring claim.
It does not guarantee that the town/nation has a public spawn.

## v0.4.5: scanner deduplication fix

Sign identity now uses `dimension + exact XYZ + front/back`. A change in item or
price updates the same physical sign, but different sign faces or dimensions no
longer overwrite one another. An incoming manual or QuickShop record will not
replace a sign record solely because item/owner/coordinates match.

Existing records that were previously overwritten cannot be recovered from
the saved database; rescan affected towns. Pre-v0.4.5 saved records have no
dimension field, so they may remain as legacy entries; create a backup before
rebuilding the database.

## v0.4.6 sign unit-price fix
QuickShop signs with `单价：27G` or `单价: 27G` now parse as 1 item for 27 gold, alongside prior `每64x 1G` signs. Includes regression test cases for ancient debris and netherite equipment.

## v0.4.7
Scan unavailable QuickShop signs labeled 缺货 or 空间不足, mark stock 0, and preserve historical price without treating the shop as tradable.


## v0.4.8 GUI & arbitrage

`/emprice gui`: Stock: ALL/OK/?/X filters. `Arbitrage` button opens `/emprice arbitrage`.
The arbitrage page matches normalized item names between SELL (purchase) and BUY
(resale) shops. It reports positive unit-price spreads and marks unverified
stock. Confirmed opportunities require known stock > 0 on both sides;
maximum units is the smaller known stock count. Amounts may represent
shop trades or available quantities depending on data source; always verify.
Results are estimates and exclude taxes, fees and travel costs.
Travel buttons only prefill chat commands, never auto-send.

## v0.4.9 — Enchantment notes for inspected QuickShops

After left-clicking a QuickShop, wait for `Auto scanned`, then run
`/emprice enchant 保护 IV, 耐久 III, 经验修补` to attach the
enchantment details to **the last successfully inspected shop**.
`/emprice enchant clear` clears the note for the last shop. The note is
saved in `prices.json`, shown alongside the item in the market GUI, and
used as part of the arbitrage matching key. Enchanted variants do not
match items lacking the exact same note.

The shop's chat text alone may not expose all enchantment NBT/components.
This version does not claim to read them automatically; inspect the item
preview and type its details. Re-inspecting the same shop preserves the note
when its owner, item, trade type and observed coordinates match.
Old notes are preserved in saved JSON. Do not keep two mod JAR versions.

## v0.5.0 automatic enchantment capture

Left-click a QuickShop sign. When the server sends its `Enchantments` section
following the shop price, the client collects enchantment lines (e.g.
`保护 IV`, `耐久 III`) for up to 8 seconds and writes them to `prices.json`
under that inspected shop. In GUI the lines appear next to the item, and
arbitrage comparisons use the enchantment details. The manual
`/emprice enchant ...` command remains available as a fallback.

If the server does not send an Enchantments section, no enchantment details
are inferred. Only lines containing an enchantment name and level are
automatically captured. GUI may truncate long summaries.

## v0.5.1 Stock refresh fix
- Manual QuickShop inspection refreshes the matching scanned sign's stock in-place when the match is unambiguous.
- The next sign scan cannot overwrite a confirmed stock count with an unknown value while shop identity and price remain unchanged.
- If sign transitions from out of stock to available, old zero stock becomes unknown until the next left-click.
- An unchanged sign does not reveal an updated inventory count. Reinspect the shop to get fresh stock.
- Legacy duplicate entries may require manual cleanup or rescanning.

## v0.5.3 Capital-aware teleport drafts

The market's **Go** button and arbitrage **Go Buy/Go Sell** inspect the shop's **physical town**, not the owner's residence. They look up `status.isCapital` using `POST /v4/towns`. Capital shops prepare `/n spawn <nation>`; non-capitals prepare `/t spawn <town>`. The player still presses Enter. Town capital flags are cached for 2 hours. API failures are reported and never guessed; teleport permissions/public status are not guaranteed.


## v0.5.4 — crowded-market performance fix

- Replaced 105,625 per-block lookups per scan with iteration over **loaded chunks' block entities only**.
- Bounded scanning to **one chunk and 48 signs per client tick**. Signs outside the original 32×32 horizontal and ±12 vertical range remain excluded.
- API location completions now mark the database dirty instead of rewriting `prices.json` after every response. Autoscan writes are batched on a ~5-second debounce.
- Reduced location-lookup queue draining from 80 to 20 entries/tick.
- Retained `/emprice scan`, `/emprice autoscan on/off`, GUI, arbitrage, and older prices.json.

If crashes persist, turn off autoscan, temporarily reduce Minecraft view distance, and send the newest `crash-reports/*.txt` and `logs/latest.log`.


## v0.5.5 Shop marker fix
- H now displays a brighter 3-D particle outline on all twelve block edges for six seconds.
- Legacy sign records without dimension are accepted only while nearby and loaded; mismatch dimensions still reject.
- Ambiguous/missing chests use the saved exact shop sign instead of silently failing.
- Requires client particle rendering to be enabled; particles are not visible through walls.


## v0.5.7
Replaced the 26.2-incompatible ShopEspRenderer with the 26.2 LevelExtractionEvents/LevelRenderEvents and StagedVertexBuffer rendering pipeline. ESP remains an experimental feature pending Fabric build and in-game test.

## v0.5.8 – Auto-highlight after travel

Selecting Go (or Go Buy / Go Sell) drafts the teleport command without sending it. After the player actually sends the same command, the client waits five seconds, then checks every second whether the target's precise sign location is loaded and within 48 blocks. When it is, the ordinary H locator / ESP begins a six-second highlight. It cancels after one minute if never nearby; it does not blindly label a remote shop. Teleporting to a town spawn may still leave the shop over 48 blocks away, in which case walk closer within the one-minute window or use H manually. No teleport is sent by the mod.

### v0.5.9 Go tracking
When a prepared Go command is actually sent with Enter, the client watches for
movement/teleport away from the departure position. It then checks every 5 client
ticks for the chosen shop block within 48 blocks and loaded. It highlights
immediately upon entering range (no fixed five-second delay), or expires after
three minutes. A different command or an unsent draft cancels tracking.


## v0.5.10 — crash fix

Fix recursive client chat handling: ignore locally emitted `[EM Market]` messages, guard `say()` against reentrancy, and clear enchant capture state before emitting its completion notice. No price records are deleted.

### v0.5.15: EarthMC-only mode and quiet scanning

- The mod's `/emprice` commands, automatic sign scanning, incoming chat parsing,
  and travel highlight tracking are active only when connected using the
  `earthmc.net` host or a subdomain such as `play.earthmc.net`. On other servers
  and in singleplayer, the mod does not scan or parse market messages.
- Automatic scan completion chat is **off** by default. Toggle using
  `/emprice scanmsg on` and `/emprice scanmsg off` (persists in
  `config/earthmc-market/settings.json`). Scanning and database updates still run.
- Explicit `/emprice scan` always shows a one-off start/completion message.
- Auto-scan itself is controlled separately using `/emprice autoscan on|off`.


### v0.5.16 UI persistence and deletion
- The market GUI remembers search text, SELL/BUY filter, price order, stock filter, and page across visits and game restarts. Arbitrage screen remembers its own search, unverified filter, and page. Saved in `config/earthmc-market/gui-state.json`.
- Press **Del** next to an observation and confirm to delete only that record from `prices.json`.
- Deleted physical sign locations are suppressed from automatic rescan and stored in `config/earthmc-market/deleted-signs.json`. `/emprice restoredeleted` clears this suppression list (the next scan can rediscover those shops).
- Non-sign historical records are removed directly; a newly observed real trade may create a new observation later. Back up `prices.json` before mass deletions.

### v0.5.17 Position hints

GUI Go / Go Buy / Go Sell and H now print the saved sign coordinates in local chat even when the shop chunk is not loaded. If the client has loaded the shop and finds a unique nearby chest/barrel, its coordinates are printed as **estimated**, alongside the sign position. Unknown or legacy coordinates are explicitly labeled as unverified; no remote chest coordinate is invented.
