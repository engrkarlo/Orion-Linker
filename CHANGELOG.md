# Changelog

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
