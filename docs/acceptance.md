# Compatibility and live acceptance

[Documentation index](README.md) · [Load-test runbook](load-testing.md)

This development preview includes implementation and regression tests, not production certification. Test matching OrionBot and OrionBridge builds in a disposable environment.

## Live acceptance checklist

- Upgrade from custom config v5/v9/v12 with preserved token, menus and data. Verify migration to v14, backup, restart and matched restore.
- Link Java online, offline Java after AuthMe and Floodgate Bedrock. Test disconnect before claim, offline Discord claim, reconnect before/after expiry, Java and Bedrock client crashes, Minecraft/bot restart, explicit regeneration, wrong account, replay and session replacement. Confirm AuthMe login alone cannot unlock movement.
- Test optional lobby with valid and unavailable spawn, reconnect, return to safe loaded location, cancelled teleport, missing world and no inventory changes. Leave disabled unless accepted.
- Exercise strict command allowlist, protected cuboids, biome cell boundaries, concurrent FAWE edits, staff task world/operation/local region/total quota, restart after a partial quota reservation, approval replacement, revocation, sanction and expiry. Test missing/disabled hook and supported direct entity commands. Do not claim coverage for arbitrary direct writes.
- Verify independent lockdown/containment expiry and recovery after restart, corrupt/unwritable state and console recovery.
- Test each moderation action, platform hierarchy, offline/overlap/revision/revoke/expiry, uncertain delivery, appeal screenshot, independent review, five information requests, replies and sanction unchanged after a decision.
- Test rank reconciliation for manual and inherited groups, role permission changes, canonical Java, local Floodgate linked/unlinked/remapped and provider timeout/global-only; observed AuthMe/noncanonical 24h age and contradictory mapping. Unknown results do not revoke ranks.
- Verify chat/mentions/loop prevention, private channel permissions, identity correlation false positives, actor/target attribution, evidence export and CoreProtect preview/rollback on disposable data.
- Run [matched load benchmarking](load-testing.md) and record baseline/feature-on measurements.

## Boundaries and outstanding validation

No Bukkit plugin can guarantee interception of arbitrary privileged plugin or console code, NMS direct writes, or every FAWE implementation path. The WorldEdit extent supports block, biome and entity creation; direct entity removal commands are denied, not generally intercepted. External account remapping without authoritative provider data remains unknown. AuthMe name-based identity reuse needs staff recovery. The optional lobby refuses unsafe or unloaded return locations rather than changing inventory or guessing a safe destination. Not all UI strings are configurable. Actual server benchmarks, full Java 25 Paper and Node integration test results and live plugin/Discord acceptance must be verified before a stable release.

Guard protection is a policy layer, not a replacement for permissions, backups and tested recovery. Never use production worlds for destructive tests.
