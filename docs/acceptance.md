# Compatibility and live acceptance

[Documentation index](README.md) · [Load-test runbook](load-testing.md)

This development preview includes implementation and regression tests, not production certification. Test matching OrionBot and OrionBridge builds in a disposable environment.

## Live acceptance checklist

- Upgrade from custom config v5/v9/v12 with preserved token, menus and data. Verify migration to v15, category values, backup, restart and matched restore. Test interrupted migration, conflict rejection, missing v15 category, fresh install, and existing menu theme preservation.
- Link Java online, offline Java after AuthMe and Floodgate Bedrock. Test disconnect before claim, offline Discord claim, reconnect before/after expiry, Java and Bedrock client crashes, Minecraft/bot restart, explicit regeneration, wrong account, replay and session replacement. Confirm AuthMe login alone cannot unlock movement.
- Test optional lobby with valid and unavailable spawn, reconnect, return to safe loaded location, cancelled teleport, missing world and no inventory changes. Leave disabled unless accepted.
- Exercise explicit temporary edit command allowlists, resolved EssentialsX/WorldGuard/CoreProtect/AdvancedBan/LiteBans/LuckPerms/PlugMan commands, hanging entities and bucket filling, strict command allowlist, protected cuboids, biome cell boundaries, concurrent FAWE edits, staff task world/operation/local region/total quota, restart after a partial quota reservation, approval replacement, revocation, sanction and expiry. Test missing/disabled hook and supported direct entity commands. Do not claim coverage for arbitrary direct writes.
- Verify independent lockdown/containment expiry and recovery after restart, corrupt/unwritable state and console recovery.
- Test each moderation action, platform hierarchy, offline/overlap/revision/revoke/expiry, uncertain delivery, appeal screenshot, independent review, five information requests, replies and sanction unchanged after a decision.
- Test rank reconciliation for manual and inherited groups, role permission changes, canonical Java, local Floodgate linked/unlinked/remapped and provider timeout/global-only; observed AuthMe/noncanonical 24h age and contradictory mapping. Unknown results do not revoke ranks.
- Verify chat/mentions/loop prevention, private channel permissions, identity correlation false positives, actor/target attribution, evidence export and CoreProtect preview/rollback on disposable data.
- Run [matched load benchmarking](load-testing.md) and record baseline/feature-on measurements.

## Boundaries and outstanding validation

No Bukkit plugin can guarantee interception of arbitrary privileged plugin or console code, NMS direct writes, or every FAWE implementation path. The WorldEdit extent supports block, biome and entity creation; direct entity removal commands are denied, not generally intercepted. External account remapping without authoritative provider data remains unknown. AuthMe name-based identity reuse needs staff recovery. The optional lobby refuses unsafe or unloaded return locations rather than changing inventory or guessing a safe destination. Minecraft dashboard theme covers headings, body templates, input prompts, common notices and action labels; Discord administrative interface text remains partially fixed. Actual server benchmarks, full Java 25 Paper and Node integration test results and live plugin/Discord acceptance must be verified before a stable release.

Guard protection is a policy layer, not a replacement for permissions, backups and tested recovery. Never use production worlds for destructive tests.

- Saturate Discord background work across multiple guilds, exercise global and route rate-limit events, confirm the queue stays bounded, verify no stale leases are extended, and ensure moderation is not dispatched or reported as uncertain merely because a queued request expires. Verify that an updated private-channel permission blocks queued sensitive messages and that chat/evidence retries do not duplicate messages within Discord's nonce deduplication window. Verify the periodic storage cleanup is off-thread, and validate that task budget reservations persist through restart.

- Run /ob reload after each category/menu/message edit. Test malformed types, failed bind, failed rollback, busy signed request and pending kick, unchanged cosmetic reload with active verification, pending linking code preservation, fresh staff leases, retained Guard budgets, and stale Java/Bedrock form callbacks. Verify help and tab completions for ordinary verified/unverified users, trusted staff, console and players with/without reload permission. Confirm private request IDs are visible only to their owner.
