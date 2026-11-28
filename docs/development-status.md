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
