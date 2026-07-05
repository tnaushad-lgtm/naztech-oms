# Phase 1 — QuickFIX/J integration (DONE)

**Goal:** wire the industry-standard FIX engine (QuickFIX/J) into the OMS behind the existing
`MatchingGateway` seam, add config-only venue switching, and prove nothing existing broke.
**No exchange is contacted yet** — that's Phase 2. Status: ✅ compiles, **21/21 tests pass**.

## What was added (all additive — no existing module rewritten)
| File | Purpose |
|---|---|
| `pom.xml` | + QuickFIX/J (`quickfixj-core`, `-messages-fixt11`, `-messages-fix50sp1`) |
| `resources/application.yml` | new `exchange.mode` / `fix.*` / `itch.*` config (secrets excluded) |
| `exchange/config/ExchangeProperties`, `FixProperties`, `ItchProperties` | type-safe settings |
| `exchange/config/ExchangeConfig` | registers the property beans |
| `exchange/config/SimulatorModeCondition`, `FixModeCondition` | pick the active gateway from `exchange.mode` |
| `exchange/OrderFillApplier` | shared fill math (VWAP@4dp + FILLED/PARTIAL/OPEN), extracted from the simulator |
| `exchange/fix/FixSessionState` | live FIX session health (for the admin page, Phase 6) |
| `exchange/fix/OmsFixApplication` | QuickFIX/J callbacks: logon/logout/heartbeat + logging; injects credentials; masks password |
| `exchange/fix/FixEngineConfig` | builds & starts the FIX initiator (only when `fix.enabled` + host/port set) |
| `exchange/fix/FixTradingGateway` | real-DSE `MatchingGateway` (routing stubbed → Phase 5) |

**Touched existing file (minimal):** `SimulatedMatchingEngine` now (a) calls `OrderFillApplier` instead of
its private copy of the fill math, and (b) carries `@Conditional(SimulatorModeCondition)` so it is the
active gateway only in simulator mode. Behaviour is unchanged (verified by the existing test suite).

## How the config-only switch works
`exchange.mode`:
- `simulator` (default) → `SimulatedMatchingEngine` is the `MatchingGateway` (today's demo behaviour).
- `dse-cert` / `dse-prod` → `FixTradingGateway` is the `MatchingGateway`; the QuickFIX/J initiator
  connects **only** when `fix.enabled=true` and host/port are set.

## The only secret — never in git
Put the FIX password in the **gitignored** `backend/secrets.properties` (already imported):
```
fix.password=<supplied out-of-band by IBCS-Primax / DSE>
```
Everything else (host, port, senderCompID, targetCompID, username) goes in `application.yml` or env vars
(`FIX_HOST`, `FIX_PORT`, `FIX_SENDER_COMP_ID`, `FIX_TARGET_COMP_ID`, `FIX_USERNAME`).

## Tests (21/21)
- `OrderFillApplierTest` (5) — VWAP/rounding/status parity with the simulator.
- `ExchangeModeConditionTest` (4) — `simulator`→sim, `dse-cert`/`dse-prod`→FIX.
- `OmsFlowTest` (12) — the full existing OMS flow still passes (no regression).

## Next — Phase 2 (needs input)
Live FIXSIM logon (session/heartbeat/resend). Needs from IBCS-Primax/DSE:
`fix.host`, `fix.port`, `fix.sender-comp-id`, `fix.target-comp-id` (username + password already supplied).
