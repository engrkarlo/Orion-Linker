# OrionBridge 2.0.0-alpha.4

**Development foundation — not a production security release.** Existing v1 telemetry remains supported. New: opt-in signed Pull audit feed, versioned configuration migration, and central SQLite identity/case/evidence primitives. Opt-in typed local Minecraft moderation supports a subset of sanctions; experimental mandatory verification has a live Pull contract, Java dialog/inventory and Bedrock forms. An experimental OrionBot extension is included for Discord verification, evidence, network comparison and Minecraft moderation. Full role/chat sync and third-party security hooks are still pending. See [extension setup](docs/extension-setup.md). See [development status](docs/development-status.md) and [architecture](docs/architecture.md).

Standalone source imported from [OrionBot at cd031ad](https://github.com/engrkarlo/OrionBot/tree/cd031ade390aec6d84f046a297156d86e2eb7f16/OrionBridge). Existing OrionBot integration remains in place during migration.

OrionBridge is an optional Paper 26.3 plugin that lets OrionBot read live Minecraft information, including custom skin textures and performance metrics. Your normal Discord status panels still work without it.

## Requirements

- A running OrionBot installation with a registered Java Minecraft server.
- Paper 26.3 with Java 25. SkinsRestorer and Floodgate are optional.
- An OrionBridge JAR built for the target server. Get it from a successful [build workflow](https://github.com/engrkarlo/Orion-Linker/actions/workflows/build.yml), or [build it yourself](#build-from-source).
- A public HTTPS reverse proxy on either the Minecraft host (Pull) or the bot host (Push).

Use **Pull mode** if your Minecraft Pterodactyl host provides a reverse proxy. You do not need a new port or domain on the Discord bot host.

| Mode | How it connects | Where HTTPS is needed |
| --- | --- | --- |
| Pull (default for new installs) | OrionBot requests snapshots from Minecraft | Minecraft hosting |
| Push | Minecraft sends snapshots to OrionBot | Bot hosting |

## Quick setup: Pull mode

### 1. Set up the Minecraft host

In your Minecraft server's Pterodactyl panel, allocate a spare TCP port, for example `25762`. Create a subdomain and HTTPS reverse proxy pointing to that allocation. Choose a valid SSL certificate (such as Let's Encrypt if offered).

| Reverse proxy field | Example |
| --- | --- |
| Domain | `bridge.example.com` |
| Target | Your Minecraft server's allocated IP and `25762` |
| SSL | Valid public TLS certificate |

Use the domain provided by your own host. DNS alone cannot forward to a port. The plugin serves plain HTTP behind the HTTPS proxy, so restrict direct backend access to the proxy if your host supports this.

### 2. Install and configure the plugin

Put the JAR into Minecraft's `plugins/` directory and start Paper once. Open `plugins/OrionBridge/config.yml`:

```yaml
mode: pull
bind: "0.0.0.0"
port: 25762
endpoint: ""
token: ""
interval-seconds: 10
hide-vanished: true
respect-listing-preference: true
skinsrestorer-fallback: true
```

Set the port to your own allocation. Leave `endpoint` empty in Pull mode. If a token already exists, keep it rather than clearing it unless you intend to rotate it.

Save the file and run `/ob reload` in Minecraft, or `ob reload` in the server console. When the Pull token is blank, OrionBridge generates a secure 64-character token and writes it to the config file. Reopen the file and copy that token privately.

### 3. Connect OrionBot

In Discord, as a member with Manage Server permission:

1. Run `/mcserver manage` and select the registered Java server.
2. Open **OrionBridge** → **Configure Pull**.
3. Enter your HTTPS domain (for example `https://bridge.example.com`), the token from Minecraft's config file, and a polling interval such as 10 seconds.
4. Save and check that the connection says **Connected**. Use **Test / refresh** if needed.

No `ORION_BRIDGE_*` environment variables are required for Pull. The bot adds `/v1/snapshot` itself. Polling is on demand during status requests and scheduled panel updates, not an independent constant polling loop.

### 4. See the results

Click **Performance** on a status card. Regular members can view TPS, MSPT and server ping; Manage Server holders also see JVM heap, average player ping and recent roster changes. Publish a dedicated performance panel in a restricted staff channel if desired. Enable Player avatars in the Minecraft Status Manager to see applied skin heads in the player list.

## Startup and upgrades

The console startup summary credits Karlow and shows the active mode, collection interval, privacy controls and saved-skin availability. It never prints pairing credentials or the remote endpoint. READY means the local session initialized, not that the remote bot has received a snapshot yet. Push delivery backs off on failures, including HTTP 429/503 retry timing; reload and shutdown release the old HTTP client. Existing telemetry settings are preserved; versioned migration adds disabled security settings and saves a backup. Replacing the JAR does not replace an existing `plugins/OrionBridge/config.yml`; retain the data folder and current token.

## Reload and manage tokens

Use `/ob reload` after editing `config.yml`. It requires `orionbridge.reload` (operators by default). Reload validates the settings, restarts the listener/session, and attempts to restore previous settings if the replacement fails. Installing a new JAR still requires a server restart.

For Pull, clear or replace the token in Minecraft's config, reload, reopen the file and update the token in Discord. Disconnecting in Discord removes the bot's configuration but does not invalidate the plugin's token; rotate it on Minecraft to revoke old clients. Never post tokens in Discord or share screenshots that reveal them.

## Troubleshooting

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

## Alternative setup: Push mode

Use Push when the bot host already has a suitable HTTPS reverse proxy. Update OrionBot, preserve existing data and configure:

```env
ORION_BRIDGE_PUBLIC_URL=https://bridge.example.com
ORION_BRIDGE_BIND=127.0.0.1
ORION_BRIDGE_PORT=8787
```

Forward only POST requests to `/v1/snapshot/*` to the bot receiver on port 8787. If using a URL prefix, strip it at the proxy. Set a 1 MiB body limit and rate limits; do not log Authorization headers. If both processes share a host and network namespace, the public URL may instead use `http://127.0.0.1:8787`. In a separate container, choose an appropriate allocated bind address; do not expose the plaintext listener to the public internet.

Restart the bot. In `/mcserver manage` → server → OrionBridge, choose **Create Push pairing**. Copy the one-time endpoint and token, set `mode: push` in Minecraft's config, paste both values and run `/ob reload`. Do not use the Pull-generated token in Push mode.

Existing Push installations without a mode field retain Push when an endpoint is configured. Creating a new pairing invalidates the old Push token. In both modes, pairings are tied to the registered server and Discord guild.

## Privacy, skins and telemetry

OrionBridge reads server data; it does not run remote console commands or change player skins. It excludes players with `orionbridge.hide`, common vanished metadata (when enabled) and players who disallow listing (when enabled). The visible count follows the same filters. Other vanish plugins may need the hide permission; public API fallback may report different counts.

Skin lookup prefers the current Paper profile's Mojang texture ID. If missing, the optional SkinsRestorer saved-skin lookup runs off-thread, then the bot can fall back to its existing Floodgate/Geyser or Java public skin lookup. Saved skins can differ from temporary live skins. Bedrock textures that are only client-side are not necessarily exposed by Paper. MCHeads renders the texture; provider availability and caching still matter.

Snapshots include visible player names and UUIDs, textures, ping, server version and MOTD, TPS, average MSPT, JVM heap used/max and the advertised icon. Recent joins/leaves are inferred from consecutive snapshots, not an event history. The external server ping in Discord is a separate public protocol measurement, not the bridge HTTP latency.

## Security and technical limits

- Pull uses HTTPS domains on port 443 with public IPv4 DNS resolution, pinned destination addresses and no redirects. The bot encrypts Pull tokens using `data/orionbridge.key` (mode 0600 on Unix). Protect and back up this key alongside the database. Push stores only a token hash.
- The Minecraft listener serves authenticated snapshots and opt-in separately signed security endpoints; credentials are checked before returning data. The plugin does not log the token. Use HTTPS for remote connections; restrict access to plaintext backend ports.
- Snapshots expire after 90 seconds; the public status API is used if live bridge data cannot be obtained. Payloads are capped at 1 MiB, visible rosters at 2,000 entries, and timestamp skew at two minutes. Plugin collection interval: 5–30 seconds; bot Pull polling minimum interval: 5–60 seconds.
- Bukkit reads happen on the main thread. Network operations and optional saved-skin lookup run on a single worker with no overlapping collections. The bot Push listener is disabled unless its public URL is set.

## Build from source

From this repository root with JDK 25 and Maven 3.9+:

```sh
mvn -B -f pom.xml clean verify
```

Output: `target/OrionBridge-2.0.0-alpha.4.jar`. The POM pins Paper API `26.3.build.263-stable` as a provided dependency; the optional SkinsRestorer and Floodgate plugins are not bundled. The GitHub workflow attempts to build with the official API and uploads the JAR on success; check the run result rather than assuming an artifact exists.

[OrionBot host project](https://github.com/engrkarlo/OrionBot)
