# OrionBridge 2.0 development contract

Pull-first, Paper 26.3 / Java 25, existing Minecraft-side HTTPS proxy only. Preserve v1 snapshot and Push compatibility. OrionBot is authoritative for guild-scoped one-to-one identities and per-server policies. No arbitrary remote console.

## Roadmap
1. Import 1.1.0 and standalone CI.
2. Versioned config migration, signed scoped event/action transport, durable local queue and central persistence.
3. Mandatory verification after setup, authenticated Java/Floodgate/AuthMe identities, two-party expiring link codes, Java dialogs/inventory/Bedrock forms, Discord verification panel.
4. Configurable punishments, type/duration/scope, approval, evidence, durable ACK and overlap-safe expiry; no punishment from IP alone.
5. Per-server channels, public/staff chat, safe LuckPerms sync, trusted operation profiles with command allowlists, WorldEdit and cumulative budgets, revocable Discord-role-derived leases.
6. OrionGuard: privilege escalation, WorldEdit/FAWE, CoreProtect evidence, aggregate alerts, incident dashboards, guild-keyed network fingerprints, rollback preview/approval.
7. Discord Components V2 and in-game dashboards; all labels/colors configurable. Humanized docs, Java/Bedrock/Pull live testing.

## Trust boundary
The central store is an internal library, not an authorization boundary. Host adapter must verify Discord membership, staff roles, target scope, authenticated Minecraft session, account age/cooldown and rate limits before invoking it. No external endpoints or enforcement are enabled by this library. Record historical identities and distinguish an observed actor from affected account. Shared networks are not proof of ownership. Host/JVM compromise is outside plugin guarantees.

## Acceptance
Test concurrent linking, duplicate/replay, spoofed offline identities, guild isolation, stale role authorization, command aliases, FAWE async coverage, overlapping sanctions, crash recovery, queue bounds, config preservation and actual end-to-end Discord/Paper/Bedrock interactions before 2.0 stable.
