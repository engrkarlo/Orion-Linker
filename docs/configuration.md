# Configuration reference — v15

**New installation?** Start with the [quickstart](installation.md). Normally only the allocated port needs editing. Keep the advanced files for explicit security policy and appearance.

The first startup creates the following files in plugins/OrionBridge. Read the comments directly above each option: they explain what enabling it does, prerequisites, limits and common examples. New optional features default off. The root config.yml contains connection and telemetry only; the section name inside each feature file is unchanged.

| File | Purpose |
| --- | --- |
| config.yml | Connection mode, transport (AUTO/HTTP/WEBSOCKET), bind, allocated port, legacy token import, telemetry and privacy. |
| config/audit.yml | Signed activity/evidence, guild/server IDs and audit signing key. |
| config/control.yml | Signed actions and its separate control key. Required for verification and chat. |
| config/verification.yml | Optional account linking, independently required login gate, AuthMe realm, Java interface and optional lobby. |
| config/correlation.yml | Optional pseudonymous network evidence; real client addresses required. |
| config/guard.yml | Guard enforcement, rules, limits, protected areas and trusted profiles. |
| config/roles.yml | LuckPerms group and permission allowlists and offline rank reconciliation. |
| config/chat.yml | Public two-way chat and optional private staff chat. |
| config/recovery.yml | CoreProtect lookup and separately approved rollback. |

## Upgrade and reload

Stop Paper, back up the complete plugin folder, and replace the JAR. A legacy root config (v1–14) is automatically split; its exact original is saved as config.yml.v<version>.*.bak. Existing values, keys, custom profile names, unknown root options and data directories are retained. The root version is written last; interrupted migration can reuse identical feature files. If a category conflicts, neither existing file is overwritten: compare both copies and resolve the conflict before restarting. Once v15 is installed, a missing category or duplicated legacy category in the root is an error, not an invitation to regenerate defaults. Keep the entire config/ and menus/ directories in backups. Existing themes are backed up and upgraded additively to the current theme version, preserving custom values. Missing menu files and messages.yml are generated even when verification is off.

Use /ob reload for the root, all eight feature files, all menu themes, and messages.yml. A cosmetic-only change keeps current verification leases and services. Feature/connection changes rebuild services with fresh signed authorization; unexpired challenges survive when the identity scope is unchanged. The command refuses to interrupt active signed requests or pending moderation effects; try again when idle. If initialization fails, the previous loaded runtime is restored when possible, without rewriting your edited files. If rollback fails, players are disconnected and joins restricted until recovery. A full service reload can discard ephemeral chat, old forms and unapproved CoreProtect previews; persisted sanctions, Guard deadlines and edit quotas remain. Explicit disabling of enforcement intentionally turns that service off. Installing a new JAR still requires restart. A missing/corrupt configuration can cause a temporary join restriction rather than silently turning security off. Console ob verification-off is the emergency release until reload or restart. Do not change version numbers manually.

## Menu appearance

- menus/java-dialogs.yml: native Java verification screens, button captions, columns and widths; also the text fallback.
- menus/java-inventory.yml: Java verification chest menu, item materials, size and slots.
- menus/bedrock-forms.yml: Bedrock verification forms, captions and optional texture paths.
- menus/dashboard.yml: shared post-verification player and staff account menus, reports, appeals, punishments and diagnostics; Java dialog/inventory layout, materials, tooltips and Bedrock icons.
- menus/help.yml: command guide title, per-command usage/descriptions, entry formatting, footer, click and hover hints.
- messages.yml: command responses, verification hints, moderation notices, Guard warnings, in-game chat and startup console styling.

Use MiniMessage and retain dynamic placeholders, especially the private verification code and claimant identity. A theme only changes appearance, never authorization.

## Cross-platform chat

Finish Pull pairing and signed control first, test verification, and enable the matching verification feature in OrionBot. In config/chat.yml set chat.enabled: true, then decide which direction(s) to allow. Minecraft-to-Discord relays eligible chat as MC · Player; Discord-to-Minecraft displays [Discord] Name to verified players. Set the Minecraft chat channel in OrionBot > Identity & Security > Channels, enable chat, grant channel access and enable Message Content Intent. Staff chat additionally requires the private staff destination, Guard and a trusted profile with staff-chat: true. Keep public and staff channels separate. Run /ob reload. The relay is not a durable transcript; queued chat can expire during congestion.

## Data and transport
Retain config.yml, config/, the entire audit and sanctions directories, and menu files. Back up OrionBot SQLite and encryption keys together. Keys are scoped to guild/server. Security endpoints are available through Pull: events, acknowledge, actions, verification and batch, policy, chat, recovery and health. Legacy /v1/snapshot remains compatible. No arbitrary remote console commands. Configure HTTPS, request size/rate limits and restrict plaintext backend access.

## Guard deployment
Start with enforcement disabled. Test with a linked administrator and ordinary player; allow only exact necessary commands. Protected worlds are hard-deny for guarded edits. Edit limits are attempted changes, not a guarantee of successful block counts. Non-player edits and alternate plugin/API paths need separate review. Lockdown is not a substitute for backups or server permissions.

## Rule responses (config v10)

For each of GUARD_COMMAND_BLOCKED, WORLDEDIT_BLOCKED, GUARD_BLOCK_LIMIT, CONTAINER_ACTIVITY, JOIN_SPIKE, ENTITY_SPIKE and EXPLOSION_ACTIVITY, set guard.rules.<KIND>.enabled, severity (LOW/MEDIUM/HIGH/CRITICAL), response, duration-seconds (5–3600) and cooldown-seconds (1–3600). Responses: ALERT, BUILD_RESTRICTION, WORLDEDIT_RESTRICTION, COMMAND_RESTRICTION, FREEZE, QUARANTINE; LOCKDOWN only for JOIN_SPIKE. Unattributed entity events are alert-only. Unknown actors are never assigned individual containment. Rule switches control additional response and evidence, not the independent hard command/edit/block budgets. Defaults: suspicious commands/edits and observational rules alert; block-budget violation quarantines; JOIN_SPIKE inherits the existing join-lockdown-seconds setting (0 = alert only). Omitted durations inherit containment-seconds or join-lockdown-seconds. These short local restrictions are stored locally with absolute deadlines; they are not centrally approved durable moderation cases. Use a centrally approved durable case for persistence.

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

When Guard is enabled, in-game /ob reload requires the orionbridge.reload permission and a verified profile explicitly listing orionbridge:ob-reload. Console reload is unaffected. All configuration and menu changes can be applied through /ob reload.

roles.offline-reconciliation (default true when roles.enabled is true) enables signed POST /v2/ranks. The plugin accepts at most four canonical Java UUIDs per request and at most four storage loads concurrently, waits at most two seconds, returns only available primary groups, and does not grant any permissions. The matching bot queries previously verified matching Java UUIDs, not arbitrary usernames. Bedrock/AuthMe rank changes reconcile on authenticated login; do not infer offline identity from names.

## Protected edit regions and permission attribution

Optional guard.protected-regions is a list of up to 32 inclusive cuboids. Example: `{world: spawn, x1: -100, y1: -64, z1: -100, x2: 100, y2: 320, z2: 100}`. The WorldEdit extent rejects protected block writes, biome cells intersecting those cuboids and entity creation, including explicitly enabled non-player edits. Entity creation for player edits also requires the trusted profile's entity-tasks flag. This is not a WorldGuard replacement: direct plugin edits, entity removals and FAWE alternate paths still require integration testing. LuckPerms node mutations identify the affected account, while an optional published action log identifies a reported initiator if its authenticated session can be resolved. Never treat a grant recipient as the initiator without evidence.

## Scoped task profiles (config v11)

Each local `guard.profiles.<name>.worldedit-scope` may restrict `worlds`, inclusive `regions` (world, x1,y1,z1,x2,y2,z2), `operations` (BLOCK, BIOME, ENTITY) and resolved `commands` (`worldedit:set`, etc.). Empty world/region lists mean no additional restriction; an empty command list denies temporary-task commands (permanent profiles may be unrestricted); an explicitly empty operations list denies all extent mutations. Both region and world restrictions apply when set. Biome writes must fit the entire 4×4×4 cell. Global protected worlds/regions always win. A temporary profile selected and independently approved in Discord inherits these limits and existing operation/minute ceilings; it does not override Bukkit permissions. Use separate named profiles for specific jobs. Direct WE entity removal commands are denied when extent enforcement is on because they may bypass the guarded extent. Enabled non-player edits have separate bounded operation/minute budgets and are blocked during lockdown. Direct API writes and untested FAWE paths remain outside guarantees.

## Observed offline ranks (v12)

`roles.offline-observed-identities: false` is the default. Enable it together with Discord Role Sync > Observed ranks only where the server's AuthMe/Floodgate UUID mapping is stable. Signed lookups use the last authenticated linked identity/UUID observation, scoped to this server, for up to 24 hours. AuthMe must use the configured realm and be enabled. Noncanonical Floodgate UUIDs require Floodgate and cannot be confused with a different canonical XUID UUID. A contradictory current session rejects the lookup. External offline remapping is not authoritative or detectable in every deployment; disable this feature when mappings can change externally. Unknown rank results retain existing owned role state until a valid lookup or another explicit policy change.

## Strict command allowlist (v13)

`guard.strict-command-allowlist: false` defaults off. Enable only after listing ordinary commands by resolved `owner:command` in `guard.ordinary-commands`. Unlisted commands require a trusted profile; mandatory authentication and `/ob` recovery commands remain handled separately. Sensitive commands cannot become ordinary merely by listing them. Test aliases and plugin ownership.

## Verification lobby (v14)

`verification.lobby.enabled: false` and `verification.lobby.world: ''` are additive defaults. When enabled use the exact name of a loaded world with safe spawn. Return coordinates are stored under `verification-return/`; never delete a record before safe recovery. Inventory is untouched. See [administration](administration.md#optional-verification-lobby).

## Task authorization storage

Task requests use the existing local named profiles and separate signed, immutable per-task limits. `guard-task-budgets/` stores reserved mutation quotas; preserve the directory. Quotas are conservative after a crash. No temporary task grants unrelated privileges or lockdown bypass.

## Setup-managed settings

The private setup process fills missing audit/control/network credentials and activates requested services. It journals approved changes, makes per-file backups and rejects mismatched existing credentials. Do not edit IDs or secrets unless intentionally recovering an existing pairing. Never delete the data folder to reset setup. `verification.force` defaults to true for existing configurations; the new optional-linking wizard writes false explicitly. `chat.require-link` defaults to true, and forced verification always overrides public unlinked chat. Private staff chat remains authorization-only. Both settings are additive; existing security policies and custom values are retained. The `transport: AUTO` listener accepts WebSocket and authenticated HTTP on the same port. `WEBSOCKET` requires that listener but still accepts authenticated HTTPS compatibility traffic. `HTTP` uses the original listener.

## Private credentials and offline crossplay

`internal/credentials.yml` is managed by the plugin. Legacy nonempty token, guild/server IDs and signing keys are imported once; conflicts with an existing private value are rejected. Backups contain secrets and must remain private. Do not remove the `credentials-managed` marker or reset the internal folder. A missing private file is a recovery error. `internal/setup.properties` stores short-lived pending requests and hashed claim capabilities, with the original expiry. New defaults are persisted additively on reload/startup. `verification.auto-authme-realm: true` assigns a unique server-scoped realm to an empty realm on an active offline AuthMe server. Set false only to intentionally disable automatic assignment; a nonempty explicit realm always wins. No changes are made to AuthMe, Geyser or Floodgate configuration.
