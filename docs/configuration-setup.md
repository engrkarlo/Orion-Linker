# OrionBridge — Complete configuration setup, step by step

This guide is for server owners setting up OrionBridge for the first time, or adding a feature to an existing Minecraft ↔ OrionBot connection. Follow it in order. You do not need to enable everything.

**Start here:** Paper 26.3, Java 25, matching OrionBot native integration, configuration v15. This is a development preview. Test security and moderation on a private staging server before enforcing them on your live SMP.

## Contents

1. [Before you begin](#1-before-you-begin)
2. [Connection — config.yml](#2-connection--configyml)
3. [Get your private credentials](#3-get-your-private-credentials)
4. [Audit — config/audit.yml](#4-audit--configaudityml)
5. [Control — config/control.yml](#5-control--configcontrolyml)
6. [Discord channels and staff](#6-discord-channels-and-staff)
7. [Verification — config/verification.yml](#7-verification--configverificationyml)
8. [Chat — config/chat.yml](#8-chat--configchatyml)
9. [OrionGuard — config/guard.yml](#9-orionguard--configguardyml)
10. [Rank sync — config/roles.yml](#10-rank-sync--configrolesyml)
11. [Network evidence — config/correlation.yml](#11-network-evidence--configcorrelationyml)
12. [Recovery — config/recovery.yml](#12-recovery--configrecoveryyml)
13. [Appearance — messages.yml and menus](#13-appearance--messagesyml-and-menus)
14. [Troubleshooting and final checklist](#14-troubleshooting-and-final-checklist)

## 1. Before you begin

- Install the matching OrionBridge JAR in the Minecraft server's plugins folder, and start Paper once. Update OrionBot to the matching branch. The integration is native to OrionBot; do not install a second OrionBridge bot extension.
- For upgrades, stop both processes, back up the entire Minecraft plugins/OrionBridge folder and OrionBot data directory (including SQLite and encryption keys), then replace the JAR. Start both again.
- Ensure you have access to the Minecraft server console and Discord Manage Server permission. Have a second independent Manage Server holder for approval tests.
- For Discord inbound chat, enable Message Content Intent in the bot application's [Developer Portal](https://discord.com/developers/applications). For member-based features, configure the Server Members Intent if required by your bot configuration. Grant bot permissions only where needed.
- If you use Bedrock, configure working Geyser and Floodgate first. If offline-mode Java players authenticate through AuthMe, configure working AuthMe first. OrionBridge cannot make an unauthenticated offline-mode username trustworthy.
- Keep every optional local feature disabled until its prerequisites are ready. Never enable verification on an occupied production server just to see what happens.

### Where are the files?

All Minecraft paths below are relative to `plugins/OrionBridge/`.

```text
OrionBridge/
├── config.yml
├── config/
│   ├── audit.yml
│   ├── control.yml
│   ├── verification.yml
│   ├── chat.yml
│   ├── guard.yml
│   ├── roles.yml
│   ├── correlation.yml
│   └── recovery.yml
├── messages.yml
└── menus/
    ├── java-dialogs.yml
    ├── java-inventory.yml
    ├── bedrock-forms.yml
    ├── dashboard.yml
    └── help.yml
```

Open these in the Pterodactyl **Files** tab. Keep the top-level section inside each category file (for example, `chat:` inside config/chat.yml). Do not paste the entire category into the root config.yml. Do not manually edit schema/theme version numbers.

An older single-file configuration migrates automatically to this structure; the original is backed up. Do not remove existing signing keys, custom profiles, sanctions or player data. If migration reports a conflict, preserve both files and follow [troubleshooting](troubleshooting.md).

### How to apply a change

Use the Pterodactyl **Console** tab, type `ob reload` (without the slash) and press Enter. In game, `/ob reload` requires orionbridge.reload; an enabled Guard additionally requires an appropriate verified profile containing orionbridge:ob-reload. Using the console is easiest and safer while setting up.

Then type `ob status` to inspect local services and check the Discord dashboard's Health field. Local Active does not prove the remote connection works. If the reload is busy, retry after the signed request or pending moderation action finishes. Invalid configuration keeps the existing runtime or attempts to restore it. If rollback fails, joins may be restricted. Do not keep retrying without correcting the error.

Presentation-only changes retain active services and leases. Other changes can briefly require fresh signed authorization, invalidate old forms/previews and discard queued chat; durable cases and Guard state remain stored. Installing a new JAR still requires a restart.

## 2. Connection — config.yml

**Goal:** get live player count, TPS and MSPT to OrionBot before setting up security.

### A. Set up the Minecraft reverse proxy

1. In your Minecraft Pterodactyl panel, find an unused TCP allocation in the Network/Allocations tab, or request one from your host. For example, 25762. Do not reuse the Minecraft game port.
2. Open your host's reverse proxy feature. Create an HTTPS origin such as https://bridge.example.com, pointing to the allocated IP and port.
3. Enable a valid publicly trusted TLS certificate. The plugin listener itself speaks plain HTTP; the proxy terminates HTTPS. Restrict direct access to the backend if your provider supports it.
4. Forward /v1/snapshot and /v2/ paths, GET and POST, with Authorization and signed request headers and unchanged body/path. Do not log secrets, put an interactive login page in front of the API, or rewrite its paths.

Pterodactyl does not universally include a reverse-proxy feature; use the actual feature supplied by your hosting provider. You only need an inbound allocation/proxy on the Minecraft host for Pull mode. No new inbound port is needed on OrionBot's host.

### B. Edit the root file

| Key | Where to get the value / what to use |
| --- | --- |
| config-version | Automatically managed. Leave 15. |
| mode | Use pull for the Minecraft-host reverse proxy and all security features. push is legacy telemetry only. |
| bind | 0.0.0.0 for a normal container allocation; 127.0.0.1 only when the proxy can reach the same loopback namespace. |
| port | Your actual allocated spare TCP port, not the example if your host assigns another. |
| endpoint | Leave empty in Pull. Only Push uses a URL generated by OrionBot. |
| token | Generated by OrionBridge on first Pull startup if blank; copy from this file. Keep any existing token. |
| interval-seconds | 5–30 seconds. Leave 10 initially. |
| hide-vanished | true hides vanished players from public telemetry listings. |
| respect-listing-preference | true respects the player's listing privacy preference. |
| skinsrestorer-fallback | true allows saved skin lookup if SkinsRestorer is installed. |

Example root config (fresh installation only; retain your actual token if already paired):

```yaml
config-version: 15
mode: pull
bind: '0.0.0.0'
port: 25762
endpoint: ''
token: ''
interval-seconds: 10
hide-vanished: true
respect-listing-preference: true
skinsrestorer-fallback: true
```

Start the plugin with the blank token only on a fresh installation, then reopen the file. It should contain a 64-character lowercase hexadecimal token. If a listener is already running, do not clear the token and reload: the current reload implementation rejects a blank replacement token. For intentional rotation, generate a secure new 32-byte random token (64 lowercase hex characters) in a trusted local environment, replace the value, reload, and update the Discord pairing immediately. Do not use a public online key generator.

### C. Pair in Discord

1. Run **/mcserver manage**.
2. Select the registered Java server (register the server first if needed).
3. Open **OrionBridge → Configure Pull**.
4. Enter your real HTTPS origin (no /v1/snapshot suffix), the token copied from config.yml, and a polling interval such as 10 seconds.
5. Save, then use the connection test/refresh and open a performance panel.

**Success:** the bridge connection works, performance values appear, and Minecraft console `ob status` reports Telemetry Active. If you already have a working Pull connection, do not replace it; proceed to the next section.

## 3. Get your private credentials

This is the important step for all other features. **Do not invent signing keys or copy your Discord bot token.**

1. In the same Discord guild, run **/orionbridge manage**.
2. Select the same Pull-paired Minecraft server. The equivalent entry is **/mcserver manage → server → OrionBridge → Identity & Security**.
3. Click **Create setup** if this server has never been configured, or **Setup credentials** if it has.
4. The private response contains separate YAML snippets for audit, control and optional correlation. Copy values into the indicated Minecraft files, not a public channel.

| Value | Get it from | Paste it into |
| --- | --- | --- |
| Pull token (64 lowercase hex) | Minecraft root config.yml, generated on first start | OrionBot Configure Pull token field |
| guild-id (17–20 decimal digits) | Setup credentials → audit snippet | config/audit.yml → audit.guild-id |
| server-id (16 lowercase hex) | Setup credentials → audit snippet | config/audit.yml → audit.server-id |
| Audit signing key (64 lowercase hex) | Setup credentials → audit snippet | config/audit.yml → audit.signing-key |
| Control signing key (64 lowercase hex) | Setup credentials → control snippet | config/control.yml → control.signing-key |
| Optional correlation guild key | Setup credentials → correlation snippet | config/correlation.yml → correlation.guild-key |
| Discord invite | Create an invite in your Discord server | config/verification.yml → verification.discord-invite |
| Discord role IDs | Discord Developer Mode → Copy Role ID | Discord Trusted Operations / Role Sync forms |
| Minecraft group/permission names | LuckPerms editor and plugin permission documentation | config/roles.yml |
| World names and coordinates | Your Minecraft server's loaded worlds and actual coordinates | config/verification.yml / config/guard.yml |

The **server-id is OrionBot's internal ID for the registered Minecraft server**, not the server IP, Pterodactyl UUID, Discord channel ID or Discord guild ID. Copy both IDs from Setup credentials so they refer to the same pairing. Keep them quoted as strings.

The audit and control keys must be distinct from each other and from the Pull token. The optional guild key is shared for the guild's network evidence, not an authentication token. Never rotate a key casually, expose it in screenshots, or copy it from a different pairing. Keep backups private.

You do not need to edit OrionBot's .env for ordinary Pull pairing or paste its Discord application token into Minecraft. Discord channels and roles are stored by the bot and configured through the dashboard.

## 4. Audit — config/audit.yml

**Goal:** report observations and evidence to OrionBot. Audit does not punish a player by itself.

Open config/audit.yml. Merge the audit snippet from Step 3 into the existing section:

```yaml
audit:
  enabled: true
  guild-id: 'COPY_GUILD_ID_FROM_SETUP'
  server-id: 'COPY_SERVER_ID_FROM_SETUP'
  signing-key: 'COPY_AUDIT_KEY_FROM_SETUP'
  block-changes-per-minute: 500
```

The uppercase placeholders are not working values. Replace them with your own copied values before applying.

| Key | Value source / effect |
| --- | --- |
| enabled | true when you want signed evidence; false leaves audit off. |
| guild-id | Exact guild ID from Step 3. Also used by control. |
| server-id | Exact paired Minecraft server ID from Step 3. Also used by control. |
| signing-key | Private audit signing key from Step 3, not the control key. |
| block-changes-per-minute | Default 500; observation threshold, not the Guard hard block limit. Supported effective range 10–100,000. |

Keep the default threshold at first. If it generates too many legitimate alerts, review the evidence and adjust based on normal gameplay.

Apply the change with `ob reload`. Finish Step 6's private channel setup, then click **Enable audit** in the OrionBot manager. Join/leave with a test account or generate a harmless observation. Open **Evidence** and check the appropriate log channel. An empty evidence list immediately after setup may simply mean nothing has happened yet.

## 5. Control — config/control.yml

**Goal:** signed bidirectional security operations, verification, chat and typed moderation.

```yaml
control:
  enabled: true
  signing-key: 'COPY_CONTROL_KEY_FROM_SETUP'
```

| Key | Value source / effect |
| --- | --- |
| enabled | true enables local signed control endpoints. Required for verification, chat and signed Guard/rank/recovery actions. |
| signing-key | Copy the control key from Step 3. Do not reuse the audit key or Pull token. |

The guild/server scope comes from config/audit.yml, even if audit collection is disabled. There are no extra ID fields to add here. Apply via console `ob reload` and confirm Moderation (local control) Active. In Discord, configure the channels and staff first, then enable the corresponding switches. Test signed service health and, after linking a test account, a short reversible case with an independent approver. Signed control is not arbitrary remote console execution.

## 6. Discord channels and staff

Open **/orionbridge manage → server → Channels**. Create ordinary Discord text channels first, then select them with the bot's channel selectors; no channel IDs belong in the plugin YAML. The second page is **More channels**.

| Selector | Example channel | Access / purpose |
| --- | --- | --- |
| Verification | #verify | Players can view the linking panel. |
| Security alerts | #security-alerts | Private staff evidence alerts. |
| Critical security alerts | #critical-alerts | Private high-urgency alerts. |
| Punishment logs | #punishment-logs | Private case information and receipts. |
| Minecraft chat | #minecraft-chat | Public or member-accessible game chat. |
| Private staff chat | #staff-chat | Private staff chat, never the public chat channel. |
| Minecraft activity | #minecraft-activity | Public/member-visible eligible activity. |
| Trusted staff activity | #staff-activity | Private staff activity. |
| Account logs | #account-logs | Private account-related records. |
| Role sync logs | #role-logs | Private rank reconciliation records. |
| Administrative audit | #admin-audit | Private administrative actions. |
| Bridge health | #bridge-health | Private operational health notices. |

The current channel picker checks that private destinations deny View Channel to @everyone. Also inspect role/user-specific overwrites: allow only intended staff and OrionBot. Public verification/chat/activity can be visible to the intended players. Grant OrionBot View Channel, Send Messages and Read Message History, plus Attach Files when evidence attachments/exports are required. For Discord-side moderation give only the required moderation permissions and correct role hierarchy.

**Staff roles:** click **Staff roles** in the manager and select the roles permitted to view evidence and request cases. This is not the same as Guard trusted profiles. Manage Server and independent approval are required for privileged case actions. Avoid giving rank-synced roles staff permissions.

Enable Audit and, if needed, Moderation. Keep services resumed. Never assume a Discord action was applied in Minecraft merely because a request was approved; check the receipt.

## 7. Verification — config/verification.yml

**Goal:** link Java and Bedrock accounts to Discord. This feature is mandatory for the current chat relay and trusted staff/rank integration. Once both local switches are true, unverified users (including OPs) are restricted. If OrionBot stops renewing authorization, gameplay becomes restricted again.

### A. Prepare Discord first

1. Assign the Verification channel in Step 6.
2. In the manager click **Enable verification**. Then click **Publish verification**.
3. Under **Accounts**, review the minimum Discord account age and relink policy if needed. Configure a real Discord invite using the server's invite feature. Prefer a valid long-lived invite where your server policy permits it.
4. Ensure signed control is working, clocks on both hosts are synchronized, and you have console access. Test on a staging instance first.

### B. Pick the right Java identity settings

| Your Minecraft setup | authme-realm |
| --- | --- |
| Online-mode Java with normal authentication | Leave '' (empty). |
| Offline-mode Java using a working trusted AuthMe database | Choose a stable lowercase realm such as 'my-smp'; 1–32 lowercase letters/digits/underscore/hyphen. |
| Bedrock through working Floodgate | Floodgate identifies Bedrock automatically; set the realm only if offline Java also uses AuthMe. |
| Offline-mode Java without a supported authenticated provider | Do not enable verification; arbitrary usernames are not authoritative identities. |

The AuthMe realm is your chosen identity namespace, **not a password, database URL or secret**. Use the same realm only across servers sharing the same trusted authentication database and account mapping. Changing it after accounts are linked changes their identity namespace and may require recovery/relinking. For mixed offline Java and Bedrock, test both paths separately. Do not infer identity from a Floodgate username prefix.

### C. Edit the file

```yaml
verification:
  enabled: true
  setup-complete: true
  java-interface: DIALOG
  discord-invite: 'https://discord.gg/REPLACE_WITH_YOUR_INVITE'
  lease-seconds: 60
  reopen-seconds: 15
  authme-realm: ''
  lobby:
    enabled: false
    world: ''
```

This example assumes online-mode Java or Floodgate; change authme-realm as explained above for authenticated offline Java.

| Key | Where to get it / effect |
| --- | --- |
| enabled | Set true only when ready to enforce verification. |
| setup-complete | Manual confirmation of completed setup; must also be true to activate the gate. |
| java-interface | Choose DIALOG for native Java dialogs or INVENTORY for chest GUI. Bedrock forms selected automatically when supported. |
| discord-invite | Copy your actual Discord invite. No bot OAuth invite. Empty means no invite URL supplied. |
| lease-seconds | 15–60; default 60. Signed authorization lifetime, not code validity. Shorter means faster fail-closed behavior and more renewals. |
| reopen-seconds | 0–300; default 15. How often restricted players' verification interface can reopen. 0 disables automatic reopening, not enforcement. |
| authme-realm | See table above. |
| lobby.enabled | Optional; leave false while initially testing. |
| lobby.world | Exact name of a loaded world with a safe spawn. Leave empty when lobby disabled. |

Do not turn on the lobby until normal linking works. For the lobby, choose a safe already-loaded world name, e.g. 'verification_lobby' only if that world really exists. It stores the original position under verification-return/ and tries to restore it after verification. Preserve return records; no inventory changes. A verified player can retry with /ob return if the destination is safe and loaded.

### D. Test verification

From console, run `ob reload`. Join as an ordinary test player. AuthMe players authenticate first, then run `/ob link`. Open Discord's published verification panel, click **Link Account**, enter the private code and return to Minecraft to confirm the displayed Discord account. The challenge lasts five minutes and can survive a normal disconnect. Never give a code to another player. Test Java, Bedrock and AuthMe as applicable. Then test a linked staff account and ensure permissions are correct.

**Emergency:** console `ob verification-off` temporarily releases the verification gate (or startup fallback). Correct the configuration first; reload or restart can re-enable the gate. This is not account linking and does not remove other sanctions. Do not leave a mandatory verification gate enabled on the plugin while disabling the bot's verification switch, because leases may expire and restrict players.

## 8. Chat — config/chat.yml

**Goal:** relay Minecraft Java/Bedrock chat to Discord and linked Discord chat back to verified Minecraft players.

```yaml
chat:
  enabled: true
  minecraft-to-discord: true
  discord-to-minecraft: true
  staff-chat: false
```

| Key | Where to get it / effect |
| --- | --- |
| enabled | Set true after Pull, signed control and verification work. |
| minecraft-to-discord | true relays eligible public Minecraft chat to Discord; false disables that outbound public relay. |
| discord-to-minecraft | true relays eligible linked Discord members' chat to verified online Minecraft players; false disables inbound relay. |
| staff-chat | Optional separate private chat; leave false until trusted profiles and private channels work. |

In the Discord Developer Portal, enable Message Content Intent for OrionBot. Restart the bot if the intent/configuration was changed. Under Channels select Minecraft chat; click **Enable chat**. Run console `ob reload`.

Test with a verified Java or Bedrock player and a linked Discord account. Minecraft messages appear in Discord as MC · Player, and Discord messages in Minecraft use the format in messages.yml → chat.discord. The Discord-side prefix is currently controlled by OrionBot's code, not Minecraft YAML. Unlinked senders, bots and webhooks are ignored, and supported mutes restrict relay. Discord mentions are suppressed; the relay is not a permanent transcript and queued chat can expire.

Optional staff chat: complete Step 9 first, enable staff-chat locally, assign a distinct **Private staff chat** channel (deny @everyone View Channel), and verify the trusted profile has staff-chat: true. Use `/ob staffchat Hello staff!` in game. Test both directions with a verified authorized staff account, and confirm ordinary players cannot see it. Use the direction switches to control relay; local staff chat is also tied to staff authorization.

## 9. OrionGuard — config/guard.yml

**Optional.** Do not enable until verification, signed control, console recovery and a tested staff account work. Guard is a local safety layer, not a substitute for LuckPerms, WorldGuard, backups or a full server-side security review. Arbitrary plugin/API writes and alternate untested WorldEdit/FAWE paths may bypass the supported hooks.

### A. Base settings

Keep the packaged default profiles/rules. Start with enforcement off, strict-command-allowlist off, non-player edits off and automatic join lockdown off. On a private staging server enable Guard, reload, and set the Discord trusted mappings in Step B. Ordinary staff may be blocked until their profile is mapped and renewed.

| Key under guard | Default | How to choose / effect |
| --- | --- | --- |
| enabled | false | true activates local Guard in Pull mode. |
| block-sensitive-commands | true | Restricts known sensitive operations unless authorized. |
| strict-command-allowlist | false | Optional stricter policy; unlisted ordinary commands need authorization. Keep false initially. |
| ordinary-commands | [] | Exact resolved owner:command names for strict mode, from the real installed command owner, not arbitrary aliases. |
| enforce-worldedit | true | Requires supported guarded edit hook; unavailable hook can deny edit commands. |
| allow-non-player-edits | false | Allow bounded supported non-player edits only when deliberately needed. |
| non-player-per-operation | 10000 | Effective 0–100,000 attempts per operation. |
| non-player-per-minute | 20000 | Effective 0–200,000 shared attempts/minute. |
| block-changes-per-minute | 500 | Effective 10–100,000 ordinary block attempts/minute. |
| container-items-per-minute | 4096 | Effective 64–100,000 interaction threshold; not proof of theft. |
| entities-per-10-seconds | 500 | Effective 10–10,000 creature spawn observation threshold. |
| joins-per-10-seconds | 20 | Effective 3–1,000 join observation threshold. |
| join-lockdown-seconds | 0 | 0 = no default automatic lockdown; up to 3,600 seconds. Explicit JOIN_SPIKE response/duration overrides the default. |
| containment-seconds | 60 | Default rule restriction duration, 5–3,600 seconds. |
| protected-worlds | [] | Exact names of worlds where guarded edits are denied. |
| protected-regions | [] | Up to 32 inclusive cuboids, using actual world names and coordinates. |
| rules | See below | Additional alert or containment policy. |
| profiles | See below | Local named staff profiles, not permission grants. |

Use actual server measurements to adjust limits. Start with defaults; a high limit is not necessarily safer. Attempted changes can consume budgets even when not completed.

### B. Staff profiles and Discord mapping

The packaged local profiles are builder and administrator. The profile name comes from the YAML key below guard.profiles, not from a Discord role name. Names use 1–40 lowercase letters/digits/underscore/hyphen. You may add a narrowly scoped custom profile.

| Profile key | What it does |
| --- | --- |
| commands | Explicit sensitive commands allowed for that profile. Use resolved owner:command names, e.g. minecraft:gamemode or orionbridge:ob-reload. Does not grant actual Bukkit permission. |
| worldedit-per-operation | Attempt ceiling per guarded edit; 0–2,000,000. |
| worldedit-per-minute | Attempt ceiling/minute; 0–4,000,000. |
| blocks-per-minute | Ordinary block attempt limit, 0–100,000. |
| staff-chat | Allow authorized profile to use the private relay if enabled. |
| lockdown-bypass | Allow this verified profile to bypass Guard lockdown, not mandatory verification or other sanctions. Required by the current admin GUI alongside its permission. |
| entity-tasks | Allow supported guarded entity creation; not arbitrary removal. |
| worldedit-scope.worlds | Exact allowed world names. Empty means no extra world restriction. |
| worldedit-scope.regions | Inclusive allowed cuboids. Empty means no extra region restriction; global protected regions still win. |
| worldedit-scope.operations | BLOCK, BIOME, ENTITY. Empty list denies all extent mutations. |
| worldedit-scope.commands | Exact supported edit command names. Temporary tasks require a nonempty explicit allowlist. |

Example of adding a limited builder profile (merge beneath existing guard.profiles; do not replace the whole file):

```yaml
guard:
  profiles:
    event_builder:
      commands: []
      worldedit-per-operation: 5000
      worldedit-per-minute: 10000
      blocks-per-minute: 1000
      staff-chat: false
      lockdown-bypass: false
      entity-tasks: false
      worldedit-scope:
        worlds: ['event_world']
        regions: []
        operations: [BLOCK]
        commands: ['worldedit:set']
```

Replace event_world with a real world and confirm the resolved edit command name in your installation. A task cannot use arbitrary direct/admin edit commands even if listed.

Get the Discord role IDs: enable Developer Mode in Discord User Settings → Advanced, then right-click the actual role in Server Settings → Roles and choose **Copy Role ID** (on mobile use the role menu). The role ID is a decimal snowflake string. Do not use a role name or user ID.

With Guard initialized, open **/orionbridge manage → server → Trusted Operations → edit mappings**. The form expects one role ID=local profile name per line. For example (fictional IDs; replace both):

```text
111111111111111111=builder
222222222222222222=administrator
```

Temporary profile names field: `none` to disable temporary grants, or comma-separated exact local profile names such as `event_builder`. Do not map the same player to conflicting profiles. Trusted mapping is not the same as the Staff roles list or LuckPerms rank sync. Wait for signed authorization renewal and test with the linked staff member.

Grant underlying Minecraft permissions separately using your permission manager. For the GUI, grant orionbridge.admin.gui only to intended staff and map a verified profile with lockdown-bypass: true. For in-game reload grant orionbridge.reload and explicitly allow orionbridge:ob-reload in the trusted profile; console is always the recovery path.

Temporary edit tasks use Trusted Operations: enter the linked recipient's actual Discord user ID, an allowlisted local profile name, 1–60 minutes, exact world, and operation plus total attempt budget (e.g. BLOCK 5000). A different administrator must approve, and neither administrator may be the recipient. Tasks do not grant other staff privileges. Test supported WorldEdit commands and budget exhaustion on disposable blocks.

### C. Protected regions

Find exact world names in the server's world configuration or world manager, and coordinates using Java F3, Bedrock coordinate display or the server's location tools. Use inclusive lower/upper corners; do not use arbitrary world names. Example (adapt to your server):

```yaml
guard:
  protected-worlds: ['spawn']
  protected-regions:
    - world: world
      x1: -50
      y1: -64
      z1: -50
      x2: 50
      y2: 319
      z2: 50
```

The same region format applies to a profile's worldedit-scope.regions. The global protected areas always override trusted profile scopes. These guardrails protect supported edit paths, not every possible plugin operation.

### D. Rule configuration

| Rule key | What triggers it | Packaged response |
| --- | --- | --- |
| GUARD_COMMAND_BLOCKED | Blocked sensitive command | CRITICAL / ALERT |
| WORLDEDIT_BLOCKED | Blocked guarded edit | CRITICAL / ALERT |
| GUARD_BLOCK_LIMIT | Block attempt limit exceeded | HIGH / QUARANTINE |
| CONTAINER_ACTIVITY | Container threshold | MEDIUM / ALERT |
| JOIN_SPIKE | Join threshold | HIGH / ALERT unless configured for lockdown |
| ENTITY_SPIKE | Creature spawn threshold | MEDIUM / ALERT |
| EXPLOSION_ACTIVITY | Explosion observation | HIGH / ALERT |

Each rule accepts enabled (true/false), severity (LOW, MEDIUM, HIGH, CRITICAL), response (ALERT, BUILD_RESTRICTION, WORLDEDIT_RESTRICTION, COMMAND_RESTRICTION, FREEZE, QUARANTINE; LOCKDOWN only for JOIN_SPIKE), optional duration-seconds (5–3,600) and cooldown-seconds (1–3,600, default 5). JOIN_SPIKE and ENTITY_SPIKE do not identify a player for individual restrictions. Severity is an alert classification, not proof of guilt. Disabling a rule does not disable independent hard limits.

For initial testing, prefer alert-only rules and leave join-lockdown-seconds: 0. Note that GUARD_BLOCK_LIMIT is quarantine by default: explicitly set its response to ALERT if you want to test without that additional containment. Apply via `ob reload`. Verify an ordinary player cannot perform an unauthorized sensitive action, a trusted user can perform only approved operations, and lockdown can be cleared from console using `ob lockdown off`. Never treat a shared address or an alert alone as guilt.

## 10. Rank sync — config/roles.yml

**Optional; requires LuckPerms, signed control and verified linked accounts.** Roles are not trusted staff profiles. Do not synchronize OP or administrative groups.

| Key | Where to get it / effect |
| --- | --- |
| enabled | true activates local LuckPerms integration. |
| offline-reconciliation | true permits bounded lookup of previously verified supported offline identities; default true. |
| offline-observed-identities | false by default. Enable only if AuthMe/Floodgate UUID mapping is stable, with Observed ranks in Discord too. |
| allowed-groups | Exact existing LuckPerms group names you intentionally permit the bridge to grant. Empty means no groups granted. |
| allowed-permissions | Exact safe positive permission nodes required by those groups (including inheritance/context nodes as relevant). Empty can reject permission-bearing groups. No wildcards or administrative nodes. |

To find group names, use LuckPerms `/lp listgroups`; inspect `/lp group <group> permission info` and inheritance using the LuckPerms editor (`/lp editor`) and group information. Choose only harmless rank groups. Do not just paste every permission into the allowlist. Review inherited groups; unknown, wildcard, LuckPerms admin, Minecraft/Bukkit command, WorldEdit/FAWE and OrionBridge permission nodes are rejected by the grant safety check.

Example only, replace with actual safe existing names and nodes:

```yaml
roles:
  enabled: true
  offline-reconciliation: true
  offline-observed-identities: false
  allowed-groups: ['vip']
  allowed-permissions: ['yourcosmetics.vip']
```

Use actual permission nodes documented by your installed plugins; yourcosmetics.vip is illustrative, not a real universal permission. If you only mirror Minecraft rank to Discord, you still need the local rank integration enabled, but the local grant allowlists protect grants into Minecraft rather than granting access to Discord.

In Discord create the cosmetic rank/verified roles and place them below OrionBot's highest role. The bot must have Manage Roles. Managed roles must have **zero guild-level permission bits**, be editable, not be integration-managed, and must not serve as trusted/staff roles. Check channel permission overwrites as well.

Go to **/orionbridge manage → server → Role Sync → Edit mappings**. The Verified role ID field accepts an actual Discord role ID or `none`. The mappings field expects one line per mapping:

```text
vip 333333333333333333 MC_TO_DISCORD
cosmetic 444444444444444444 DISCORD_TO_MC
```

Replace IDs and groups with your own. MC_TO_DISCORD mirrors the Minecraft primary group to a Discord rank role. DISCORD_TO_MC allows the mapped Discord role to confer a safe temporary transient LuckPerms group; the local allowlists must permit it. Choose one direction for each role/group pair rather than building a feedback loop. Use none if there are no mappings. Save and click **Enable** in Role Sync. Apply local config via console reload.

Test granting/removing a rank and confirm unrelated manual grants remain untouched. Offline reconciliation has limited identity coverage: canonical Java and appropriate Floodgate identities may be queried; observed noncanonical/AuthMe UUID mappings are optional, scoped, expire after 24 hours, and may be unknown after external account remapping. Unknown results should not automatically revoke previously owned ranks. Do not enable observed ranks on unstable identity mappings.

## 11. Network evidence — config/correlation.yml

**Optional. Leave disabled unless you have a real need, a valid privacy policy and trustworthy real client IP forwarding.**

```yaml
correlation:
  enabled: false
  confirm-real-client-addresses: false
  guild-key: ''
```

| Key | Where to get it / effect |
| --- | --- |
| enabled | true opts in to network correlation evidence. Requires audit for collection. |
| confirm-real-client-addresses | true only after confirming Paper sees actual authenticated client addresses, not the same proxy address for everyone. |
| guild-key | Copy the guild-key from the optional correlation snippet in Setup credentials. Keep it private and stable. |

You may copy the provided key while leaving both switches false. Do not generate a different guild key per server if correlation across that guild is intended. On shared proxy or uncertain forwarding, keep this disabled. Never log or expose players' IPs just to troubleshoot. Compare account evidence using **Compare accounts** in the private manager if intentionally configured. Fingerprints are a pseudonymous clue, not proof of common ownership; shared networks and carrier NAT can produce legitimate matches. This feature must not automatically sanction people.

## 12. Recovery — config/recovery.yml

**Optional.** Install and verify a compatible CoreProtect API and use a backup/staging world. Signed control is required. Local Guard lockdown and independent Discord approval are required for actual rollback.

```yaml
recovery:
  enabled: false
  allow-rollback: false
  maximum-records: 500
  maximum-radius: 32
  maximum-seconds: 900
```

| Key | Where to get it / effect |
| --- | --- |
| enabled | true enables supported bounded CoreProtect lookups; requires compatible CoreProtect. |
| allow-rollback | false = preview only. true opts in to controlled rollback after approval and safety checks. |
| maximum-records | 10–1,000 effective cap; leave 500. |
| maximum-radius | 1–64 blocks effective cap; leave 32. |
| maximum-seconds | 60–3,600 seconds effective cap; 900 = 15 minutes. |

Start with enabled: true and allow-rollback: false only if you installed CoreProtect. Reload. In Discord open **Recovery**. The preview form uses the target player's actual name, loaded world name, X/Z coordinates, radius, and lookback seconds. These come from the incident and your server, not from the bot setup card. The target must be offline for execution, and the fresh preview must match the approved data. Do not interpret a submitted rollback as proof of restoration. Inspect the world and compare backups. Do not turn on actual rollback until the acceptance test passes.

## 13. Appearance — messages.yml and menus

No new secret values are required. These files contain labels, formatting, icons and sizes. All use MiniMessage; see [MiniMessage syntax](https://docs.advntr.dev/minimessage/format.html). Use single quotes for YAML strings containing color tags or colons, and preserve existing dynamic placeholders. Actual command identifiers, authorization, protocol values and native client controls are not changed by a theme.

### messages.yml

| Section | What to edit |
| --- | --- |
| commands | Command replies, errors, reload result, status header/rows/footer. |
| services | Display names for services in /ob status. |
| verification | Authentication hints, return messages, confirmation/link/copy-code prompts. |
| guard | Guard denial warnings. |
| moderation | Notices and kick/ban text. Keep reason and case_id placeholders. |
| chat | In-game staff-local, discord and discord-staff formats. |
| console | Startup banner, section names, labels and status colors. Runtime facts still come from the implementation. |

Example (merge only these keys into the existing chat section of messages.yml):

```yaml
chat:
  discord: '<#b48cff>[Discord]</#b48cff> <white><name></white><gray>:</gray> <message>'
```

name/message are provided by the relay. Do not replace them with actual player names or a private token. Save and reload.

### menus/java-dialogs.yml

This is **Java verification before gameplay**, not the staff dashboard.

| Key | How to choose |
| --- | --- |
| theme-version | Managed by the plugin. |
| title | MiniMessage verification window title. |
| screens.welcome/authenticate/waiting/code/confirm/pending/verified | Wording for each step. Keep code, discord, discord_id and invite placeholders as needed. |
| buttons.link/refresh/regenerate/confirm/close.name | Captions; action remains fixed. |
| buttons.*.tooltip | Optional hover text; blank disables it. |
| columns | 1–2. |
| button-width | 50–300. |

Legacy material/image fields in this file are retained for compatibility, but do not change a native Java dialog button into a Minecraft item.

### menus/java-inventory.yml

Java verification fallback or INVENTORY mode. The shared title, screens and buttons fields are the same verification steps as above. buttons.*.material, information-material and filler-material use actual Bukkit material enum names (e.g. BOOK, EMERALD, BLACK_STAINED_GLASS_PANE). Invalid materials fall back. Set size to 27–54 in multiples of nine; information-slot, primary-slot and secondary-slot are zero-based inventory slots. The implementation keeps these within the usable area and avoids collisions. filler-name controls filler item text; italic-items controls item italics. Image fields are not used for Java inventory.

### menus/bedrock-forms.yml

Bedrock verification forms. Change title, screens and buttons.*.name; buttons.*.image is an optional real Bedrock texture path (e.g. textures/items/emerald), or '' for no image. Material is not used by Bedrock forms. Text is rendered through legacy-compatible formatting; exact Java gradients/fonts may differ on Bedrock. Test with a real Bedrock client.

### menus/dashboard.yml

This controls the **post-verification** account and staff interfaces across Java dialogs, inventory and Bedrock forms.

| Key | What it controls |
| --- | --- |
| theme-version | Managed automatically. |
| title-prefix | Prefix for menu titles. |
| labels | Button captions and sanction display labels. labels.player uses name. |
| headings | Titles of the account, report, appeal, player list, punishment request and diagnostics screens. |
| materials | Java inventory item materials keyed by button label name. |
| images | Optional Bedrock image paths keyed by button name. |
| tooltips | Optional Java button hover text keyed by button name. |
| states | Display words for active, inactive, none, unavailable. |
| layout.dialog-columns | 1–2 Java dialog columns. |
| layout.dialog-button-width | 50–300 Java button width. |
| layout.input-width / input-button-width | 50–300 input/button width. |
| layout.input-placeholder | Optional Bedrock input hint. |
| layout.italic-items | Java inventory item italic style. |
| layout.inventory-rows | 3–6; inventory can grow to fit actions. |
| layout.information-slot / button-slots | Zero-based slot numbers; empty button-slots uses automatic placement. |
| bodies | Screen content templates; retain existing placeholders such as identity, discord, group, reason, count, online, current, total, details. |
| inputs | Report, identity search and punishment request prompt text. |
| messages | Feedback for those menu actions. |

Change displayed words or colors, not the internal keys. Use the generated defaults as examples. Keep dynamic identity, private code and case information accurate. Avoid adding public click/hover text that reveals private data.

### menus/help.yml

| Key | Effect |
| --- | --- |
| theme-version | Managed by plugin. |
| header / footer | Top/bottom text and separators. |
| entry | Row template using usage and description. |
| click-to-suggest | Java click-to-suggest command toggle. |
| hover-hint | Java hover help toggle. |
| hover | Template using command. |
| commands.<subcommand>.usage | Displayed syntax. |
| commands.<subcommand>.description | Displayed explanation. |

Example:

```yaml
entry: ' <light_purple>› <usage></light_purple> <dark_gray>—</dark_gray> <gray><description></gray>'
```

Use /ob help and /ob help chat-related commands (for example /ob help staffchat) to test; permission and feature checks determine which real commands are shown. Command names themselves cannot be renamed here.

Use `ob reload` after changing themes. Discord verification panel appearance is separate: **Panel appearance** in OrionBot configures title, accent, intro/footer, and supported labels. Preview and republish the panel to apply changes. Discord-native controls and the Discord-side relay prefix are not controlled by these Minecraft files.

## 14. Troubleshooting and final checklist

| Symptom | What to check first |
| --- | --- |
| Connection unavailable / 502 | Correct allocation, bind, proxy target, public TLS and path forwarding. Check the listener and use Configure Pull test. |
| 401 / invalid signature | Check the original pairing token, exact guild/server IDs, separate signing keys, signed headers and both host clocks. Do not replace working keys at random. |
| 404 on a v2 path | Correct Pull pairing, local feature enabled, signed control/audit initialized, matching bot/plugin versions and unmodified proxy path. |
| Local status Active but Discord not working | Bot feature switches, paused state, paired server, health, channel permissions and polling. Local status alone is not remote confirmation. |
| Cannot publish verification | Set Verification channel, enable verification on OrionBot, ensure services are resumed and the bot can send there. |
| Everyone restricted | Check signed control and verification polling, AuthMe/Floodgate identity, both switches and lease renewal. Emergency console ob verification-off; correct settings before reload. |
| Discord chat doesn't reach Minecraft | Both chat switches, linked sender, verified recipients, correct channel, bot verification enabled, Message Content Intent and mute status. |
| Minecraft chat doesn't reach Discord | Local outbound flag, verified sender, bot chat enabled, selected channel, send permissions and local mute status. |
| Private channel rejected | Deny View Channel for @everyone; verify overwrites and use a normal text channel in the correct guild. |
| Trusted mappings fail | Guard must be initialized so bot can read actual local profiles; check the profile names and role IDs. |
| Rank not granted | LuckPerms installed, role hierarchy, zero guild permissions, exact group, safe permission allowlist and direction. |
| Reload rejected / busy | Inspect console, correct YAML types or dependency errors, or retry when work is idle. Existing runtime may still be active. |
| Menus appear old | Edit the correct edition-specific file or dashboard.yml; reload, reopen the screen, and use matching JAR. Discord panel changes require republishing. |

For a setup limited to verification and chat, you only need the root connection, audit scope/keys, control, verification and chat plus Discord channels/switches. Guard is only necessary for its enforcement and trusted staff chat. LuckPerms rank sync, correlation, CoreProtect and the verification lobby can stay off. Audit collection is recommended for evidence; its scope IDs are required by control even when collection is off.

- [ ] Matching JAR and OrionBot running; full backups retained.
- [ ] Pull HTTPS test and performance values work.
- [ ] Correct IDs and distinct private keys copied from Setup credentials.
- [ ] Audit/control and remote health tested; channels and staff permissions reviewed.
- [ ] Console emergency recovery tested on staging.
- [ ] Java/Bedrock/AuthMe linking tested as applicable.
- [ ] Chat tested both directions; no public leakage of staff chat.
- [ ] Optional Guard, role sync, network evidence and recovery tested only if wanted.
- [ ] Reopen menus after reload; inspect help and permission-aware completion.
- [ ] Complete the [live acceptance checklist](acceptance.md) and [load tests](load-testing.md) before production enforcement.

**More help:** [Configuration reference](configuration.md) · [Commands and permissions](commands.md) · [Administration](administration.md) · [Troubleshooting](troubleshooting.md) · [OrionBot dashboard guide](https://github.com/engrkarlo/OrionBot/blob/orionbridge-2.0-completion/docs/orionbridge.md).
