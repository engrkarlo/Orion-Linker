# Live load benchmark and acceptance runbook

No real server benchmark has been performed by the repository build. Automated unit/smoke tests and synthetic queue checks are not evidence of production tick performance.

Use a disposable Paper server matching the intended Java/Paper, plugin versions, proxy, CPU/RAM allocation and world settings. Back up the plugin and bot data before testing; do not generate an actual attack or perform rollback on a production world.

## Run matched trials

1. **Baseline**: keep telemetry enabled but Guard, role sync, verification, audit and chat disabled. Record idle for 5 minutes, then representative normal gameplay for at least 10 minutes. Record actual concurrent population, activity, plugin list, heap allocation and host load.
2. **Enabled**: enable the intended services, restart both processes and repeat the same normal activity and population. Record TPS/MSPT (median, p95, p99 if available), tick stalls, heap/GC, CPU and Node event-loop delay.
3. **Peak and recovery**: repeat at the anticipated peak population. On a disposable server, exercise authorized bounded WE edits, authenticated Java/Bedrock linking, rank changes, case delivery, chat and a controlled disconnect/reconnect of the proxy. Record verification lease expiries, queue drops, delivery lag and recovery time. Confirm that existing active lockdown survives restart and clears at the original deadline.
4. **Repeat**: perform at least three matched trials if possible. Record all raw profiler URLs privately and report ranges rather than only averages. Check for unrelated host contention.

## Measurement sources

- Paper: Spark profiler (for example /spark profiler start --timeout 120 if supported by the installed Spark version), TPS/MSPT and health reports. Compare the relevant OrionBridge stacks between matched runs.
- Minecraft: /ob status and OrionBridge startup logs; configured Guard health and dropped audit counters.
- Discord: /diagnostics (operator only) for CPU, RSS/heap, event-loop delay and REST activity; /orionbridge manage > Performance for bridge diagnostics.
- Record Pterodactyl resource charts and proxy error/latency logs. Do not include credentials or player identifiers in shared reports.

Suggested provisional criteria to agree with the operator: no unintended access, cross-guild data leak, lost case receipts or restart expiry extension; no queue growth under sustained normal load, no unexplained verification expiry at the target concurrency, and acceptable p95/p99 MSPT and event-loop latency versus baseline. Choose numeric thresholds for the actual hardware before testing; 50 ms/tick is the 20 TPS budget, not a guarantee.

| Trial | Population | TPS | MSPT median/p95/p99 | CPU | Heap / GC | Bot event-loop | Audit drops / lease expiries |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Baseline | TBD | TBD | TBD | TBD | TBD | TBD | TBD |
| Services enabled | TBD | TBD | TBD | TBD | TBD | TBD | TBD |
| Peak / proxy recovery | TBD | TBD | TBD | TBD | TBD | TBD | TBD |

## Known boundary

Guard's WE/FAWE hook applies only to supported extent mutation paths; if registration fails or its plugin is disabled, resolved edit commands are blocked when edit enforcement is enabled. Arbitrary third-party direct writes, privileged host code, and untested FAWE paths remain outside the guarantee. A rare lockdown state transition performs an atomic disk write; measure its latency during the controlled lockdown trial. A passing benchmark does not establish complete protection.
