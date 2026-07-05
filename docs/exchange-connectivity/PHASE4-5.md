# Phases 4 & 5 — Market-data integration + Order execution over FIX (DONE)

Status: ✅ compiles, **32/32 tests pass** (Java 21). Existing modules & UI unchanged; everything is
config-switchable and additive.

## Phase 4 — ITCH → live market data
- **`MarketDataGateway`** seam (in `service/`): the depth source, swappable by config.
  - `SimulatedMarketDataGateway` (default, `itch.enabled=false`) → delegates to the matching engine's book (today's behaviour).
  - `ItchGateway` (`itch.enabled=true`) → runs the ITCH simulator, pushes every message through the real binary `ItchCodec`, rebuilds per-security `ItchOrderBook`s, and drives LTP / trade tape / indices via the existing `MarketDataService`. **Market depth now comes from ITCH.**
- `MarketDataController.depth()` now reads from the seam (one-line change). Added `MarketDataService.applyIndex()`.

## Phase 5 — Orders over FIX
- **`FixMessageFactory`** — builds FIX 5.0 SP1 `NewOrderSingle(D)`, `OrderCancelRequest(F)`, `OrderCancelReplaceRequest(G)` from OMS orders (core tags; DSE Parties/OrderRestrictions/bond-Yield added with real DSE cert).
- **`FixTradingGateway`** (`exchange.mode=dse-cert|dse-prod`) — the real `MatchingGateway`: `submit()/cancel()` send FIX messages via `Session.sendToTarget`.
- **`ExecutionService`** — maps inbound `ExecutionReport(8)` / `OrderCancelReject(9)` onto the **existing** OMS lifecycle: reads ExecType(150)+OrdStatus(39) independently, applies fills via the shared `OrderFillApplier`, trusts exchange CumQty(14)/AvgPx(6), writes trades, marks the portfolio, and pushes the same `order`/`trade` SSE the UI already consumes.
- `OmsFixApplication.fromApp` dispatches app messages to `ExecutionService`. Added `OmsOrderRepo.findByOrderRef` (ClOrdID lookup). New test `FixMessageFactoryTest`.

## Status → OMS lifecycle map (FIX OrdStatus 39)
`0`→OPEN · `1`→PARTIAL · `2`→FILLED · `4`→CANCELLED · `8`→REJECTED · `C`→EXPIRED · `6` (Pending Cancel)→no change.
All eight OMS statuses already existed — no schema/enum change.

## How to run each mode
| Mode | Command | Trading | Market data | FIX session |
|---|---|---|---|---|
| **Polished demo** (default) | `restart-backend.bat` | simulator | dsebd.org feed | logs on to FIXSIM (green) |
| **Fully connected** | `connect-dse-sim.bat` | **over FIX → FIXSIM** | **ITCH simulator** | logs on + routes orders |

Switching to real DSE later = change only `exchange.mode`, `fix.host/port/comp`, `itch.host/port` — no code.

## Deferred (roadmap)
- Phase 6: Exchange-Connectivity admin page (status, seq numbers, last message, reconnect, download logs).
- Phase 7: 10k-msg/s SSE coalescing, durable JdbcStore, DSE-specific FIX enrichment (Parties/OrderRestrictions/bonds), soak & cert tests.
- ITCH real-feed transports (SoupBinTCP/MoldUDP64) + binary replay.
