# Acceptance checklist and known boundaries

Alpha.7 is a development preview. Compile and protocol smoke tests are necessary but insufficient. Keep production on its existing stable configuration until matched live tests pass.

- Upgrade from config v5/v9 with custom port, token and menu/data files. Verify preserved values, backups and restart persistence.
- Link Java online, Bedrock/Floodgate, AuthMe with explicit realm; reject spoofed identities, reused/expired codes, mismatched guild/server, duplicate linking. Test reconnect, dialog/inventory/forms, console recovery and bot outage. Load test batch lease renewal.
- Exercise trusted command aliases/namespaces, role removal, temporary approval expiry, protected worlds/cuboids, biome cells and entity creation, per-operation and per-minute WorldEdit limits with concurrent FAWE edits. Confirm unsupported paths are not advertised as blocked. LuckPerms node events identify affected holders; reported action logs may identify authenticated initiators. Verify actor/target labels separately.
- Test each local punishment, offline target, restart persistence, overlap, expiry, revocation and revision replay. Test Discord hierarchy, external bans/timeouts, partial delivery and uncertain one-shot requests.
- Test role sync against manual/inherited groups, permission changes, offline Java rank changes, managed role/staff-role collisions and unlinked users. Test staff chat isolation, loop prevention, mute and queue drops. Confirm activity respects privacy.
- Test fingerprint matches and exceptions only from real authenticated client addresses. Network overlap is never proof of identity.
- Test evidence checksum, grouped incidents, staff notes, channel access and export. Use a disposable CoreProtect world for preview/rollback; expired or changed preview must fail. Check actual restored blocks; CoreProtect's time-window API is not transactional.
- Measure MSPT and event-loop behavior under actual population and slow/unavailable reverse proxy. Task timings and heap metrics do not prove zero lag.

- Verify automatic and manual lockdown across restart, expiry, clearing, and corrupt/unwritable state; ensure a failed save is not acknowledged. Test previously observed matching Floodgate UUID rank lookup with Floodgate absent and with an incompatible mapping; unknown results must not revoke ranks.

- Test fail-closed resolved WE/FAWE commands with a failed or disabled hook, and verify the separate Discord appeal submission and independent review flow. Run the [live load benchmark](load-testing.md).

- Restart during overlapping automatic build/freeze containment; check independent expiry and exact authenticated identity isolation.

## Remaining engineering gaps
Comprehensive third-party/FAWE bypass coverage, effective-permission attribution, per-category task-specific approvals and external region integration, offline AuthMe and noncanonical/externally relinked Bedrock reconciliation, attachment intake, exhaustive UI/theme coverage, optional verification lobby, attachment-based and multi-round appeals, verified high-load performance and production incident recovery remain incomplete or unverified. Rule-specific containment and repeat-case duration suggestions exist but require live acceptance; no automatic escalation to permanent bans. Do not advertise full raid prevention. No live Discord or Paper instance is controlled by the build.
