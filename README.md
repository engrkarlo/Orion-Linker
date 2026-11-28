# OrionBridge

Connect a Paper Minecraft server to OrionBot. Start with live status and public chat, then add account linking, ranks, moderation, protection and recovery when you need them.

**2.0.0-alpha.8 · Paper 26.3 · Java 25 · OrionBot 1.9.0-alpha.1**

> Development preview. Use matching builds, complete the checks and test on a disposable server before enforcing security in production.

## Get started

1. Install OrionBridge and start Minecraft. Set your hosting allocation's spare port in `plugins/OrionBridge/config.yml`.
2. Point your HTTPS reverse proxy at that port.
3. Run `ob setup` in the Minecraft console. In Discord, open `/orionbridge setup`, select your registered Java server, and follow the private pairing instructions.
4. Approve the connection in the Minecraft console. Complete pairing in Discord and choose the features and channels you want.

No manual signing-key exchange is required for a new matching installation. The setup wizard provisions keys privately. Existing connections and security settings are retained. WebSocket is preferred when the proxy supports it; authenticated HTTPS is the compatibility path.

[Quickstart and upgrading](docs/installation.md) · [Player linking](docs/player-linking.md) · [Commands](docs/commands.md) · [Advanced settings](docs/configuration.md) · [Administration](docs/administration.md) · [Troubleshooting](docs/troubleshooting.md)

## Build and test

Use JDK 25 and Maven 3.9+:

```sh
mvn -B clean verify
```

Use the [checked build artifact](https://github.com/engrkarlo/Orion-Linker/actions/workflows/build.yml) (`OrionBridge-2.0.0-alpha.8.jar`). The [acceptance checklist](docs/acceptance.md) and [load-test runbook](docs/load-testing.md) are release gates, not claims of completed live tests.

[Changelog](CHANGELOG.md) · [OrionBot](https://github.com/engrkarlo/OrionBot/tree/orionbridge-simple-setup)

Created by **Karlow**.
