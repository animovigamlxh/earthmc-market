# EarthMC Market v0.5.52 — Chest Tracker Gradle configuration fix

- Replace unavailable Loom `modCompileOnly files(...)` with Gradle `compileOnly files(...)` for the bundled Chest Tracker API JAR.
- Chest Tracker remains a separate required runtime mod; no JAR classes are bundled into EarthMC Market.
- No changes to Sell Mode, inventory matching, GUI, or existing data.
- This only addresses the `build.gradle:15` configuration failure. Compilation on GitHub Actions is still required to validate the complete source.
