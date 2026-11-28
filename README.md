# OrionBridge

**Connect Minecraft to OrionBot — accounts, moderation, security, ranks, chat and live server status.**

OrionBridge is the Minecraft-side Paper plugin for OrionBot. Its Discord control center is built into OrionBot itself; no separate extension is needed. A basic Minecraft status panel can still work without OrionBridge.

> Development preview · 2.0.0-alpha.7 · Paper 26.3 / Java 25. The new security code is not production-certified. Complete the matched live acceptance checklist before enabling it on a production server.

## Start here

| I want to… | Guide |
| --- | --- |
| Install, update or pair the plugin | [Installation & setup](docs/installation.md) |
| Link my Minecraft and Discord accounts | [Player guide](docs/player-linking.md) |
| Find commands and permissions | [Commands & permissions](docs/commands.md) |
| Manage ranks, staff, punishments and protection | [Administration](docs/administration.md) |
| Customize settings and menus | [Configuration](docs/configuration.md) |
| Fix a connection or verification problem | [Troubleshooting](docs/troubleshooting.md) |
| Run live compatibility and performance checks | [Acceptance](docs/acceptance.md) · [Load testing](docs/load-testing.md) |

[Full documentation index](docs/README.md) · [OrionBot Discord administration](https://github.com/engrkarlo/OrionBot/blob/orionbridge-2.0-completion/docs/orionbridge.md) · [Changelog](CHANGELOG.md)

## Quick setup

1. Install a matching OrionBridge JAR on Paper 26.3 with Java 25. Keep the existing `plugins/OrionBridge/` directory.
2. On your Minecraft host, allocate a spare port and configure a public HTTPS reverse proxy to that port. Use Pull mode; the bot host does not need a new inbound port.
3. In `config.yml`, set `mode: pull`, `bind: "0.0.0.0"` and your allocated `port`. Keep the existing token or let the plugin generate one on first start.
4. In Discord, run `/mcserver manage` → select your Java server → OrionBridge → Configure Pull. Enter the HTTPS origin and token privately.
5. Test telemetry first. Follow [the security setup](docs/installation.md#enable-identity-and-security) before enabling mandatory verification or other enforcement features.

## What players do

Join the server, authenticate through AuthMe if required, run `/ob link`, enter the private code in the Discord verification panel, then confirm the Discord account in Minecraft. Both Java and Bedrock clients can disconnect and resume an unexpired code. Authentication alone does not unlock gameplay while mandatory Discord verification is active.

## Build and compatibility

Use JDK 25 and Maven 3.9+ at this repository root:

```sh
mvn -B clean verify
```

The configured output is `target/OrionBridge-2.0.0-alpha.7.jar`. Use a successful [build workflow](https://github.com/engrkarlo/Orion-Linker/actions/workflows/build.yml), not an unverified artifact. The [acceptance checklist](docs/acceptance.md) distinguishes supported paths from unverified or unsupported integrations.

Standalone source imported from [OrionBot at cd031ad](https://github.com/engrkarlo/OrionBot/tree/cd031ade390aec6d84f046a297156d86e2eb7f16/OrionBridge).

Created by **Karlow**.

### Configuration layout

`config.yml` is for connection/telemetry. Feature settings are in `config/` (audit, control, verification, correlation, guard, roles, chat, recovery); menu themes are in `menus/`. On upgrade v15 automatically splits your existing root configuration and retains your settings and credentials. Read the [configuration guide](docs/configuration.md) before enabling security or chat.
