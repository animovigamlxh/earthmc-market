# v0.5.49 — Jade-style name resolution for placed heads

- Reads placed player-head block entity custom names (including inherited field/accessor paths) before owner fallbacks.
- Attempts read-only clone/pick ItemStack name resolution when supported by Minecraft 26.2 mappings; rejects generic placeholder names.
- Retains color/bold style, manual rename, favorites and history. On rescanning, existing automatic placeholder names can update.
- Placed-head records with a real name still receive correct block existence checks.

Limitations: Jade may receive server-side information unavailable to this mod; if the custom name is not synced and the pick result contains no custom name, this client-only implementation cannot guarantee the same result as Jade. GitHub Actions compilation and server testing are required.
