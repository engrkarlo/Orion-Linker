# Quickstart & upgrading

## New server

**You need:** Paper 26.3, Java 25, OrionBot 1.9.0-alpha.1, a registered Java server in `/mcserver manage`, a spare hosting allocation and a public HTTPS reverse proxy. OrionBot needs no inbound port.

1. Install the matching plugin JAR and start the server. In `plugins/OrionBridge/config.yml`, set the allocated **port** (not the Minecraft port). Keep `mode: pull`, `transport: AUTO`, and the default bind unless the host requires another.
2. Configure the proxy to that allocation. Use a valid public certificate on port 443; forward HTTP and WebSocket upgrades, Authorization, X-Orion-* headers, and request bodies. Do not cache bridge requests. If the provider cannot upgrade WebSockets, HTTPS still works.
3. In the Minecraft console, run `ob setup`. Copy the private temporary code (five minutes).
4. In Discord, use `/orionbridge setup` and select the registered Java server. Enter the public HTTPS origin and temporary code, not a permanent token.
5. Check `ob setup status` in the console. Compare the Discord guild, registered server ID and requesting account with the private Discord prompt. Only if correct, run the displayed `ob setup approve <id>` command.
6. Click **Complete pairing**, then **Check setup**. The token is stored privately and the control service is provisioned. If local activation is busy, run `ob reload` and check again. If an incomplete pairing is lost after a restart, run `ob setup` again; credentials are not replaced.

## Choose what you want

Open Setup from `/orionbridge manage`. Choose existing Discord channels or explicitly create a missing one. Staff/security channels are private; select staff roles first. The bot must have appropriate Discord permissions. Enable Message Content intent in the Discord developer portal for public chat.

| Feature | What to do |
| --- | --- |
| Public chat | Set a chat destination; choose Public chat. New optional chat can relay clearly labeled unlinked messages. |
| Optional account linking | Set a linking channel, choose Account linking, check setup and publish the panel. Test with a player. Gameplay is not blocked. |
| Required linking | Test optional linking, keep a linked test account online, then choose Require linking and approve locally. Unlinked players including operators are restricted. |
| Evidence | Set a private security destination, then choose Evidence. |
| Ranks | Install LuckPerms, link a test player, choose Rank sync and check setup. Select loaded groups and harmless Discord roles in Role Sync. |
| Guard | Review local protection rules and trusted profiles first, then choose Guard after testing forced linking. |
| Recovery | Install compatible CoreProtect, then choose Recovery. Rollback remains separately controlled. |
| Moderation | Configure private punishment logs and staff, then enable moderation in the manager. Test a reversible sanction and independent approval. |

Each feature provisioning step uses a fresh console code and approval, then **Check setup** to reconcile the Discord switches. This is deliberate for security-sensitive changes. The local file remains the source of Minecraft enforcement policy. Do not set up high-risk features on a production server until tested.

AuthMe servers need a stable configured realm in `config/verification.yml`; review authentication before activation. Floodgate must be installed and correctly configured for Bedrock. Account linking is not authentication by username. See [player linking](player-linking.md).

## Existing server

Stop Paper and back up the entire plugin directory and OrionBot data/key files. Replace the JAR, update the bot and restart. Keep your token, guild/server scope, signing keys, YAML, identities, cases, and all persistent state. Version 15 feature files are retained; newer settings are additive. Existing mandatory verification does **not** become optional. Do not create a new pairing for an already paired server. Use **Check setup** first; use automatic provisioning only for missing credentials, and never rotate keys casually.

The plugin may reload all YAML with `ob reload`, but a JAR upgrade requires a restart. If a setup request conflicts with stored credentials, it rejects the request rather than replacing them. See [configuration](configuration.md) for advanced settings, [troubleshooting](troubleshooting.md) for diagnostics, and [acceptance](acceptance.md) before deploying.

## Compatibility

For older bot builds, the existing `/mcserver manage` Pull configuration and the manual credential screen still exist. Copy the exact generated values only in a private session, not through public channels. Legacy Push is telemetry-only. HTTP-only mode is available if the WebSocket-capable listener is incompatible with the host.
