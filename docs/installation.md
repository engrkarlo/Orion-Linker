# Installation & setup

For a guided walkthrough of every file and the exact source of keys, IDs, roles and other values, start with [Complete configuration setup](configuration-setup.md).

[Documentation index](README.md) · [Commands](commands.md) · [Configuration](configuration.md)

## Installation
Paper 26.3 and Java 25; matching native OrionBot alpha.7 development branch (Node 22). Install one OrionBridge JAR and retain the entire existing plugins/OrionBridge directory. Stop the server for upgrades. Back up plugin data, OrionBot data, and both encryption/signing keys. Do not delete the database or existing token. On startup config v15 separates feature settings into config/ and backs up the original; all YAML changes can be applied with /ob reload.

## Pull pairing
Use an allocated Minecraft-host port and an HTTPS reverse proxy with a valid public certificate. Configure mode: pull, bind and allocated port. Copy the generated existing token privately into /mcserver manage > server > OrionBridge > Configure Pull. Keep the existing pairing on upgrade. Allow signed v2 GET/POST requests through the same proxy. No inbound port or proxy on the bot host. Push remains legacy telemetry only.

## Enable identity and security
1. Verify telemetry first. Keep the existing Pull token. In Discord choose Identity & Security > setup; copy the server/guild IDs and private signing keys into audit and control configuration. Never expose the keys. Enable those sections and restart Paper.
2. Configure private security/punishment channels, staff roles and verification channel. Test evidence and a short reversible sanction. Enable verification only once the correct authenticated identity provider and an independent staff recovery path are tested.
3. Publish the verification panel. In Minecraft link with a short-lived private code; confirm the Discord identity in Minecraft. One account per guild per identity. No OP bypass.
4. Configure optional Guard profiles on Minecraft, map the matching Discord roles in Trusted Operations, and test limits on disposable blocks. Configure each supported rule's response, duration, severity and cooldown in config/guard.yml. Hard safety limits remain independent. Staff profiles relax only explicitly configured checks; they do not grant Bukkit permissions.
5. For LuckPerms role sync, first allow the exact harmless group and permission names locally, then map groups/roles in Discord. Test that manual grants remain. Verified Java and matching unlinked Floodgate UUID observations support bounded offline primary-group lookup; AuthMe and noncanonical Bedrock mappings may additionally use opt-in server-specific observations up to 24 hours old; unsupported or externally remapped identities still require authenticated login. See [configuration](configuration.md). Discord managed roles must have no guild permissions and be below the bot.
6. Enable chat locally and in the bot, with a private staff channel for staff chat. Confirm Message Content intent and channel access. Never route private staff chat to a public channel.
7. CoreProtect recovery: enable lookup first. Execution is separately opt-in, requires lockdown, an offline target, a fresh unchanged preview, and independent Discord approval. Test on a copy of the world. A submitted rollback is not proof of restoration.



## Requirements and hosting details

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

Save the file and run `/ob reload` in Minecraft, or `ob reload` in the server console. On initial startup with a blank Pull token, OrionBridge generates a secure 64-character token and writes it to the config file. Do not clear an active token during reload. Reopen the file and copy that token privately.

### 3. Connect OrionBot

In Discord, as a member with Manage Server permission:

1. Run `/mcserver manage` and select the registered Java server.
2. Open **OrionBridge** → **Configure Pull**.
3. Enter your HTTPS domain (for example `https://bridge.example.com`), the token from Minecraft's config file, and a polling interval such as 10 seconds.
4. Save and check that the connection says **Connected**. Use **Test / refresh** if needed.

No `ORION_BRIDGE_*` environment variables are required for Pull. The bot adds `/v1/snapshot` itself. Telemetry polling is on demand during status requests and scheduled panel updates. Enabled security services use a separate bounded Pull polling loop. The proxy must forward the signed v2 GET and POST paths for security features.

### 4. See the results

Click **Performance** on a status card. Regular members can view TPS, MSPT and server ping; Manage Server holders also see JVM heap, average player ping and recent roster changes. Publish a dedicated performance panel in a restricted staff channel if desired. Enable Player avatars in the Minecraft Status Manager to see applied skin heads in the player list.

## Startup and upgrades

The console startup summary credits Karlow and shows the active mode, collection interval, privacy controls and saved-skin availability. It never prints pairing credentials or the remote endpoint. READY means the local session initialized, not that the remote bot has received a snapshot yet. Push delivery backs off on failures, including HTTP 429/503 retry timing; reload and shutdown release the old HTTP client. Existing telemetry settings are preserved; config v15 migration preserves values in separate feature files and saves a backup. Keep the Guard state, task budgets and verification return records. Replacing the JAR does not replace an existing `plugins/OrionBridge/config.yml`; retain the data folder and current token.

## Reload and manage tokens

Use `/ob reload` after editing any OrionBridge settings, category files, messages or menus. It requires `orionbridge.reload` (operators by default). Reload validates settings; presentation-only changes retain live services. Feature changes replace the local runtime and require fresh signed authorization. Busy requests defer reload; previous settings are restored if initialization fails, when possible. Installing a new JAR still requires a server restart.

For intentional Pull token rotation, replace the token with a securely generated 64-character lowercase hexadecimal value in Minecraft's config, reload, and update the token in Discord. A blank token is generated on initial startup, not accepted as a replacement during a live reload. Disconnecting in Discord removes the bot's configuration but does not invalidate the plugin's token; rotate it on Minecraft to revoke old clients. Never post tokens in Discord or share screenshots that reveal them.


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



For security setup, paste the audit, control and correlation credentials into their matching files under `config/` rather than into the root. See [configuration reference](configuration.md) for the exact file layout.
