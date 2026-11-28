# Commands & permissions

[Documentation index](README.md) · [Player linking](player-linking.md) · [Administration](administration.md)

## Minecraft commands

| Command | Who may use it | What it does |
| --- | --- | --- |
| `/ob`, `/ob account` | Everyone | Account or verification interface |
| `/ob link` | Authenticated players | Start or resume linking |
| `/ob help` | Everyone | Verification/account help |
| `/ob confirm <request UUID>` | Authenticated player with a matching claim | Chat fallback for the in-game confirmation |
| `/ob return` | Verified player | Retry restoration from optional lobby when safe |
| `/ob roles` | Linked players | Rank and account |
| `/ob punishments` | Linked players | Local case IDs and active restrictions |
| `/ob report` | Linked players with audit enabled | Staff review request |
| `/ob appeal` | Linked players | Formal Discord appeal instructions |
| `/ob staffchat <message>` | Verified authorized staff | Private relay, not granted by edit-only task |
| `/ob admin` | Verified trusted staff with explicit GUI permission and lockdown-bypass profile | Administrator dashboard |
| `/ob status` | `orionbridge.admin` | Local service diagnostics |
| `/ob reload` | `orionbridge.reload`; Guard also requires `orionbridge:ob-reload` in player's trusted profile | Telemetry settings only |

## Bukkit permission nodes

| Permission | Default | Purpose |
| --- | --- | --- |
| `orionbridge.hide` | false | Hide from telemetry/activity listings |
| `orionbridge.reload` | op | reloads all configuration, messages and menus|
| `orionbridge.admin` | op | View diagnostics |
| `orionbridge.admin.gui` | false | Open trusted admin UI; profile and verification still required |

There is no OP bypass of mandatory verification. A staff profile relaxes only OrionGuard checks and never grants underlying Bukkit, WorldEdit or LuckPerms permissions. Player commands such as linking do not need a separate permission.

## Console recovery

| Command | Purpose |
| --- | --- |
| `ob status` | Diagnose initialized services |
| `ob reload` | reloads all configuration, messages and menus|
| `ob lockdown <minutes or off>` | Set (1–60 minutes) or clear durable Guard lockdown |
| `ob verification-off` | Disable verification gate until restart |

Use the server console rather than a player command when testing emergency recovery. Correct configuration and restart after recovering; console break-glass is not an account link.

## Discord commands

| Command | Access |
| --- | --- |
| `/mcserver manage` | Manage Server; pair Java server and configure bridge |
| `/orionbridge manage` | Manage Server; native security manager |
| `/orionbridge account` | Guild member; view own linked account |
| `/diagnostics` | OrionBot operator; bot health |

Configured security staff can view evidence/request cases; configuration, approval and recovery require Manage Server. Discord enforcement also checks the platform's permissions and hierarchy. Appeal reviewers cannot be the appellant, original case requester or original case approver.

## Command guide and completion

`/ob help` lists available commands with usage and descriptions, `/ob help <command>` shows an individual entry. Java chat can suggest the command on click and display a hover hint. Tab completion is permission/feature-aware; lockdown suggests durations and off, confirm only suggests the sender's own unexpired request ID, and free-text staff chat has no argument suggestions. Formatting and descriptions live in `menus/help.yml`; status and replies live in `messages.yml`. Actual command names, permissions and enforcement are not customizable. `/ob reload` applies all YAML files. If a signed operation or pending moderation effect is active, retry later. Configuration errors keep the previous runtime when possible. A full service reload invalidates old staff leases and requires bot reauthorization; cosmetic edits do not.
