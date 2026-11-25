# OrionLink — development preview 0.1.0

Two-component foundation for Paper 26.3 (Java 25) and OrionBot. **Not a production release.** Minecraft → Discord only, with one authoritative Paper server per Discord guild. Different guilds may be configured independently. Do not use for staff privileges or production access control until integrated and reviewed.

## Build
- Paper: JDK 25 and Maven. Run mvn -B -f minecraft-plugin/pom.xml clean verify. Output: minecraft-plugin/target/OrionLink.jar.
- Extension: Node 22+, run npm install and npm test in orion-extension/. Install the directory using OrionBot's extension manager (manifest and extension.js at the root).
- GitHub Actions compiles the plugin, uploads the JAR, and runs the extension tests.
- Paper API is pinned to 26.3.build.252-stable; verify artifact availability and desired stable version before production.

## Setup (test environment)
1. Install LuckPerms and the compiled plugin on an authenticated (online-mode=true) Paper test server. First startup writes plugins/OrionLink/config.yml and disables until configured.
2. Install the OrionBot extension. Its first enable writes an empty config.json in its extension data directory. Use orion-extension/config.example.json as a template. Enter actual Discord guild and role IDs. Generate a separate secret per server using openssl rand -hex 32. Use matching server ID and secret in Minecraft. Never commit actual credentials.
3. The extension listens on 127.0.0.1:8787. Configure a TLS reverse proxy for wss://your-domain/orionlink on the bot host, preserving WebSocket upgrade and Authorization and X-Orion-Server headers. Do not expose plaintext or log authorization headers. Set endpoint in Minecraft to this WSS URL.
4. Give OrionBot Manage Roles and place its highest role above managed roles. Elevated and integration-managed roles are blocked for additions. Use ordinary rank roles.
5. Enable the extension and restart the Paper test server. Run /link in Minecraft, then /orionlink link code:... in Discord. /orionlink status and /orionlink unlink are available in Discord. Linking does not guarantee immediate assignment; role sync runs separately.

## Implementation
- LuckPerms recalculation and player-join events enqueue bounded, UUID-coalesced snapshots. Group calculation, serialization and WSS writes run on a dedicated Java worker, not the Minecraft tick thread.
- WSS, standard TLS verification, per-server secret, bounded frames, rate limits, heartbeat and reconnect backoff.
- Random 48-bit single-use verification codes with five-minute expiry, guild scope, five attempts per minute per Discord user and bounded storage.
- Incremental Discord role updates. Only roles recorded as added by OrionLink are removed. Periodic batched requests reconcile linked offline accounts and missed events. JSON state uses atomic replacement and serialized operations.
- Components V2 responses and OrionBot lifecycle exports following OrionWave conventions.

## Known limitations / release gates
- Paper compilation, Discord.js/WSS runtime, actual OrionBot loading, and end-to-end behavior remain unverified until CI and live testing. No performance benchmarks.
- Not implemented: Discord → Minecraft, bidirectional conflict resolution, per-guild multi-server authority, dashboard editing, pairing tokens, Minecraft unlink/status, nickname/reward/PlaceholderAPI, Floodgate/proxy identity, permission-node/contextual mappings, temporary rank semantics validation, automatic retry of pending unlink on member departure.
- JSON persistence is a development store. A crash between Discord role mutation and ownership persistence may leave orphaned roles. Production requires a durable operation journal, recovery, audit history, handling role hierarchy/privilege changes, membership reconciliation and load testing. Pre-existing mapped roles intentionally remain untouched.
- Queue pressure can drop updates; reconciliation is eventual. Test frame limits with large permission configurations. Do not assume zero MSPT impact.
