# v0.5.41 — Head Not For Sale / Ask

- Added `/emprice headsask` for a second head catalogue screen.
- Added `Ask Heads` / `Shop Heads` navigation in the existing Heads screen.
- Heads without linked payment sign candidates appear under Ask Heads by default; this means **unverified / ask owner**, not a confirmed declaration of non-sale.
- Each row has an `Ask` or `Sale` action to override the inferred classification. User overrides are stored in `config/earthmc-market/head-sale-status.json` independently of wishlists and observed heads, keyed by dimension, frame position and item fingerprint.
- Retains searching, favorites, browsing, manual refresh, frame highlighting and existing travel controls. Travel requires a linked price sign with an identified town/nation; H works without a price.
- Other nearby signs can still make a decorative head appear in Shops as an unverified candidate. Move it to Ask manually.
- No new background scans; category filtering uses the existing head cache.
- Existing `/emprice heads` is preserved.

Not verified via Gradle or on EarthMC; test and share the GitHub Actions log if build errors occur.
