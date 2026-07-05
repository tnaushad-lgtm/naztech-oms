# DSE OMS — Exchange Connectivity Architecture Assessment Report

**Naztech DSE Exchange-Hosted OMS · X-stream INET Integration (FIX 5.0 SP1 order-entry + ITCH v2.2 market data)**
Prepared by: Lead Solution Architect · Status: **GATES ALL CODING** · Scope: `C:\tareq_AI_RND\local_mysql_analyze\dse-oms`

---

## Executive Summary

The existing DSE-OMS POC is architecturally ready for real exchange connectivity **because the trading side already routes through a single clean seam**: the `MatchingGateway` interface (`service/MatchingGateway.java:15`). Its javadoc (`MatchingGateway.java:11-12`) explicitly names a "FIX/FAST/ITCH gateway to the real Exchange Matching Engine (NASDAQ X-Stream INET)" as the intended production implementation "with zero changes to the OMS order/risk/portfolio layers above it." `OrderService` and `RiskService` depend only on this interface (`OrderService.java:29`), so replacing the in-process `SimulatedMatchingEngine` with a real FIX gateway is a **bean-selection change, not a rewrite**.

Three gaps must be closed:

1. **Trading (FIX 5.0 SP1 / FIXT.1.1):** QuickFIX/J is **not** on the classpath (`pom.xml` has only langchain4j + MySQL connector). A `FixTradingGateway implements MatchingGateway` is a net-new build.
2. **Market data (ITCH v2.2):** there is **no** `MarketDataGateway` seam — market data is written by the concrete `MarketDataService.ingest()` (`MarketDataService.java:174`). This interface must be **introduced first** before an `ItchGateway` can plug in cleanly.
3. **Persistence & config:** no FIX session/sequence/message-log tables exist; config is a single flat `application.properties` with no `application.yml` and **no externalisation of the exchange-specific enums** the DSE spec demands.

The design principle throughout: **the OMS business layer (`OrderService`, `RiskService`, `PortfolioService`, all controllers, the entire UI) never imports a QuickFIX/J or ITCH class.** Those live behind `com.naztech.oms.exchange.*` adapters that translate to and from the existing `OmsOrder` lifecycle. This keeps the eight-state order model, the SSE contract, and the REST surface **byte-for-byte unchanged**, and makes future CSE/BSE/NSE support a matter of adding adapters — not touching core.

**One hard security rule, stated up front:** the FIXSIM password is provided out-of-band by IBCS-Primax/DSE and must live **only in gitignored config** (the established `secrets.properties` / `spring.config.import` seam). It must never appear in `application.yml`, `application.properties`, source, or git history. (Note: the datasource password `gulshan` is currently committed in plaintext at `application.properties:9` — this pattern must **not** be repeated for FIX credentials.)

---

## 1. Existing Module Structure

Spring Boot 3.2.5 / Java 17 backend (`pom.xml:7-11,21`), single flat `application.properties`, no `application.yml`, no Docker (Windows `.bat` scripts). Package root `com.naztech.oms`.

```
com.naztech.oms
├── controller/          10 @RestControllers, plain, constructor-injected, NO Spring Security
│   ├── OrderController.java:15        /api/orders
│   ├── MarketDataController.java:18   /api/market   (depth() delegates to MatchingGateway)
│   ├── PortfolioController.java:15    /api           (bare prefix — collision caution)
│   ├── SecurityController.java:14     /api           (bare prefix — collision caution)
│   ├── RmsController.java:20          /api/rms
│   ├── AdminController.java:20        /api/admin      (exchange-level control plane)
│   ├── AuthController.java:13         /api/auth       (SHA-256 login, UUID token, no filter)
│   ├── StreamController.java:15       /api/stream     (SSE, own @CrossOrigin)
│   ├── NewsController.java:12         /api/news
│   └── AiController.java:23           /api/ai
├── service/
│   ├── MatchingGateway.java:15        ← THE trading seam (interface, 4 methods)
│   ├── SimulatedMatchingEngine.java:33   @Service implements MatchingGateway
│   ├── OrderService.java:21          lifecycle orchestrator (injects MatchingGateway L29)
│   ├── RiskService.java:18           pre-trade RMS
│   ├── MarketDataService.java:21     read/compute + ingest() write (NO interface in front)
│   ├── StreamService.java:20         SSE fan-out hub (CopyOnWriteArrayList<SseEmitter>)
│   ├── AuditService                  orderEvent() + audit() writers
│   ├── PortfolioService              applyFill()
│   ├── AuthService.java:21           login / fromToken (fromToken unused by any filter)
│   └── (Ai / Equity / etc.)
├── entity/              15 @Entity classes, 1:1 with db/schema.sql, ALL FKs are plain Long
│   ├── OmsOrder.java:14   Trade.java:13   OrderEvent.java:12   MarketData.java:14
│   ├── Security PriceHistory Exchange Broker AppUser ClientAccount Holding
│   └── RiskLimit AuditLog News EquitySnapshot
├── api/Dtos.java:7       records: OrderRequest, RiskResult, OrderView, Depth/DepthLevel,
│                         TradeTick, MarketRow, QuoteIn/IngestRequest, Candle
└── config/WebConfig.java:10   global CORS /api/** (allowedOriginPatterns "*", credentials true)
```

Frontend: Next.js App Router; sidebar `NAV` array in `components/Shell.tsx:11-20`; SSE via `EventSource` in `lib/useLive.ts`; API base derived in `lib/api.ts:6-13`.

Secondary services: Python/FastAPI market-data + AI on `:8091`; Next.js on `:3060`; backend on `:8090`.

---

## 2. Existing Order Flow

### OmsOrder status lifecycle (verbatim, from `entity/OmsOrder.java:33` comment)

```
NEW  →  PENDING_RISK  →  REJECTED
                     →  OPEN  →  PARTIAL  →  FILLED
                            →  CANCELLED
                            →  EXPIRED
```

The eight declared statuses (verbatim): **`NEW`, `PENDING_RISK`, `REJECTED`, `OPEN`, `PARTIAL`, `FILLED`, `CANCELLED`, `EXPIRED`.** Note: `EXPIRED` is declared in the enum comment (`OmsOrder.java:33`) but **no current code path sets it** — DAY/GTD validity is not enforced by the simulator (see Risks §9).

### Place → Risk → Matching → Fill → Status

1. **PLACE.** `POST /api/orders` + `X-Actor` header → `OrderController.place` (`OrderController.java:25`) → `OrderService.place` (`OrderService.java:47`). Loads `Security` + `ClientAccount` (unknown → `IllegalArgumentException` → 400). Builds `OmsOrder`: `orderRef "ORD-<ts>-<rand>"` (L54), uppercases side (L60), defaults `orderType=LIMIT` (L61), `tradeWindow=NORMAL` (L62), `validity=DAY` (L63), `price` default ZERO (L65), `filledQty=0` (L68), `avgFillPrice=0` (L69), `status="NEW"` (L70).
2. **PRE-TRADE RISK.** `riskService.check(o)` (`OrderService.java:76` → `RiskService.java:44`), order not yet persisted. Order of checks: broker RMS kill-switch (`broker.status != ACTIVE`, L48) → security ACTIVE (L51) → INDEX not tradable (L52) → qty>0 (L55) → lot-size multiple (L56) → per-scope CLIENT/TRADER/BROKER `maxOrderQty`/`maxOrderValue` (L73-84) → BUY buying-power / SELL holdings (L87-98) → wash-sale (L100-105) → AI soft score (L109). Returns `RiskResult(pass, reason, score, flags)`.
3. **RISK REJECT** (`OrderService.java:78`): `status="REJECTED"`, `rejectReason`, save, `audit.orderEvent(id,'RISK_REJECT',...)`, `publishOrder` SSE `order` — **never touches MatchingGateway**.
4. **RISK PASS** (`OrderService.java:88`): `status="PENDING_RISK"`, save (assigns id), `audit.orderEvent('RISK_PASS')`. Branch on `orderType` (L92): STOP/STOP_LIMIT → `status="OPEN"`, save, `matching.arm(o)` (L96), event `ARMED`; else LIMIT/MARKET → `matching.submit(o)` (L99) which matches **and sets final status inline**.
5. **SUBMIT → MATCH** (`SimulatedMatchingEngine.submit` L114): `synchronized(lock)`, builds `Incoming`, `match(inc, dbOrder)` (L125). `match()` (L176) picks `bestEligible` (price-then-seq priority, L220), `fillPx = best.price` (passive price), `executeFill(...)` (L185). Remainder: MARKET sweeps synthetic liquidity (L191-197); real LIMIT rests on book (L198-204).
6. **FILL** (`executeFill` L236): builds+saves `Trade` (`tradeRef "TRD-<seq>-<ms>"`, L240-249); `applyOrderFill` (L252/L289, volume-weighted avg, scale 4 HALF_UP); `portfolio.applyFill` for both aggressor and passive; passive → status FILLED/PARTIAL (L260), save, `orderEvent('FILL')`, SSE `order` (L265); `marketData.applyTrade` moves LTP/bid/ask (L275); SSE `trade` tape (L286).
7. **AGGRESSOR STATUS FINALIZE** (`SimulatedMatchingEngine.java:207-217`): `FILLED` if `remainingQty<=0`, else `PARTIAL` if `filledQty>0`, else `OPEN`; save; `orderEvent(status,...)`; SSE `order`.
8. **STOP LIFECYCLE:** armed stops in `armedStops`; scheduled `tick()` (L342) → `checkStops()` (L364): BUY `ltp>=stop` / SELL `ltp<=stop` (L374) → event `STOP_TRIGGERED` → `match()` → same fill path.
9. **CANCEL** (`OrderService.cancel` L147): status must be in `CANCELABLE {NEW,PENDING_RISK,OPEN,PARTIAL}` (L150) else `IllegalState` 400; `matching.cancel(o)`; `status="CANCELLED"` set **optimistically** (L153), save, event, SSE.
10. **MODIFY** (`OrderService.modify` L110): only OPEN/PARTIAL (L113); new qty must exceed filled (L120); `matching.cancel(o)` pulls from book (L122); `riskService.checkAmend` (L126); reject → revert + re-`submit` original + throw; else `status="PENDING_RISK"`, save, `matching.submit` re-matches remainder (L138), event `AMENDED`.

**APIs (§2):** `POST /api/orders`, `POST /api/orders/{id}/cancel`, `PUT /api/orders/{id}/modify`, `GET /api/orders`, `GET /api/orders/{id}/events`.

---

## 3. Existing Market Data Flow

**Single write path today:** Python/FastAPI (`marketdata/app.py:39`, `:8091`) scrapes `dsebd.org` via `bd_feed.py:90` on a 30 s APScheduler loop (`app.py:89`), normalises to `QuoteIn` dicts, `POST /api/market/ingest`. `MarketDataController.ingest` (`:89`) → `MarketDataService.ingest()` (`:174`): resolves `Security` by symbol+exchangeId (`:184`), upserts one `market_data` row per security (PK = `security_id`), seeds `bid=ltp-0.10`/`ask=ltp+0.10` (`:200`), computes `changePct` (`:204`), saves, publishes SSE `market` (`:211`).

**Second (internal) write path:** `MarketDataService.applyTrade()` (`:217`), called by `SimulatedMatchingEngine.executeFill()` after every fill, moves LTP/high/low/volume/valueMn/bid/ask.

**Read side** derives entirely from `market_data` + `trade`:
- `GET /api/market/watch` (`:104`) — one `MarketRow` per equity, sorted by valueMn.
- `GET /api/market/movers` (`:141`) — gainers/losers/active top-10.
- `GET /api/market/indices` (`:159`) — INDEX-class rows (animated by `driftIndices()`).
- `GET /api/market/{id}/depth` — **served from the in-memory `Book`, NOT the DB** (`MatchingGateway.depth`, `SimulatedMatchingEngine.java:146`).
- `GET /api/market/{id}/candles` (`:45`) — aggregates last 1000 real trades into OHLCV; falls back to `synthCandles()` (`:72`) when thin.
- `GET /api/market/{id}/trades`, `GET /api/market/tape` — from the `trade` table.

**Key finding:** there is **no `MarketDataGateway` interface** — market data is written by the concrete `MarketDataService`. This is the clean integration point that must be introduced (§8).

---

## 4. Existing Websocket / SSE Flow

**It is Server-Sent Events, not WebSocket.** `StreamController.stream()` (`StreamController.java:21-24`) exposes `GET /api/stream` (`produces=text/event-stream`) → `StreamService.subscribe()` (`StreamService.java:26-37`): `new SseEmitter(0L)` (no timeout), added to a `CopyOnWriteArrayList<SseEmitter>`, immediately sends `hello {ok, clients}`. `publish(event, payload)` (`StreamService.java:39-53`) Jackson-serialises **once**, then **synchronously loops over every emitter** — no filtering, batching, or backpressure.

**Five event names** (frontend whitelist `useLive.ts:7`): `hello`, `trade`, `order`, `market`, `indices`.

- `trade` `{securityId, symbol, price, qty, side, ts}` (`SimulatedMatchingEngine.java:279-286`)
- `order` — **two shapes**: from `SimulatedMatchingEngine.publishOrder` (L300-308) `{id,status,filledQty,avgFillPrice,accountId,brokerId}`; from `OrderService.publishOrder` (L180-184) `{id,status,brokerId,accountId}` (no fill fields).
- `market` `{type:"ingest", count:n}` (`MarketDataService.java:211`)
- `indices` `{ts}` (`SimulatedMatchingEngine.java:438`)

**SSE is a nudge/signal bus, not the data transport.** The `terminalStore.tsx:120-127` handler: `trade/indices/market` bump a `tapeBump` counter; `order/trade` arm a **600 ms-debounced** REST refetch of blotter+portfolio. Market grid independently polls `GET /api/market/watch` every 3 s. Auto-reconnect after 2.5 s on error (`useLive.ts:23-27`).

**Structural limit:** at 10k msg/sec this design cannot survive — every publish is a full serialize + O(clients) synchronous send on the producer thread (see §9 performance).

---

## 5. Existing Database Schema

`db/schema.sql` (lines 1-276): **15 InnoDB tables**, `exchange` a first-class dimension (DSE/CSE). Every entity maps 1:1, **all FKs are plain `Long` id fields — no `@ManyToOne`, no extra `@Index`/`@UniqueConstraint`** (Hibernate relies entirely on the SQL DDL).

| Group | Tables (schema.sql lines) |
|---|---|
| Reference/master | `exchange` (32-41), `broker` (45-56), `app_user` (61-75), `security` (78-97) |
| Market data | `market_data` (101-118, PK=`security_id`), `price_history` (121-132), `news` (248-260) |
| Accounts/positions | `client_account` (135-147), `holding` (150-159), `risk_limit` (163-174), `equity_snapshot` (264-275) |
| Trading core | `oms_order` (177-206), `trade` (209-221), `order_event` (225-232), `audit_log` (235-245) |

`oms_order` (177-206): UNIQUE `order_ref`; composite `idx_o_book(security_id,side,status)`. `trade` (209-221): UNIQUE `trade_ref`; `buy_order_id`/`sell_order_id` nullable, non-FK (allows single-sided synthetic fills).

### What is MISSING for FIX / ITCH

There is **no table for exchange connectivity** — no session state, sequence numbers, raw message logs, or exchange-order-id mapping (all matching is in-process). New persistence required:

| New object | Purpose |
|---|---|
| `fix_session` | id, `exchange_id` FK, SenderCompID, TargetCompID, begin_string, status (LOGGED_ON/OUT), last_heartbeat |
| `fix_sequence` | `session_id` FK, next_out_seq, next_in_seq — **or** host QuickFIX/J `JdbcStore` tables inside `dse_oms` for single-DB audit |
| `fix_message_log` | append-only journal: direction (FIX_IN/FIX_OUT/ITCH_IN), msg_type, seq_num, raw_payload TEXT, order_ref/trade_ref nullable, ts |
| `exchange_order_map` | `oms_order_id` FK, `exchange_order_id`, `exec_id`, leaves_qty, cum_qty, ord_status, UNIQUE(`exchange_order_id`) |
| `oms_order` new columns | nullable `cl_ord_id`, `exchange_order_id`, `exchange_status`, `routed_at` |
| `trade` new columns | nullable `exec_id` (reconcile `ExecutionReport` ExecID 17); + `accrued_interest_amt`, `dirty_price` for bonds |

Keep the plain-`Long`-FK convention for all new tables (avoids Hibernate mapping churn). **Referential-integrity caveat:** `order_event.order_id`, `trade.buy/sell_order_id`, `equity_snapshot.account_id`, `dealer_id` are app-enforced only — a FIX gateway writing exec reports must not assume DB cascade.

---

## 6. Existing APIs (Grouped Inventory)

**Backend `:8090` (`application.properties:2`). No Spring Security, no `@PreAuthorize`, no interceptor — authorization is NOT enforced server-side; `X-Actor` is a self-declared audit header (spoofable).**

- **Auth** — `POST /api/auth/login`
- **Reference** — `GET /api/exchanges`, `GET /api/securities`, `GET /api/securities/{id}`
- **Orders** — `POST /api/orders`, `POST /api/orders/{id}/cancel`, `PUT /api/orders/{id}/modify`, `GET /api/orders`, `GET /api/orders/{id}/events`
- **Market data** — `GET /api/market/watch|movers|indices|{id}/depth|{id}/candles|{id}/trades|tape`, `POST /api/market/ingest`
- **Portfolio/reports** — `GET /api/accounts`, `GET /api/portfolio/{id}`, `.../fills`, `.../equity`, `GET /api/reports/tradebook`
- **RMS** — `GET /api/rms/brokers`, `POST /api/rms/broker/{id}/halt`, `GET/PUT /api/rms/limits`, `GET /api/rms/alerts`
- **Admin (exchange control plane)** — `GET /api/admin/overview`, `GET/POST /api/admin/brokers`, `GET /api/admin/users`, `GET /api/admin/audit`
- **News** — `GET/POST /api/news`
- **Stream** — `GET /api/stream` (SSE)
- **AI** — `GET /api/ai/tts`, `POST /api/ai/advisor`, `GET /api/ai/status`, `GET /api/ai/search`, `POST /api/ai/risk-preview`

New connectivity admin endpoints belong under `/api/admin` (or a fresh `/api/connectivity`); global CORS on `/api/**` (`WebConfig.java:20-24`) auto-covers them.

---

## 7. Existing Extension Points

### `MatchingGateway` — THE FIX/ITCH seam (interface, quoted verbatim, `service/MatchingGateway.java:15-28`)

```java
public interface MatchingGateway {
    /** Route a risk-approved order to the engine for matching/resting. */
    void submit(OmsOrder order);                    // L18
    /** Arm a STOP / STOP_LIMIT order; it triggers when the LTP crosses its stop price. */
    void arm(OmsOrder stopOrder);                   // L21
    /** Pull a resting order off the book. */
    void cancel(OmsOrder order);                    // L24
    /** Aggregated market depth (bids/asks) for a security. */
    Depth depth(Long securityId, int levels);       // L27
}
```

The javadoc (`MatchingGateway.java:11-12`) states the production impl is "a FIX/FAST/ITCH gateway to the real Exchange Matching Engine (NASDAQ X-Stream INET) — with zero changes to the OMS order/risk/portfolio layers above it."

### How `SimulatedMatchingEngine` implements it (`SimulatedMatchingEngine.java:33`)

`@Service` implementing all four methods against an in-process price-time-priority book:
- `submit()` (L114) — `synchronized(lock)`, wraps order in `Incoming`, calls `match()` (L125) which **fills synchronously inside the call** and sets final status inline (L207-217).
- `arm()` (L130) — `armedStops.add(id)`.
- `cancel()` (L135) — removes from `armedStops` + bids/asks.
- `depth()` (L146) — reads the in-memory `Book`, `aggregate()` (L158) sums remaining qty per level.
- Supporting: `loadRestingOrders()` `@ApplicationReadyEvent` (L89), `tick()` `@Scheduled` autosim (L342), `checkStops()` (L364), `executeFill()` (L236), `applyOrderFill()` (L289, VWAP math).

### Other reusable seams
- **`StreamService.publish(type, payload)`** — the single SSE fan-out; a FIX/ITCH gateway reuses `trade`/`order`/`indices`/`market` names so the UI is transport-agnostic.
- **`AuditService.orderEvent(orderId, type, detail)`** — the `order_event` write seam (ACK/PARTIAL_FILL/FILL/REJECT/CANCELLED).
- **`PortfolioService.applyFill(...)`** + **`MarketDataService.applyTrade(...)`** — position/market-marking seams reused verbatim on each `ExecutionReport` fill.
- **`OmsOrder.remainingQty()`** (`OmsOrder.java:39`) + the VWAP block (`SimulatedMatchingEngine.java:289-298`) — the fill-accounting logic a FIX ER handler should mirror (better: factor into a shared `OrderFillApplier`).

---

## 8. Recommended Integration Points — The Adapter Design

### Design goal (non-negotiable)

The OMS business layer imports **zero** QuickFIX/J or ITCH classes. All protocol code lives in a new package `com.naztech.oms.exchange.*`. Adapters translate protocol messages ⇄ the existing `OmsOrder` lifecycle and reuse the existing SSE/audit/portfolio/market seams. **`OrderService`, `RiskService`, `PortfolioService`, all controllers, and the entire UI are untouched.**

### Adapter layering (ASCII)

```
┌──────────────────────────────────────────────────────────────────────┐
│  UI (Next.js) — /api/orders, /api/market, /api/stream  — UNCHANGED     │
└───────────────┬──────────────────────────────────────────────────────┘
                │ REST + SSE (5 event names, unchanged)
┌───────────────▼──────────────────────────────────────────────────────┐
│  OMS BUSINESS LAYER  (no QuickFIX/J, no ITCH imports)                  │
│  OrderController · MarketDataController · StreamController             │
│  OrderService · RiskService · PortfolioService · MarketDataService    │
│  AuditService · StreamService                                         │
└───────┬───────────────────────────────────────────┬──────────────────┘
        │ MatchingGateway (interface, existing)      │ MarketDataGateway (NEW interface)
        │                                            │
┌───────▼────────────────────┐          ┌────────────▼─────────────────┐
│  TradingGateway (port)      │          │  MarketDataGateway (port)     │
│  = MatchingGateway impl     │          │  onQuote / onSnapshot / depth │
├─────────────┬───────────────┤          ├──────────────┬───────────────┤
│ Simulated   │ FixTrading    │          │ Python-feed  │ ItchGateway   │
│ MatchingEng │ Gateway       │          │ ingest (now) │ (SoupBinTCP/  │
│ (profile    │ (@Primary /   │          │              │  MoldUDP64)   │
│  =simulator)│ ConditionalOn │          │              │               │
│             │ Property)     │          │              │               │
└─────────────┴──────┬────────┘          └──────────────┴──────┬────────┘
                     │ QuickFIX/J (Application, Session)        │ ITCH binary decode
         ┌───────────▼────────────┐              ┌─────────────▼──────────┐
         │ ExecutionService        │              │ market_data upsert +   │
         │ FIX ExecutionReport(8)  │              │ trade table + SSE      │
         │  → OmsOrder lifecycle   │              │ (via applyTrade/ingest)│
         └───────────┬────────────┘              └────────────────────────┘
                     │ reuses OrderFillApplier · AuditService · StreamService · PortfolioService
        ┌────────────▼─────────────┐        ┌──────────────────────────────┐
        │  DSE X-stream FIX 5.0 SP1 │        │  DSE-INET ITCH v2.2           │
        │  (FIXT.1.1 transport)     │        │  (fixed-width binary MD feed) │
        └───────────────────────────┘        └──────────────────────────────┘
```

### Interfaces

**`ExchangeGateway`** — a lifecycle/health facade over a connected exchange session (logon/logout status, session state, last heartbeat, sequence numbers). Backs the new `/api/admin/connectivity` admin page. Does not carry orders — it aggregates connection state exposed by `FixTradingGateway` and `ItchGateway`.

**`TradingGateway`** — realised as the existing **`MatchingGateway`** interface. The `FixTradingGateway implements MatchingGateway` wraps/replaces the simulator drop-in using **QuickFIX/J**:
- `submit(OmsOrder)` → `NewOrderSingle(35=D)`: `11=orderRef` (ClOrdID, max 20, embed date for cross-day uniqueness), `<Parties>` **must include PartyRole=5** (Investor/Deposit Account), `<Instrument>` (SecurityID/Source per DSE symbology), `1=Account` (max 16), `529=OrderRestrictions` (Account Type, externalised), `54=Side`, `38=OrderQty`, `40=OrdType`, `44=Price` or `236=Yield` (mutually exclusive — bonds), `59=TimeInForce`, `60=TransactTime`. **Returns immediately (async)** — order stays PENDING_RISK/OPEN until first `ExecutionReport` (unlike simulator's synchronous fill).
- `cancel(OmsOrder)` → `OrderCancelRequest(35=F)`: `41=OrigClOrdID` (previous non-rejected ClOrdID), new `11=ClOrdID`. **Cancel removes ALL remaining qty** (DSE: use `35=G` to partially reduce). Confirm via `CANCELED(150=4)` ER — do not finalise optimistically.
- `arm(OmsOrder)` → local stop tracking (armedStops-style) or FIX stop order; fire `NewOrderSingle` when LTP crosses. Behaviour preserved because `arm()` is only called after status is set OPEN (`OrderService.java:96`).
- `depth(Long,int)` → served from the ITCH/market-data book; returns the same `Dtos.Depth` shape.

**`MarketDataGateway` (NEW interface, must be introduced)** — realised by `ItchGateway`. Methods `onQuote`/`onSnapshot`/`depth`. Both the existing Python-feed `ingest()` and the `ItchGateway` implement it, funnelling into the same `market_data` upsert. `ItchGateway` decodes the ITCH v2.2 fixed-width big-endian feed:
- Directory `[R]` (stock-directory) → `Security` (symbol, tick_size, lot_size, category, status, **MaturityDate off163, YieldDecimals off167** for bonds).
- `[A]/[F]/[U]/[D]/[E]/[C]` → order book → `MatchingGateway.depth()`.
- `[Q]` Trade / `[C]` Order-Executed-With-Price → `trade` table + `MarketDataService.applyTrade()` → LTP/OHLC/turnover; SSE `trade`.
- `[O]` BBO → real top-of-book (replaces the LTP±0.10 synthesis at `MarketDataService.java:200`).
- `[Z]` Index → real index values (replaces `driftIndices()`).
- **Price scaling:** divide raw ints by `10^PriceDecimals` (from `[R]`); handle sentinels `0x7FFFFFFF` (market/unavailable), `[A]` OrderNumber==0 && Qty==0 (ref-price), `[Q]` MatchNumber==0 && Qty==0 (close-price).

**`ExecutionService`** — the inbound `ExecutionReport(35=8)` handler (gateway-internal, **NOT in OrderService**). Finds order by ClOrdID/orderRef; **reads BOTH `ExecType(150)` and `OrdStatus(39)` independently** (DSE design rule: `150` = what this ER is about, `39` = current overall state — never derive one from the other). Applies fills using `LastQty(32)`/`LastPx(31)`/`CumQty(14)`/`AvgPx(6)`, trusting exchange `CumQty(14)`/`AvgPx(6)` (idempotency key = `ExecID(17)` — protects against out-of-order/duplicate ERs, the main behavioural difference from the simulator's local math). Writes `Trade` (exec_id=17), `AuditService.orderEvent`, `StreamService.publish('order'/'trade')` — identical downstream effect to `executeFill`.

### DSE FIX ExecType / OrdStatus → OMS status mapping

| DSE `ExecType(150)` | DSE `OrdStatus(39)` | Trigger | OMS `OmsOrder.status` | OrderEvent |
|---|---|---|---|---|
| `0` New | `0` New | order accepted | `OPEN` | `ACK` |
| `F` Trade | `1` Partially filled | partial fill | `PARTIAL` | `PARTIAL_FILL` |
| `F` Trade | `2` Filled | full fill | `FILLED` | `FILL` |
| `4` Cancelled | `4` Cancelled | cancel confirmed | `CANCELLED` | `CANCELLED` |
| `5` Replaced | `5` Replaced | cancel/replace ack | `OPEN`/`PARTIAL` (reconcile) | `AMENDED` |
| `6` Pending Cancel | (unchanged) | cancel in progress | (unchanged — do not flip yet) | `PENDING_CANCEL` |
| `8` Rejected | `8` Rejected | reject (+`OrdRejReason 103`, `Text 58`) | `REJECTED` | `RISK_REJECT`/`EXCH_REJECT` |
| `C` Expired | `C` Expired | GTD/day rollover | `EXPIRED` | `EXPIRED` |
| `3` Done for day | — | end of day | (working→as-is; day-end sweep) | `DONE_FOR_DAY` |
| `7` Stopped | — | stopped | (status-only note) | `STOPPED` |
| `H` Trade Cancel | — | trade bust | reverse fill / adjust | `TRADE_CANCEL` |
| `I` Order Status | (echo current) | OrderStatusRequest reply | (no change) | — |
| — | `X` trigger-in-book-not-active | *Nasdaq-defined, status-only* | maps to armed-STOP `OPEN` | `ARMED` |
| — | `Z` Private Order | *Nasdaq-defined, status-only* | out of scope for POC | note only |

All eight OMS statuses already exist (`OmsOrder.java:33`) — **no schema/enum change needed**. `X` and `Z` are status-only (no ExecType); treat as annotations.

### Bond-with-yield handling (from BRS "Bond Trading with Yield" V2)

- Bonds quote/match on **clean price**; a trader may enter clean price **or** YTM. `Price(44)` and `Yield(236)` are **mutually exclusive** on D/G; whichever is sent, the other is echoed on the `ExecutionReport(8)`. **Never send both.**
- The OMS may send either; recommended over FIX to send `Yield(236)` and let X-Stream compute clean price (BRS also allows converting-then-sending Price). A typed yield is converted to clean price **once at entry** and not recalculated on future days.
- `ExecutionReport(8)` adds `Yield(236)` (echoed) and `AccruedInterestAmt(159)`. Settlement (dirty) price = clean price + accrued interest → persist to new `trade.accrued_interest_amt`/`dirty_price` columns.
- **Basis caution:** FIX `TradeCaptureReport` `LastPx(31)` = **dirty** price for yield-traded bonds; ITCH `[Q]` reports Execution Price (clean, order-book basis) + Execution Yield. Order book = clean; TCR LastPx = dirty. `OrderQty(38)` for bonds = par/face value, not share count — affects qty/lot/risk validation.
- Bond boards/products (`YIELDDBT`, `GOVDBT`/`CORPDBT`/`SUKUK`), Actual/Actual day count, **negative yield supported**, no opening session, G-Sec close/yield sourced externally from Bangladesh Bank. The OMS must hold per-bond static data (coupon rate/frequency/schedule, issue/maturity dates, par value, day-count) to display price⇄yield locally (iterative, no closed form — matches Excel YIELD).

### `application.yml` (proposed — greenfield migration; secrets stay OUT)

```yaml
# application.yml  (migrated from flat application.properties; secrets NEVER here)
exchange:
  mode: simulator          # simulator | dse-cert | dse-prod

fix:
  enabled: false           # true only for dse-cert / dse-prod
  begin-string: FIXT.1.1
  default-appl-ver-id: "8" # FIX50SP1 — the ONLY valid value
  sender-comp-id: ${FIX_SENDER_COMP_ID:}   # exchange-agreed, TBC IBCS-Primax/DSE
  target-comp-id: ${FIX_TARGET_COMP_ID:}   # exchange-agreed, TBC
  host: ${FIX_HOST:}       # TBC bilaterally with the Exchange
  port: ${FIX_PORT:0}      # TBC
  heartbeat-int: 30        # gateway clamps to 10..60; server may override to 60 on 1st logon
  reset-seq-num-flag: false
  username: ${FIX_USERNAME:}
  # password: NEVER here — see fix.password in gitignored secrets file below
  store: jdbc              # QuickFIX/J JdbcStore inside dse_oms for single-DB audit
  data-dictionary: FIXT11.xml, FIX50SP1.xml  # + DSE custom fields

itch:
  enabled: false
  transport: soupbintcp    # point-to-point; moldudp64 for multicast
  soup-host: ${ITCH_SOUP_HOST:}   # TBC
  soup-port: ${ITCH_SOUP_PORT:0}  # TBC
  mold-group: ${ITCH_MOLD_GROUP:} # multicast group, TBC
  mold-port: ${ITCH_MOLD_PORT:0}  # TBC
  feeds: [TOTALVIEW, INDEX, BBO, LASTSALE]   # subscribe set, TBC
  username: ${ITCH_USERNAME:}

spring:
  config:
    import: optional:file:./secrets.properties   # gitignored — fix.password lives here
```

```properties
# secrets.properties  (GITIGNORED — provided out-of-band by IBCS-Primax/DSE)
fix.password=<FIXSIM password — never committed, never logged, never in yml>
```

### DSE-specific values that MUST be externalised, never hardcoded (from spec findings)

| Category | Values | Source |
|---|---|---|
| Session identity | `SenderCompID(49)`, `TargetCompID(56)` (swapped by direction, exchange-agreed) | FIX spec p7 |
| Connectivity | host, port, multicast group/port (agreed bilaterally, not in spec) | FIX p1-18 / ITCH openQ |
| Credentials | `Username(553)`, `Password(554)` (**secrets file only**) | FIX p9 |
| Account types | `OrderRestrictions(529)`: N=Investor, F=Foreign Investor, I=Institution, O=Foreign Institution, R=NRB | FIX p47 (Nasdaq-defined) |
| Order types | `OrdType(40)`: 1=Market, 2=Limit, **Z=Market-at-best** (Exchange-defined) | FIX p47 |
| Time in force | `TimeInForce(59)`: 0=Day,1=GTC,3=IOC,4=FoK,6=GTD, **S=Session** (Nasdaq-defined) | FIX p47 |
| Sides | `Side(54)`: 1=Buy, 2=Sell, 5=Short Sell | FIX p47 |
| Party roles | `PartyRole(452)` list; **PartyRole=5 (Investor/Deposit Acct) mandatory on D**; `PartyIDSource(447)=C` | FIX p42-43 |
| Symbology | `SecurityID`/`SecurityIDSource`, board via `SecuritySubType(762)`/`MarketSegmentID(1300)` | FIX Appx B.1 |
| Reject codes | `OrdRejReason(103)`{5,6,99}, `CxlRejReason(102)`{1,6,99}, `CxlRejResponseTo(434)`{1,2}, `BusinessRejectReason(380)`{0..6} | FIX p46 |
| ExecType/OrdStatus | full enum incl. Nasdaq-defined `X`,`Z` | FIX p46-47 |
| Bond boards/products | `YIELDDBT`/`ALTDBT`/`BUYDBT`; `GOVDBT`/`CORPDBT`/`SUKUK`/`AMORT`/`ZERO`/`NOTES`/`PRPTL` | BRS |
| Day-count / yield | Actual/Actual basis, coupon frequency, `YieldType(235)`, yield decimals=4 | BRS |
| ITCH scaling | `PriceDecimals`, `YieldDecimals`, tick-size tables (per-security from `[R]`) | ITCH p… |
| ITCH sentinels/events | `0x7FFFFFFF`, ref-price/close-price sentinels, System Event codes, Cross Types | ITCH |

### Files TOUCHED vs NOT touched

**NOT touched (must not be rewritten):** `OrderService`, `RiskService`, `PortfolioService`, `SimulatedMatchingEngine` (kept behind a profile as dev/demo fallback), all 10 controllers, all 15 entities (only additive columns), `Dtos.java`, `StreamService`/`StreamController` contract, `useLive.ts`, `terminalStore.tsx`, the entire UI. **The `MatchingGateway` interface itself is NOT changed** — new impls satisfy the existing signature.

**TOUCHED (additive only):**
- `pom.xml` — add QuickFIX/J + ITCH decode deps.
- `application.properties` → migrate to `application.yml` + `secrets.properties` (add `fix.password`).
- New package `com.naztech.oms.exchange.*` (all net-new).
- New interface `MarketDataGateway`; `MarketDataService.ingest()` refactored to implement it (behaviour preserved).
- Bean-selection: `SimulatedMatchingEngine` and `FixTradingGateway` gated by `@ConditionalOnProperty(exchange.mode)` / `@Profile` / `@Primary`.
- New shared `OrderFillApplier` (extract from `SimulatedMatchingEngine.java:289-298` + status derivation L207-217) so simulator, FIX ER handler, and ITCH share identical fill math.
- New tables (schema.sql additions) + additive columns on `oms_order`/`trade`.
- New `/api/admin/connectivity` controller + one `NAV` entry in `Shell.tsx:11-20` + `app/admin/connectivity/page.tsx`.

---

## 9. Risk Analysis

**Technical**
- **Bean collision:** both `SimulatedMatchingEngine` and `FixTradingGateway` are `@Service implements MatchingGateway` → `NoUniqueBeanDefinitionException` unless one is `@Primary`/profile/`@ConditionalOnProperty`. Decide bean-selection **before** adding the FIX impl.
- **Fill-math duplication:** VWAP + status derivation exists twice today (`SimulatedMatchingEngine.java:207-217` and L257-267); a FIX ER handler would be a third copy → drift risk. **Must** extract `OrderFillApplier` first.
- **Async vs sync semantics:** simulator fills synchronously inside `submit()`; FIX fills arrive later via ER. Any code assuming a filled status immediately after `place()` returns is wrong under FIX. Trust exchange `CumQty(14)`/`AvgPx(6)`; idempotency by `ExecID(17)`.
- **Depth divergence:** depth is served from RAM (`SimulatedMatchingEngine.java:146`), not the DB; no persisted L2 book. ITCH must implement `MatchingGateway.depth()` or demo and real depth will diverge.
- **`aggressorSide`:** meaningful only in the simulator; from FIX the aggressor is often unknown (`AggressorIndicator(1057)` may not be present) → column may be null/approximate on real fills.

**Sequencing / correctness**
- `OrderService.cancel` sets `CANCELLED` **optimistically** (`OrderService.java:153`) before exchange confirmation → can diverge if the cancel is rejected/late. Reconcile against the ER; treat `Pending Cancel(150=6)` as not-yet-cancelled.
- `EXPIRED` has no code path today → a FIX gateway (or day-end sweep) must implement expiry to honour DAY/GTD.
- `order_ref`/`trade_ref` are internally generated → must reconcile to exchange ClOrdID/OrderID/ExecID or the tape and drop-copy diverge (hence `exchange_order_map`). `OrderID(37)` may change after amendment; identify by OrderID with `OrigClOrdID=NONE`.

**Performance — 10k updates/sec**
- Current SSE `publish()` (`StreamService.java:39-51`) = full Jackson serialize + O(clients) synchronous send **on the producer thread**. At 10k msg/sec × N clients this saturates CPU, blocks the matching/ITCH thread (head-of-line blocking across all clients), and floods the browser (10k React state updates/sec freezes the UI).
- **Mandatory coalescing:** server-side, insert a buffered queue + scheduled flush (every 50–100 ms) in front of `publish()` — last-value-wins per symbol for `market`/`indices`, buffer trades into arrays; decouple ITCH decode thread from client sends. Client-side, batch handler calls via `requestAnimationFrame` and collapse redundant `tapeBump`. The existing 600 ms blotter debounce + 3 s market poll already coalesce those paths; the raw per-event tape rate does not. Controller and frontend contract stay stable.
- `SseEmitter(0L)` (no timeout) leaks half-open connections; `CopyOnWriteArrayList` copies on every add/remove — expensive under high churn.

**Security / credentials**
- **FIXSIM password is provided out-of-band by IBCS-Primax/DSE and must live ONLY in gitignored `secrets.properties`** (never `application.yml`, never source, never logs, never git history). FIX itself is **unencrypted — no SSL/TLS** ("No security exists without transport-level encryption"), so run the session over a private link/VPN to the exchange.
- **No server-side authz today** (no `SecurityFilterChain`/`@PreAuthorize`); `X-Actor` is spoofable. Connectivity admin endpoints (session logon/logout, credential handling) are privileged → add a lightweight `OncePerRequestFilter` wrapping `AuthService.fromToken()` (`AuthService.java:69`, ready but unused) restricting to `{EXCHANGE_ADMIN, BROKER_ADMIN, RMS_MANAGER}` — centrally, without editing controllers.
- CORS is `allowedOriginPatterns("*")` + `allowCredentials(true)` (`WebConfig.java:21-24`) — tighten before exposing connectivity controls. Datasource password `gulshan` is committed plaintext (`application.properties:9`) — do not repeat this for FIX.
- `dse-oms.zip` exists in the repo root and `secrets.properties` holds a real Gemini key on disk — avoid zipping/sharing the tree with secrets present.

**DSE cert / prod migration**
- `exchange.mode: simulator | dse-cert | dse-prod` must swap CompIDs, host/port, credentials, and enable/disable the simulator's autosim. **`app.matching.autosim`/`price-sim` MUST be false in cert/prod** or the simulator will random-walk prices and inject synthetic fills over the live feed. Disable `driftIndices()` in favour of real ITCH `[Z]`. Durable sequence-number store (QuickFIX/J `JdbcStore` in `dse_oms`) is **mandatory before go-live** — a FIX engine that loses seq-num state cannot resend.

**Spec ambiguities (open questions — do NOT invent)**
- Full enum tables (OrdType/Side/TIF/ExecInst/OrderRestrictions/reject codes) are partly in Appendix C — confirm exact codes from the spec before externalising.
- No dedicated `Reject(3)`/`SessionRejectReason(373)` table found on the read pages — confirm DSE uses standard FIXT.1.1 codes.
- No explicit gap-fill/`SequenceReset(4)` narrative on read pages — confirm session-chapter rules.
- Drop-copy session existence not described — confirm with DSE.
- `OrdStatus X` (trigger-in-book) implies stop support but `OrdType` enum lists no stop type — clarify how stop/trigger orders are entered (possibly via `ExecInst`).
- ITCH `[Q]` Orderbook-vs-match-id field labelling conflict; `[U]` uses both `0x7FFFFFFF` and `-1` for "market" — confirm canonical sentinel.
- Bond: exact `YieldType(235)` value, clean-price/N=1 formula images (didn't extract), `SecurityType` tagging for GovBond/Sukuk/Perpetual, whether `PRPTL` needs yield handling — lift from source PDFs before building the yield engine.
- **Host/port/CompIDs are not in any spec — agreed bilaterally; TBC from IBCS-Primax/DSE.**

---

## 10. Detailed Implementation Plan (7 Phases)

All new code in `com.naztech.oms.exchange.*`. Every phase ends with the same gate: **STOP → compile → run → test → commit** (do not proceed until the gate passes and the commit is made).

**FIXSIM config the user will fill (gitignored `secrets.properties` + `application.yml` non-secret keys):**

```
fix.host           = ____   # TBC IBCS-Primax/DSE
fix.port           = ____   # TBC
fix.sender-comp-id = ____   # exchange-agreed, TBC
fix.target-comp-id = ____   # exchange-agreed, TBC
fix.username       = ____   # TBC
fix.password       = ____   # OUT-OF-BAND — secrets.properties ONLY, never committed
```

> Host / port / SenderCompID / TargetCompID are **still to be confirmed** from IBCS-Primax/DSE — the code must read them from config with no defaults so a missing value fails fast rather than connecting to the wrong endpoint.

### Phase 1 — QuickFIX/J integration
- **Scope:** wire QuickFIX/J into the build; stand up a FIX Application/session skeleton that logs on to a loopback acceptor; **no order routing yet**. Prove `MatchingGateway` bean selection.
- **New files:** `exchange/fix/FixEngineConfig`, `exchange/fix/OmsFixApplication` (QuickFIX/J `Application`), `exchange/fix/FixTradingGateway implements MatchingGateway` (stub methods), `exchange/OrderFillApplier` (extracted shared fill math). `pom.xml` adds `quickfixj-core` + FIXT11/FIX50SP1 dictionaries.
- **Config:** migrate `application.properties`→`application.yml`; add `exchange.mode`, `fix.*` (secrets excluded); `store: jdbc`.
- **Tests:** loopback logon (`sessionCreated`/`onLogon`), bean-selection test (simulator vs FIX by `exchange.mode`), `OrderFillApplier` unit tests proving parity with simulator math.
- **Gate:** STOP → compile → run (logon to loopback) → test → commit.
- **Acceptance:** app boots in `simulator` mode unchanged; in `dse-cert` mode `FixTradingGateway` is the `@Primary` `MatchingGateway`; loopback logon succeeds; `OrderFillApplier` matches existing VWAP/status output.

### Phase 2 — FIXSIM connectivity
- **Scope:** connect to the DSE **FIXSIM** using the out-of-band credentials; implement full session mgmt (Logon `A`, Heartbeat clamp 10–60, Test Request, Resend, Sequence Reset, Logout); persist session + sequence + message log.
- **New files:** `exchange/fix/FixSessionManager`, `exchange/persistence/FixSession`/`FixSequence`/`FixMessageLog` entities + repos, schema.sql additions; `exchange/ExchangeGateway` (health/status facade).
- **Config:** fill `fix.host/port/sender-comp-id/target-comp-id/username` (yml) + `fix.password` (secrets); `default-appl-ver-id: "8"`.
- **Tests:** live FIXSIM logon; heartbeat/test-request round-trip; deliberate gap → resend recovery; message-log persistence assertions.
- **Gate:** STOP → compile → run (logon to FIXSIM) → test → commit.
- **Acceptance:** sustained logged-on session with heartbeats; sequence numbers persist across restart; every in/out message journaled to `fix_message_log`; connectivity status visible via `ExchangeGateway`.

### Phase 3 — ITCH simulator
- **Scope:** build an ITCH v2.2 decoder + a local ITCH simulator (SoupBinTCP/MoldUDP64 framing) emitting `T/S/R/A/E/C/D/U/Q/O/Z` so market data can be developed without the live feed. Introduce the `MarketDataGateway` seam.
- **New files:** `exchange/itch/ItchDecoder`, `exchange/itch/ItchGateway implements MarketDataGateway`, `exchange/itch/ItchSimulator`, new `service/MarketDataGateway` interface; refactor `MarketDataService.ingest()` to implement it (behaviour preserved).
- **Config:** `itch.*` (transport, soup/mold host/port/group, feeds).
- **Tests:** decode fixtures for each message type; price scaling (`10^PriceDecimals`); sentinel handling (`0x7FFFFFFF`, ref-price/close-price); order-book build/modify/delete.
- **Gate:** STOP → compile → run (simulator feeds decoder) → test → commit.
- **Acceptance:** `MarketDataGateway` exists with both Python-feed and ITCH impls; decoder passes all fixtures; simulated book produces the same `Dtos.Depth` shape.

### Phase 4 — Market data integration
- **Scope:** point `ItchGateway` at the real DSE-INET feed (or FIXSIM-paired MD); populate `market_data` + `trade` + `security` via `ingest()`/`applyTrade()`; real BBO/index; turn simulator autosim OFF.
- **New files:** `exchange/itch/ItchDirectoryLoader` (`[R]`→`Security`), `exchange/itch/ItchBookManager` (feeds `depth()`); wire `ItchGateway` into `MarketDataService`.
- **Config:** `exchange.mode: dse-cert`; `app.matching.autosim=false`, `price-sim=false`; disable `driftIndices()`.
- **Tests:** end-to-end feed → `watch`/`movers`/`indices`/`depth`/`tape`/`candles` return real data; BBO replaces LTP±0.10; index values from `[Z]`.
- **Gate:** STOP → compile → run (real/paired MD) → test → commit.
- **Acceptance:** all `/api/market/*` endpoints and the UI render live ITCH data **unchanged in shape**; `synthCandles()` fallback no longer hit; depth from real book.

### Phase 5 — Execution mapping
- **Scope:** implement `ExecutionService` — full `NewOrderSingle(D)`/`Cancel(F)`/`Cancel-Replace(G)` outbound + `ExecutionReport(8)`/`OrderCancelReject(9)` inbound; map ExecType/OrdStatus → OMS lifecycle (table §8); bond yield + accrued interest.
- **New files:** `exchange/fix/FixMessageFactory` (build D/F/G with Parties PartyRole=5, Instrument, OrderRestrictions), `exchange/fix/ExecutionService`, `exchange/persistence/ExchangeOrderMap` entity/repo; additive columns on `oms_order`/`trade` (incl. `accrued_interest_amt`, `dirty_price`).
- **Config:** externalised enum tables (account types, OrdType, TIF, reject codes) loaded from config; bond board/product/day-count map.
- **Tests:** D→New→PartialFill→Fill lifecycle; F cancel confirm/reject; G replace; reject-code mapping (103/102/380 + Text 58); duplicate/out-of-order ER idempotency by ExecID; bond order with Yield(236) → clean/dirty reconstruction; `Price`/`Yield` mutual-exclusion guard.
- **Gate:** STOP → compile → run (place/cancel via FIXSIM, observe ERs) → test → commit.
- **Acceptance:** an order placed through the existing `POST /api/orders` routes over FIX, lifecycles correctly through the eight OMS statuses driven by ERs, appears in blotter/tape/portfolio via the **unchanged** SSE+REST path; bonds settle dirty = clean + accrued.

### Phase 6 — Admin tools
- **Scope:** Exchange Connectivity admin page — session status/logon/logout, sequence numbers, message-log viewer, feed subscription state; behind role enforcement.
- **New files:** `controller/ConnectivityController` (`/api/admin/connectivity/*`), `config/AuthInterceptor` (`OncePerRequestFilter` wrapping `AuthService.fromToken`, roles `{EXCHANGE_ADMIN,BROKER_ADMIN,RMS_MANAGER}`); frontend `app/admin/connectivity/page.tsx` + one `NAV` entry in `Shell.tsx:11-20`.
- **Config:** none new (inherits `/api/**` CORS).
- **Tests:** authz filter blocks unauthorised roles; endpoints return live session/seq/log state; page renders with `<Shell connected=...>` via `useLive`.
- **Gate:** STOP → compile → run (drive session from UI) → test → commit.
- **Acceptance:** an EXCHANGE_ADMIN can view session health, force logon/logout, and inspect the FIX/ITCH message log; other roles are denied; existing pages untouched.

### Phase 7 — Testing (hardening & sign-off)
- **Scope:** end-to-end certification against DSE cert; performance (10k msg/sec coalescing), failover/resend, reconciliation vs exchange drop-copy, security review; final `dse-prod` migration checklist.
- **New files:** `exchange/stream/CoalescingPublisher` (buffered queue + 50–100 ms flush in front of `StreamService`), integration/soak test suites, drop-copy reconciliation job.
- **Config:** `exchange.mode: dse-prod`; autosim/price-sim asserted false; durable JdbcStore verified.
- **Tests:** 10k msg/sec soak (matching thread not blocked, UI stable via rAF batching); disconnect/gap-fill recovery; sequence persistence across restart; reconciliation (OMS tape == exchange drop-copy); security review (credentials only in secrets, no plaintext in logs/git, authz enforced, CORS tightened).
- **Gate:** STOP → compile → run (full cert scenario) → test → commit.
- **Acceptance:** cert sign-off; sustained 10k msg/sec without UI freeze or matching-thread stall; recovery from forced disconnect with correct sequence resume; zero credential leakage; clean go/no-go for `dse-prod`.

---

## Compatibility Statement — CSE / BSE / NSE

The design is **exchange-agnostic by construction**. All venue-specific protocol code is confined to `com.naztech.oms.exchange.*` behind the `MatchingGateway` (trading) and new `MarketDataGateway` (market data) ports, with `ExchangeGateway` as the connectivity facade. The OMS core, the eight-state `OmsOrder` lifecycle, the SSE contract, the REST surface, and the UI have **no dependency on any exchange protocol**. `exchange` is already a first-class dimension in the schema (`exchange` table, `exchange_id` FK on `broker`/`security`/`oms_order`).

Supporting a new venue (**CSE, BSE, NSE**, or any future exchange) is therefore purely additive: implement the two gateway interfaces for that venue's protocol (e.g. a `CseTradingGateway`/`CseMarketDataGateway`, or a next-gen matching engine adapter), externalise its enums/symbology/session params to config, and select it via `exchange.mode` (extended to per-exchange profiles). **No existing module is rewritten, and the UI is unchanged.** DSE, being the first real adapter, sets the template every subsequent exchange follows.

---

## Appendix Z — Reviewer confirmations (FIX spec pp.19–21, read directly by the Lead Architect)

These points were verified against the source PDF after the assessment and refine §8/§9:

- **Drop-copy DOES exist.** `ExecutionReport(8)` carries `CopyMsgIndicator(797)` ("Drop Copy"); confirm the drop-copy session setup with DSE (previously an open question).
- **`OrderCancelReject(9)` `CxlRejReason(102)` returns ONLY `99` (Other)** — the real reason is in `Text(58)`. `CxlRejResponseTo(434)` distinguishes Cancel vs Cancel/Replace. `ClOrdID`/`OrderID`="NONE" when the order is unknown.
- **`AggressorIndicator(1057)`** is present on fills **during continuous trading only** (V5.0 tag) — so the §9 "aggressor may be null" caveat is precise: rely on it only in continuous session.
- **`ExecutionReport` also carries** `LeavesQty(151)` (=OrderQty−CumQty, may be 0 when Cancelled/DoneForDay/Expired/Rejected), `DisplayQty(1138)` (iceberg disclosed qty), `TradeMatchID(880)` (X-stream trade id → reconcile to our `trade`), `GrossTradeAmt(381)`, `MatchType(574)`, and `AccruedInterestAmt(159)` (debt securities).
- **`OrderStatusRequest(H)`** needs `Symbol(55)`+`Side(54)` (not processed by the Exchange, but required) and either `ClOrdID(11)` or `OrderID(37)`.
