# Administration

[Documentation index](README.md) · [Installation](installation.md) · [Commands](commands.md)

## Verification and recovery

AuthMe authentication comes first for offline Java identities. OrionBridge does not open verification menus while the identity is unauthenticated. Gameplay stays restricted until both authentication and Discord linking succeed. During a normal client disconnect the bounded in-memory cache retains the challenge for the original five-minute lifetime, not the authorization lease or confirmation. Rejoin the same server using the same authenticated identity/UUID; the bot delivers the current Discord claimant again. Confirm in the new session. After a process restart or cache eviction the matching bot can restore an unexpired challenge to a newly authenticated session. Old pre-upgrade random codes cannot be reconstructed by the bot. An explicit regeneration cannot be replaced by a stale restored challenge. AuthMe teleport-to-spawn/restore-location behavior must be tested with the mandatory teleport restriction; no generic plugin teleport bypass is granted.

### Optional verification lobby

The default is to freeze players in place. For a dedicated safe lobby world, set `verification.lobby.enabled: true` and `verification.lobby.world` to its exact loaded name. After authentication, OrionBridge attempts teleport to a loaded, safe spawn; it never changes inventory, health, game mode or spawn point. Original return coordinates and authenticated identity are written to `verification-return/` before teleport. Other plugins can cancel the teleport. After verification, the plugin restores only a safe loaded location; if unavailable, the player can try `/ob return` with staff assistance. Back up this directory. If the gate is disabled or the original world is deleted, staff must recover any stranded players manually; never delete records before confirming their safe return. Do not enable a lobby without testing on a spare world.

## Trusted operations

Map a Discord role to a named local profile in **Trusted Operations**. Multiple conflicting profiles fail closed. Staff still need the relevant underlying Minecraft permission.

For a temporary edit task, choose a locally allowlisted profile, linked Discord user, duration (1–60 minutes), exact world name, operation (BLOCK, BIOME or ENTITY), and total attempted mutation budget (1–2,000,000). A different Manage Server holder must approve; neither administrator may be the recipient. The chosen profile supplies narrower region and command allowlists and per-operation/minute ceilings. Temporary tasks now require explicit `worldedit-scope.commands` entries (for example `worldedit:set`); an empty list permits no task commands. Specify the actual resolved plugin owner and command name on your server. Direct or administrative commands that lack a guarded extent guarantee (including regeneration and scripts) are denied even if listed. The immutable task ID binds approval, expiry and cumulative quota to the authenticated subject. Changing a pending task requires fresh approval. The quota is durably reserved in chunks of 128–1024 (larger tasks use larger chunks); unused reservations can be lost on crash rather than refunded. Task-only profiles cannot confer staff chat, console, lockdown or unrelated command privileges. Keep `guard-task-budgets/` in backups. Existing unscoped grants require a new task request. Other active sanctions always take precedence.

## Protection

Enable Guard only after setting verified staff profiles and testing a disposable world. For installations needing tighter third-party command control, `guard.strict-command-allowlist: true` treats unlisted commands as sensitive. Configure `guard.ordinary-commands` with resolved `owner:command` identities before turning it on; avoid accidental denial of ordinary gameplay commands. The standard sensitive-command rules remain enforced. Direct WorldEdit entity removal commands and edits without the guarded hook are denied by supported command paths; the guarded extent counts block, biome and entity-creation attempts. Protected cuboids and worlds take precedence over task permissions. Plugins that write directly to the server or run as console can bypass player listeners: this is not a sandbox for arbitrary plugin code. See [acceptance](acceptance.md).

## Lockdown storage

The local deadline is saved atomically to `plugins/OrionBridge/guard-lockdown.state`. Keep this file with backups; an expired deadline is not restarted. A failed manual save is not acknowledged; an automatic storage failure is logged and remains enforced in memory only. Invalid saved data prevents normal Guard initialization; investigate before reopening. Individual automatic containment is also stored by authenticated identity in guard-containment/ and retains independent absolute deadlines. Storage or capacity failures are logged. No deadline is extended merely by restart.

## Roles and offline reconciliation

Use explicit direction-specific mappings and only harmless LuckPerms groups. Managed Discord roles must be editable, have no guild permission bits and cannot serve as security staff/trusted roles. Owned transient grants are reconciled; manual grants are left intact. Canonical Java and Floodgate rank queries use authenticated identity mappings. Floodgate local linking provider checks reject a remapped Bedrock XUID; global-only, timeout or unknown results retain owned rank state. Opt-in AuthMe/noncanonical observations require stable server-specific mapping, both switches and a 24-hour maximum age. External account reuse/remapping cannot always be discovered without the provider. Do not use observed ranks where identity mappings can change externally.

## Moderation, evidence and appeals

### Discord controls
Use /mcserver manage > select Java server > OrionBridge > Identity & Security. Staff roles may inspect evidence and request cases; Manage Server is required for configuration, approvals, recovery and policy changes. Discord sanctions additionally check appropriate platform permissions and role hierarchy. A different authorized staff member approves punishment, account recovery, temporary trusted access and rollback.

Account panel: link, view account, help. Accounts: recent links, policy, unlink/recovery approvals. Compare accounts: HMAC network overlap only, not common ownership. Trusted Operations: map roles to named local profiles; request and approve temporary access. Roles: verified role and direction-specific rank mappings. Evidence: observations, related timeline, checksums, notes, acknowledge/dismiss, export. Cases: create/request, approve, revoke, local duration adjustment. Recovery: CoreProtect preview and approval. Performance: measured task diagnostics, not a full profiler.

Case states distinguish pending approval, delivery, and platform receipts. A Discord ban is removed only if it still carries the bridge's ownership marker. Existing unrelated bans/timeouts are preserved. A kick/warning cannot be undone. Offline and unsupported operations may remain pending, fail or be uncertain. Inspect receipts; never infer enforcement from a request.

Players submit their own case ID, reason and optional PNG/JPEG screenshot (1 MiB) through the verification panel. Staff may request additional information (up to five rounds); players open My appeals and respond. Reviews must be independent of the original requester and approver. Decisions are recorded; changing a sanction still requires the separate moderation process. Discord-banned users need an external staff contact path.

## Menu customization and performance

The optional `headings`, `materials`, `images` and `layout` keys in `menus/dashboard.yml` customize headings, inventory icons, Bedrock button images, dialog column/width, inventory rows, information slot and preferred button slots; existing labels and title prefix remain supported. Invalid/colliding slots are skipped and the inventory grows to fit its actions. Missing or invalid materials fall back to the built-in icon. Existing menu files are preserved; add keys manually and restart. Formal appeals are submitted on the Discord panel. See [live load benchmark](load-testing.md).

## See also

[Discord administration](https://github.com/engrkarlo/OrionBot/blob/orionbridge-2.0-completion/docs/orionbridge.md) · [Configuration](configuration.md) · [Acceptance](acceptance.md)

### Expanded dashboard themes

In `menus/dashboard.yml`, `bodies` controls account, case, appeal, player-list and staff screen content; `inputs` controls prompts; `messages` controls notifications; `labels.type-*` controls punishment-type labels; `materials.information` changes the inventory information item, and `layout.input-width` changes the Java prompt width. Use the placeholders from the bundled [default theme](../src/main/resources/menus/dashboard.yml); dynamic values are inserted literally, not parsed as MiniMessage. Existing files are retained, with built-in fallback text for missing keys. The Discord verification panel is customized separately. Security logic does not depend on labels.

### Additional command coverage

Guard recognizes sensitive resolved commands for EssentialsX, WorldGuard, CoreProtect, AdvancedBan, LiteBans, LuckPerms and PlugMan. This is command-level policy, not a hook into each plugin's underlying API. The strict allowlist remains optional. Bukkit hanging-entity interactions and bucket filling also pass through player restrictions/budgets. Test existing staff workflows: these commands now require explicit named-profile permission when sensitive-command protection is enabled.
