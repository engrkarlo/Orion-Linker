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
| `orionbridge.reload` | op | Reload connection/telemetry |
| `orionbridge.admin` | op | View diagnostics |
| `orionbridge.admin.gui` | false | Open trusted admin UI; profile and verification still required |

There is no OP bypass of mandatory verification. A staff profile relaxes only OrionGuard checks and never grants underlying Bukkit, WorldEdit or LuckPerms permissions. Player commands such as linking do not need a separate permission.

## Console recovery

| Command | Purpose |
| --- | --- |
| `ob status` | Diagnose initialized services |
| `ob reload` | Reload telemetry |
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
