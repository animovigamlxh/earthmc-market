# v0.5.47 — placed-head profile parsing fix

- Skip static methods when reading `SkullBlockEntity` instance profile.
- Read actual GameProfile from 26.x ResolvableProfile via `partialProfile`/`getGameProfile`, with constrained field fallback.
- Read `textures` properties to create SHA-256 skin fingerprints (never store the full texture payload).
- Avoid treating generic HeadDatabase name or UUID as a unique head skin identity.
- Add safe profile-field type diagnostics to `/emprice headdebug`.
- Preserve history, existing manual names, and favorite data. Revisit /emprice headscan after installation to refresh fingerprints.

Build compatibility and live server behavior must be checked using GitHub Actions / EarthMC.
