# Development status — alpha.1

This prerelease preserves the telemetry feature set. It does not enforce account linking or punishments. The new central store is a library, not a Discord bot or public API; integration with OrionBot is still required.

## Experimental audit feed
With a test server and registered guild/server IDs, enable audit.enabled and set audit.guild-id, audit.server-id and a separate random 64-hex audit.signing-key in config.yml. Keep credentials private. Restart (audit configuration is startup-only). Existing snapshot credentials and v1 GET are unchanged. Only Pull provides the experimental audit feed.

GET /v2/events and POST /v2/acknowledge require the existing Bearer token plus X-Orion-Guild, X-Orion-Server, X-Orion-Time (epoch milliseconds), X-Orion-Nonce (32 hex), and X-Orion-Signature (HMAC-SHA256 hex over newline-delimited method, path, guild, server, time, nonce, SHA256(body); key is decoded hex). ACK body is newline-separated canonical event UUIDs, max 100. Requests reject stale time/reused nonce in the current process; nonce cache is not durable across restart. Use TLS. No arbitrary action endpoint is provided.

bot/audit-pull.js uses the existing pinned public HTTPS transport. drainAudit takes trusted registered scope credentials and a central store; commits evidence before acknowledging. It is not scheduled by OrionBot yet. The spool caps at 4096 records and 512 pending writes. Drops are visible in server logs and feed. Forced process termination may lose queued but not yet persisted events. Keep the feed disabled unless actively drained.

Paper observation: join/quit, operator status changes observed on one-second samples (grant actor unknown), selected command categories (attempt not execution, no args logged), aggregated uncancelled block break/place event counts (not proof of grief). No WorldEdit/FAWE, LuckPerms or CoreProtect integration yet. Offline/proxy/Floodgate identity attribution is unknown in this phase. No IP collection implemented; only fingerprint storage/comparison primitives.

## Remaining acceptance work
- Host integration with Discord permissions and membership, background scoped polling, audit cards and persistent configuration.
- Authenticated Java/Floodgate/AuthMe identity resolution, verification state machine, rate limiting, two-party GUI, account recovery.
- Punishment execution, approval/expiry/revocation and overlapping restrictions.
- Role synchronization, chat/channel manager, trusted staff leases, security integrations, evidence export and rollback approval.
- Full Java/Bedrock interfaces and live Pull tests. Do not deploy this as a complete security system.

## Alpha.2 typed control
Requires restart and explicit control.enabled, distinct control.signing-key, audit.guild-id and audit.server-id. POST /v2/actions uses same scoped HMAC envelope as audit but the control key. Body is seven newline-delimited fields: case UUID, positive revision, canonical java/bedrock identity, allowed type, absolute expiry epoch milliseconds (0 indefinite), APPLY/REVOKE, base64url UTF-8 reason. Response PERSISTED confirms the local case record, not that a disconnected player was kicked. Revision tombstones reject stale applies after revocation. Local case store has a 10000-case cap; expiry evaluated on use. No deletion of third-party punishments.

Supported experimental restrictions: Minecraft mute, ordinary movement/interaction freeze/quarantine, break/place/bucket build restriction, temp/permanent ban checked on join and online after delivery. Not all plugin/API/event paths are covered. Direct online-mode Java and Floodgate identity resolver only; offline AuthMe/forwarded Java need further work. Unresolved identities are not enforced by this alpha. Floodgate and client behavior require live testing. Do not rely on this prerelease for production raid protection. Control initialization/storage failure is logged, not silently treated as active enforcement.

bot/moderation-pull.js delivers approved cases and records per-server acknowledgements; approval/case functions require a trusted host adapter for permissions and membership. Discord-only and cross-platform enforcement, warnings, kicks, role and WorldEdit restrictions are not yet supported by the local endpoint. The adapter does not register Discord handlers or schedule itself.

## Alpha.3 verification
Opt-in only: set verification.enabled and verification.setup-complete true after the host adapter is integrated and tested, control.enabled true, configure separate control key, scope and Discord invite. Requires restart. If the bot is unreachable, unverified players are restricted and verified leases expire in at most 60 seconds. Console: ob verification-off temporarily releases the gate until restart. If the gate failed to initialize, correct config and restart. Never enable on a live community before testing recovery.

GET /v2/verification exposes authenticated active sessions and pending request tokens, not plaintext codes. POST /v2/verification uses control HMAC and typed OFFER, CLAIM, LEASE, RESTRICT messages bound to player UUID, current join session and identity. bot/verification-pull.js orchestrates code issuance, Discord member validation and final central confirmation; the host must supply the current membership check, registered scope credentials, Discord modal handler and polling scheduler. Codes are single-use at final confirmation, session-bound, expire after 5 minutes, and claims are rate-limited by the service. One-to-one unique constraints are guild scoped. Links are not yet transferable/recoverable via dashboard.

Native Java dialog / inventory and Bedrock forms use independently configurable menu files; chat fallback. The renderer helpers originate from the user's OrionHomes project. Gameplay gate cancels ordinary Paper event paths and resolves actual plugin-owned verification/authentication commands. Other plugins/direct API actions can bypass hooks; integration and live client tests are still required. AuthMe uses explicit realm plus normalized authenticated name, not a spoofable offline UUID; use the same realm only for a shared trusted AuthMe database. Offline name changes are not migrated automatically. Trusted proxy Java without AuthMe is not supported yet. Separate auth plugin/bridge versions must be live tested.
