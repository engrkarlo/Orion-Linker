# Troubleshooting

[Documentation index](README.md) · [Installation](installation.md) · [Commands](commands.md)

## Connection and telemetry

| Symptom | Check |
| --- | --- |
| No connection | Check proxy target, allocated port, plugin bind address, certificate and token. |
| Browser receives 404 at the domain root | Expected; there is no website homepage. |
| Browser receives 401 at `/v1/snapshot` | Expected without a token. A bot 401 means its token does not match. |
| HTTP 502 | The proxy cannot reach the plugin listener. Check allocation and startup/reload logs. |
| HTTP 503 | An authenticated Pull request has no fresh snapshot yet. |
| HTTP 400 / clock errors | Synchronize server clocks and check compatible plugin/bot versions. |
| Missing custom skin | Check player avatars, backend profile textures and SkinsRestorer storage access. |
| Missing icon | Check Paper's advertised favicon or set an HTTPS icon in OrionBot. |
| TPS or MSPT unavailable | Check bridge connection and open Performance rather than the ordinary status card. |


## Verification

| Symptom | What to check |
| --- | --- |
| AuthMe login works but player cannot move | Intended during mandatory verification; complete /ob link and Discord confirmation. |
| Player disconnects before confirmation | Rejoin the same server with the same authenticated account; the original five-minute code remains valid until expiry, explicit regeneration or consumption. |
| Minecraft or bot restart | Keep matching versions, databases and signing keys. Unexpired post-upgrade challenges can be restored. Older random codes may need regeneration. |
| Verification menu missing | Authenticate first, run /ob link or /ob account. Check the bot and signed endpoints. |
| Code claimed but not linked | Confirm the displayed Discord account inside Minecraft; check membership and original expiry. |
| Verified players suddenly restricted | Authorization leases expired; inspect bot connectivity, guild membership, proxy and clocks. Use console recovery only if necessary. |
| Optional lobby return fails | Original chunk must be loaded and safe; check sanctions, world existence, record identity and other teleport plugins. Staff can arrange safe recovery, then player may retry /ob return. |

## Guard and rank sync

A missing WorldEdit/FAWE hook means supported edit commands are denied when enforcement is enabled. An expired or revoked task, exhausted quota or protected region also denies mutations. Check the exact profile and operation. Unknown offline rank data never proves removal. Verify both opt-in switches for observed mappings; leave them off for externally remapped identities.

## Storage and upgrades

Preserve config.yml, config/, menu files, audit/sanction data, guard-lockdown.state, guard-containment/, guard-task-budgets/, verification-return/ and all bot data and keys. Do not erase a corrupt file just to restore operation: quarantine the affected service and investigate. The config backup is made during version migration. Downgrade using matched backups rather than starting older code against newer databases.

## Privacy, skins and telemetry

OrionBridge reads server data; it does not run remote console commands or change player skins. It excludes players with `orionbridge.hide`, common vanished metadata (when enabled) and players who disallow listing (when enabled). The visible count follows the same filters. Other vanish plugins may need the hide permission; public API fallback may report different counts.

Skin lookup prefers the current Paper profile's Mojang texture ID. If missing, the optional SkinsRestorer saved-skin lookup runs off-thread, then the bot can fall back to its existing Floodgate/Geyser or Java public skin lookup. Saved skins can differ from temporary live skins. Bedrock textures that are only client-side are not necessarily exposed by Paper. MCHeads renders the texture; provider availability and caching still matter.

Snapshots include visible player names and UUIDs, textures, ping, server version and MOTD, TPS, average MSPT, JVM heap used/max and the advertised icon. Recent joins/leaves are inferred from consecutive snapshots, not an event history. The external server ping in Discord is a separate public protocol measurement, not the bridge HTTP latency.

## Security and technical limits

- Pull uses HTTPS domains on port 443 with public IPv4 DNS resolution, pinned destination addresses and no redirects. The bot encrypts Pull tokens using `data/orionbridge.key` (mode 0600 on Unix). Protect and back up this key alongside the database. Push stores only a token hash.
- The Minecraft listener serves authenticated snapshots and opt-in separately signed security endpoints; credentials are checked before returning data. The plugin does not log the token. Use HTTPS for remote connections; restrict access to plaintext backend ports.
- Snapshots expire after 90 seconds; the public status API is used if live bridge data cannot be obtained. Payloads are capped at 1 MiB, visible rosters at 2,000 entries, and timestamp skew at two minutes. Plugin collection interval: 5–30 seconds; bot Pull polling minimum interval: 5–60 seconds.
- Bukkit reads happen on the main thread. Network operations and optional saved-skin lookup run on a single worker with no overlapping collections. The bot Push listener is disabled unless its public URL is set.



## Startup console

The five-section startup summary separates runtime, transport/privacy, local services, optional integrations and final readiness. `READY` means local initialization, not confirmed Discord connectivity; `DEGRADED` means a requested service or required transport is unavailable, and `INACTIVE` means the listener did not start. An installed optional plugin is not proof of a tested integration. Use `ob status`, inspect preceding warnings and check OrionBot's bridge manager for actual connectivity. No credentials are printed. Console `ob verification-off` can also release the startup fallback login restriction until restart.

If v15 migration reports a conflict, preserve the original and existing category file. Compare their values, move the conflicting category aside only after backing it up, and restart to migrate the original root. Reapply any intended changes to the new file afterwards. For a missing v15 category, restore the file from backup; do not reset signing keys or downgrade the version by hand. Console `ob verification-off` can temporarily release the configuration-error join restriction.

If /ob reload reports busy, retry when the current signed work finishes. If the previous runtime was restored, fix the edited YAML before retrying; the plugin does not silently rewrite your changes. A failed rollback restricts joins and requires console recovery; restore a known-good configuration first. Cosmetic changes do not restart services. New JARs still require a server restart.
