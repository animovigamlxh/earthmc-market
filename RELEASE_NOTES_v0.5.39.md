# EarthMC Market v0.5.39

- Head Shops GUI: builds a spatial bucket index of scanned sign quotes once when opened or manually refreshed. Search input, favorites and pagination use cached rows only, avoiding the per-keystroke nested scan of all saved heads and all historical quotes.
- Look for possible payment signs within 5 blocks of the frame in each axis, rather than 2 horizontal / 4 vertical blocks. The result is **still only an unverified payment candidate**, since redstone shops may use arbitrary placeholder items or remotely placed signs.
- Head Shops adds separate **T** and **N** buttons beside Go. T prefills `/t spawn <town>`, N prefills `/n spawn <nation>`, and Go preserves API-based capital selection. All arm the *head frame* for the existing post-teleport tracer and near-frame ESP logic after the command is actually sent.
- Old records without a scanned sign or with ambiguous sign matching may still show **Price not linked**. Manual verification of the redstone circuit or a future manual head-to-payment binding feature is needed to assert a trusted price.
- This source has not been built with the Minecraft 26.2 Gradle toolchain or tested in-game. Back up `config/earthmc-market/` before updating.
