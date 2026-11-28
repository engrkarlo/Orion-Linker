# Configuration reference — v10

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

## Rule responses (config v10)

For each of GUARD_COMMAND_BLOCKED, WORLDEDIT_BLOCKED, GUARD_BLOCK_LIMIT, CONTAINER_ACTIVITY, JOIN_SPIKE, ENTITY_SPIKE and EXPLOSION_ACTIVITY, set guard.rules.<KIND>.enabled, severity (LOW/MEDIUM/HIGH/CRITICAL), response, duration-seconds (5–3600) and cooldown-seconds (1–3600). Responses: ALERT, BUILD_RESTRICTION, WORLDEDIT_RESTRICTION, COMMAND_RESTRICTION, FREEZE, QUARANTINE; LOCKDOWN only for JOIN_SPIKE. Unattributed entity events are alert-only. Unknown actors are never assigned individual containment. Rule switches control additional response and evidence, not the independent hard command/edit/block budgets. Defaults: suspicious commands/edits and observational rules alert; block-budget violation quarantines; JOIN_SPIKE inherits the existing join-lockdown-seconds setting (0 = alert only). Omitted durations inherit containment-seconds or join-lockdown-seconds. These short local restrictions are session-process state, not durable moderation cases, and a restart clears them. Use a centrally approved durable case for persistence.

Example:
```yaml
guard:
  rules:
    WORLDEDIT_BLOCKED:
      enabled: true
      severity: CRITICAL
      response: WORLDEDIT_RESTRICTION
      duration-seconds: 900
      cooldown-seconds: 30
```

When Guard is enabled, in-game /ob reload requires the orionbridge.reload permission and a verified profile explicitly listing orionbridge:ob-reload. Console reload is unaffected. All other security configuration changes need a full restart.

roles.offline-reconciliation (default true when roles.enabled is true) enables signed POST /v2/ranks. The plugin accepts at most four canonical Java UUIDs per request and at most four storage loads concurrently, waits at most two seconds, returns only available primary groups, and does not grant any permissions. The matching bot queries previously verified matching Java UUIDs, not arbitrary usernames. Bedrock/AuthMe rank changes reconcile on authenticated login; do not infer offline identity from names.
