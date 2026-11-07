# v0.5.53 — Sell Mode matching and visibility fix

- Match Chest Tracker ItemStack IDs **and localized display names** to BUY sign names; e.g. minecraft:iron_ingot vs 铁锭.
- Added `All stock` / `Sellable only` toggle. All Stock shows historical inventories even without matching active BUY offers, with an explanation, and still supports `Boxes`.
- Do not silently filter items out when the buyer has insufficient purchase capacity, complete lots cannot be formed, or the item has special components. These appear in All Stock but are not counted as confirmed sellable.
- No extra world or chest scanning; read Chest Tracker only when opening or pressing Refresh.
- Notes: Server/shop quotes and Chest Tracker content are historical snapshots. A matching buy quote alone does not guarantee an actual transaction.
