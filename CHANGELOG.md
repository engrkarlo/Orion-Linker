# Changelog

## Unreleased
- Full guarded /ob reload for connection, all feature files, messages and menus; retain live sessions for cosmetic changes and preserve Guard state/challenges across compatible runtime rebuilds. Reject busy signed work and roll back failed initialization.
- Add configurable styled /ob help [command], permission-aware completion, private confirmation ID suggestions, and YAML-driven player messages, chat, menu presentation and startup console colors.
- Configuration v15: separate eight feature categories into config/, retain legacy settings and private backup, verify output before atomic replacement, reject migration conflicts and missing category files.
- Human-friendly feature comments and menu theme v2 guidance; preserve custom menu values, create all menus even while verification is disabled. Update operator documentation and setup instructions.
- Final source inspection: restore missing immutable edit authorization type, reduce redundant LuckPerms transient node replacements, move bounded storage pruning off the main thread, optimize replay nonce expiry and enlarge the bounded verification batch payload. Large task quotas reserve up to 1024 attempts per durable write.
- Redesign the colored startup diagnostics, distinguish local readiness from remote connection, and expose requested-but-unavailable integrations.
- Expand Minecraft dashboard themes, enforce explicit task command allowlists, and recognize sensitive resolved commands for common administration plugins.
- Recheck signed offline rank mappings against current verification sessions before returning ranks.
- Add signed task-specific edit grants with cumulative durable quota, immutable asynchronous authorization, exact world and operation scope.
- Add local Floodgate linking-provider validation, opt-in strict resolved-command allowlist and expanded event enforcement.
- Add opt-in verification lobby with authenticated return records; all Java and Bedrock clients retain reconnect-safe linking.
- Reorganize the documentation into installation, player, commands, administration, configuration, troubleshooting and acceptance guides. Config v14 is additive.
- Add configurable Minecraft dashboard rows, button slots, Java dialog width/columns and Bedrock button images with safe layout fallback.
- Config v12 adds opt-in bounded observed offline AuthMe/noncanonical Bedrock rank queries, and rechecks authenticated identity before local profile/account authorization.
- Config v11 adds local world/region/operation/command restrictions for named task profiles, non-player edit budgets and direct entity command denial.
- Persist individual automatic containment by authenticated identity with independent deadlines and bounded storage.
- Preserve unexpired linking challenges across client reconnects; discard stale session IDs, confirmations and authorization leases. Avoid opening verification menus before AuthMe authentication and recheck identity on player actions.
- Deny resolved WorldEdit/FAWE commands when required edit hooks are unavailable; add configurable dashboard heading/icon keys and route formal appeals through the native Discord panel.
- Document matched live load benchmark procedure and acceptance gates.
- Persist bounded manual and automatic lockdown deadlines across normal restarts; add smoke coverage and explicit storage failure handling.
- Add bounded offline rank lookup for matching authenticated Floodgate observations with the native bot; unsupported mappings remain unknown.
- Extend ordinary Guard event coverage to bucket filling and inventory opening, and add a player linking guide. This does not guarantee third-party bypass protection.

## 2.0.0-alpha.7 (development preview)
- Config v10 preserves existing settings while adding independent rule response, severity, duration and cooldown settings.
- Local reversible containment covers guarded commands, building, edit budgets, movement, interactions and chat; overlapping restrictions expire independently. Rejoining does not reset the ordinary block budget.
- Read-only, bounded offline LuckPerms rank lookup for previously observed authenticated Java UUIDs.
- Matching OrionBot branch separates verification and delivery polling, isolates failed account challenges, rejects managed staff-role conflicts across guild servers and adds repeat-case duration suggestions.
- Player-triggered telemetry reload under Guard requires an explicit trusted profile command grant. Console recovery remains available.
- Development preview: follow acceptance checklist; live integration and remaining limitations are not certified.

## 2.0.0-alpha.6 (development preview)
- Config v9 migration preserves existing settings and credentials; new enforcement modules opt in.
- Bounded verification batches, shared identity resolution, mutable event counters, incremental sanction indexing and task timings.
- Linked staff profiles, temporary leases, command checks, WorldEdit operation/minute budgets, LuckPerms permission observations and local containment.
- Managed transient LuckPerms groups, public/private chat, privacy-aware activity events, and additional moderation types.
- Player and staff dashboards, report/appeal requests, CoreProtect preview and separately enabled recovery execution.
- Matching native OrionBot branch adds role mappings, identity recovery, incident grouping/notes, network match alerts, Discord sanctions and cross-platform receipts.
- This is not a security certification. See the acceptance checklist before use.

## 2.0.0-alpha.5
- Discord companion integrated directly into OrionBot's Minecraft Status Manager, preserving existing Pull pairing and interaction IDs. No extension package is required or built.
- OrionBot automatically migrates legacy extension SQLite data and credentials into its native data directory; original data retained. See OrionBot's native setup guide.
- Minecraft config v5 and protocol unchanged, preserving all current settings and local sanction data. Older config versions continue to migrate with backups.
- JAR and protocol regression builds remain here; native Discord regression checks run in OrionBot. Advanced security roadmap and live acceptance remain pending.

## 2.0.0-alpha.4
- Guild-scoped OrionBot extension with independent bounded Pull scheduler, private setup credentials, per-server channel and staff-role selectors, verification panel, evidence exports and case approval/revocation UI.
- Encrypted audit/control credentials per server and network correlation key per guild; existing telemetry pairing reused.
- Optional HMAC network fingerprints from authenticated player addresses, 90-day retention, manual comparison only. Raw IPs are not transmitted or stored by this feature.
- Historical link attribution, persistent Discord alert receipts, active server selection, config v5 migration and SQLite migrations.
- Standalone extension ZIP built by CI. No live Discord or Minecraft acceptance has been performed; security modules remain opt-in.

## 2.0.0-alpha.3
- Session-bound verification leases and two-party linking exchange; experimental gameplay gate and console-only recovery.
- Configurable Java native dialogs, inventory and Bedrock forms, chat fallback; no plaintext verification code in snapshots or database.
- Direct online Java, Floodgate and explicit AuthMe authentication realms; unsupported identities remain restricted when verification is enabled.
- Config v4 migration, Java and Node verification tests. Requires bot host integration; defaults remain disabled for prerelease safety.

## 2.0.0-alpha.2
- Separate signed control credential and typed local moderation endpoint; durable per-case revision records and overlap-safe expiry/revocation.
- Local experimental Java/Floodgate enforcement of mute, freeze, build restriction, quarantine and temporary/permanent ban; ordinary Paper event paths only. No unrestricted remote commands.
- Central approval and per-server delivery receipts; second actor required, stale actions rejected, expired pending cases not delivered.
- Config v3 migration and regression checks. Control is disabled by default; OrionBot dashboard and scheduler are not yet integrated.

## 2.0.0-alpha.1 — development foundation
- Imported OrionBridge 1.1.0 from OrionBot cd031ad; preserved telemetry and legacy transport.
- Standalone Java 25 Maven and Node 22 CI; original telemetry regression tests.
- Version 2 config migration with backups, preserving token, explicit values and legacy implicit Push mode.
- Opt-in signed, guild/server-scoped Pull audit endpoints and bounded persistent event spool; observation only, not raid prevention.
- Paper join/quit, observed OP changes, selected sensitive command attempts and aggregated uncancelled block activity.
- Central SQLite library for two-party link challenges, unique links, punishment case drafts, deduplicated evidence and fingerprint comparisons.
- Added regression tests. Full OrionBot adapter, verification gates, sanctions, UI, role/chat sync and third-party integrations are not yet implemented.

## 1.1.0
Imported telemetry baseline with Pull/Push, live player textures, performance metrics and startup reporting.
