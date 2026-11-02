# v0.5.37 - 30-second autoscan lag fix

- Build a sign identity hash index once at the start of each nearby sign scan; use it for matching previously recorded quotes instead of scanning the entire quote list for every sign.
- Maintain that index for newly discovered signs during the scan.
- Remove the redundant immediate head-frame rescan at sign-scan completion; HeadCatalogue continues its own 5-second cadence and manual `/emprice headscan` remains available.
- No change to user quote records or database schema. Existing `prices.json` and head favorites should be retained.
- If lag remains, next profile the periodic synchronous `prices.json` save and head-directory serialization on the render thread.

Not validated by complete Fabric Gradle build or in-game profiling.
