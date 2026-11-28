# Link your Minecraft and Discord accounts

## Players (Java and Bedrock)

1. Join the Minecraft server. Complete the server's normal authentication first (for example AuthMe, if used).
2. Run `/ob link`. You can also open `/ob account` and use the verification option. Java uses a dialog or inventory; Floodgate Bedrock uses a form when available.
3. Copy the private temporary code. In the community's Discord server, find the verification panel and click **Link Account**. Submit the code.
4. Return to Minecraft. Check the Discord identity shown and confirm only if it is yours. A successful link belongs to this Discord guild.
5. Use `/ob account` or **My Account** on Discord to check the result. If a code expires or you disconnect, request a fresh one.

Never share the code with another player. Do not enter your Microsoft password or Discord token. One Minecraft identity and one Discord account may be linked per guild. If you linked the wrong account or need to switch accounts, contact staff for the approved recovery process. Do not bypass the verification gate.

## Player commands

| Command | Purpose |
| --- | --- |
| `/ob link` | Start or resume account linking |
| `/ob account` or `/ob` | Open your account/verification interface |
| `/ob roles` | Account/rank information |
| `/ob punishments` | Current local restrictions |
| `/ob report` | Send a staff-review request |
| `/ob appeal` | View formal Discord appeal instructions and local case IDs |
| `/orionbridge account` (Discord) | Open your account interface |

Ordinary players do not need a special OrionBridge permission node. Minecraft reports require a verified account and enabled audit feed. For formal appeals choose Appeal a case on the Discord verification panel, submit your case ID and explanation, and check My appeals. Staff decisions do not automatically remove sanctions.

## For administrators

Follow [the operator manual](manual.md) to pair the Minecraft server using Pull, configure audit/control credentials, private channels, enable the verification service, and publish the Discord verification panel. Keep console access and test using a disposable server first. The feature is opt-in and requires matching OrionBot code; enabling it can restrict unverified joins. See [all commands and permissions](manual.md#minecraft-commands-and-permissions), [configuration](configuration.md), and [acceptance checks](acceptance.md).
