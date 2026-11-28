# Changelog

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
