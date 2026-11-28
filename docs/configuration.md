# Configuration reference — v9

The bundled config.yml is the authoritative list of keys. Missing fields migrate with existing values preserved. Restart Paper after changing anything except telemetry connection settings. Keep backups private; they may contain credentials.

| Section | Fields and behavior |
| --- | --- |
| Root | mode (pull/push), bind, port, endpoint, token, interval-seconds; hide-vanished, respect-listing-preference, skinsrestorer-fallback. Keep existing token. |
| audit | enabled, guild-id, server-id, signing-key, block-changes-per-minute. Separate key from telemetry token. |
| control | enabled, signing-key. Typed signed actions only; uses audit scope. |
| verification | enabled, setup-complete, java-interface (DIALOG or INVENTORY), discord-invite, lease-seconds, reopen-seconds, authme-realm. Empty AuthMe realm does not authorize offline Java identity. Use same realm only for the same trusted account database. |
| correlation | enabled, confirm-real-client-addresses, guild-key. Enable only when real authenticated addresses are available. Never use shared proxy addresses. |
| guard | enabled, block-sensitive-commands, enforce-worldedit, allow-non-player-edits, block-changes-per-minute, container-items-per-minute, entities-per-10-seconds, joins-per-10-seconds, join-lockdown-seconds, containment-seconds, protected-worlds. Zero join lockdown disables that automatic lockdown. |
| guard.profiles | commands (exact resolved names), worldedit-per-operation, worldedit-per-minute, blocks-per-minute, staff-chat, lockdown-bypass, entity-tasks. Profiles grant no Bukkit permissions. |
| roles | enabled, allowed-groups, allowed-permissions. Exact allowlists; dangerous, unknown and wildcard permissions refused. |
| chat | enabled, minecraft-to-discord, discord-to-minecraft, staff-chat. Configure matching channels in OrionBot. |
| recovery | enabled, allow-rollback (false by default), maximum-records, maximum-radius, maximum-seconds. Bounded previews; execution separately opted in. |

The verification UI has a configurable interface; dashboard theme values reside in menus/dashboard.yml. Use the packaged example. Not every UI label is configurable in this preview.

## Data and transport
Retain config.yml, the entire audit and sanctions directories, and menu files. Back up OrionBot SQLite and encryption keys together. Keys are scoped to guild/server. Security endpoints are available through Pull: events, acknowledge, actions, verification and batch, policy, chat, recovery and health. Legacy /v1/snapshot remains compatible. No arbitrary remote console commands. Configure HTTPS, request size/rate limits and restrict plaintext backend access.

## Guard deployment
Start with enforcement disabled. Test with a linked administrator and ordinary player; allow only exact necessary commands. Protected worlds are hard-deny for guarded edits. Edit limits are attempted changes, not a guarantee of successful block counts. Non-player edits and alternate plugin/API paths need separate review. Lockdown is not a substitute for backups or server permissions.
