# OrionBridge alpha.6 — operator manual

## Installation
Paper 26.3 and Java 25; matching native OrionBot alpha.6 development branch (Node 22). Install one OrionBridge JAR and retain the entire existing plugins/OrionBridge directory. Stop the server for upgrades. Back up plugin data, OrionBot data, and both encryption/signing keys. Do not delete the database or existing token. On startup config v9 adds missing fields and makes a backup; security changes require a full restart.

## Pull pairing
Use an allocated Minecraft-host port and an HTTPS reverse proxy with a valid public certificate. Configure mode: pull, bind and allocated port. Copy the generated existing token privately into /mcserver manage > server > OrionBridge > Configure Pull. Keep the existing pairing on upgrade. Allow signed v2 GET/POST requests through the same proxy. No inbound port or proxy on the bot host. Push remains legacy telemetry only.

## Enable services in order
1. Verify telemetry first. In Discord choose Identity & Security > setup; copy the server/guild IDs and private signing keys into audit and control configuration. Never expose the keys. Enable those sections and restart Paper.
2. Configure private security/punishment channels, staff roles and verification channel. Test evidence and a short reversible sanction. Enable verification only once the correct authenticated identity provider and an independent staff recovery path are tested.
3. Publish the verification panel. In Minecraft link with a short-lived private code; confirm the Discord identity in Minecraft. One account per guild per identity. No OP bypass.
4. Configure optional Guard profiles on Minecraft, map the matching Discord roles in Trusted Operations, and test limits on disposable blocks. Staff profiles relax only explicitly configured checks; they do not grant Bukkit permissions.
5. For LuckPerms role sync, first allow the exact harmless group and permission names locally, then map groups/roles in Discord. Test that manual grants remain. Discord managed roles must have no guild permissions and be below the bot.
6. Enable chat locally and in the bot, with a private staff channel for staff chat. Confirm Message Content intent and channel access. Never route private staff chat to a public channel.
7. CoreProtect recovery: enable lookup first. Execution is separately opt-in, requires lockdown, an offline target, a fresh unchanged preview, and independent Discord approval. Test on a copy of the world. A submitted rollback is not proof of restoration.

## Minecraft commands and permissions

| Command | Access / behavior |
| --- | --- |
| /ob, /ob account | Player account/verification UI |
| /ob link | Start or resume linking |
| /ob roles | Linked player's account/rank view |
| /ob punishments | Current local sanctions |
| /ob report, /ob appeal | Submit a staff-review request; not an automatic appeal decision |
| /ob admin | Verified staff with orionbridge.admin.gui and trusted lockdown-bypass profile |
| /ob staffchat <message> | Verified authorized staff profile and enabled private relay |
| /ob status | orionbridge.admin (operator by default) |
| /ob reload | orionbridge.reload (operator by default); telemetry only |
| ob lockdown <minutes or off> | Console; emergency lockdown |
| ob verification-off | Console; temporarily disable gate until restart |

Permissions: orionbridge.hide excludes player from telemetry/activity listings (false); orionbridge.reload (op); orionbridge.admin (op); orionbridge.admin.gui (false). Ordinary verification and reporting do not require special permission nodes. Always keep console access.

## Discord operation
Use /mcserver manage > select Java server > OrionBridge > Identity & Security. Staff roles may inspect evidence and request cases; Manage Server is required for configuration, approvals, recovery and policy changes. Discord sanctions additionally check appropriate platform permissions and role hierarchy. A different authorized staff member approves punishment, account recovery, temporary trusted access and rollback.

Account panel: link, view account, help. Accounts: recent links, policy, unlink/recovery approvals. Compare accounts: HMAC network overlap only, not common ownership. Trusted Operations: map roles to named local profiles; request and approve temporary access. Roles: verified role and direction-specific rank mappings. Evidence: observations, related timeline, checksums, notes, acknowledge/dismiss, export. Cases: create/request, approve, revoke, local duration adjustment. Recovery: CoreProtect preview and approval. Performance: measured task diagnostics, not a full profiler.

Case states distinguish pending approval, delivery, and platform receipts. A Discord ban is removed only if it still carries the bridge's ownership marker. Existing unrelated bans/timeouts are preserved. A kick/warning cannot be undone. Offline and unsupported operations may remain pending, fail or be uncertain. Inspect receipts; never infer enforcement from a request.

## Troubleshooting
401: check existing token. Clock/signature error: sync clocks and verify guild/server keys. 404: check matching versions and proxy routes. Lease expiry: check bot health and polling, use console recovery if necessary. Missing WorldEdit/FAWE/LP/CoreProtect: check startup logs and supported APIs. A failed or missing hook is not proof of protection. Never use a live raid or production rollback for testing.
