# EarthMC Market v0.5.45 — Fabric 26.2 compile fix

- Fixed the v0.5.44 `HeadFrameScanner.java` compilation error by removing the unsupported `SkullBlockEntity.getComponents()` call.
- Preserved the fallback profile accessors/field inspection, skin fingerprint handling, and GUI manual naming.
- Java compilation and in-game behavior have **not** been verified in this environment. Build in GitHub Actions.
