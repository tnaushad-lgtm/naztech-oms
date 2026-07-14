# Naztech OMS — Executive Technical Status

**Exchange-Hosted OMS for Dragon Security (DSE/CSE)** · Prepared for Naz bhai · 14 July 2026
Covers the codebase as of commit `253ae20` · **126/126 backend tests green**

| Headline | Value |
| --- | --- |
| Order throughput today | **292 orders/sec** — dev laptop, full FIX round trip |
| Order entry | FIX 5.0 SP1 over FIXT.1.1 (QuickFIX/J 2.3.1) |
| Market data | ITCH v2.2 binary codec · simulator / SoupBinTCP / MoldUDP64 |
| Target | **8,000 orders/sec** — server plan in §6 |

---

## §1 · What we have, in one paragraph

The OMS is a full institutional stack running end-to-end on real exchange protocols: order entry
over **FIX 5.0 SP1 / FIXT.1.1** into a local venue that behaves like a matching engine (session
phases, opening auction, partial fills, cancel/replace, configurable reject/partial outcome mix),
and market data over a real binary **ITCH v2.2** codec with order-book reconstruction,
day-start/day-close broadcasts, and genuine **SoupBinTCP** and **MoldUDP64** transports with
sequence-gap detection and retransmission recovery. MySQL remains the system of record;
**QuestDB** holds the tick time-series real candles are built from, and **Valkey** holds the live
picture (quotes, depth) with cross-instance fan-out ready. Pre-trade risk gates every order —
session, kill-switch, limits, buying power, wash-trade, board/trade-window rules, client-account
status. A measured throughput harness places real orders through the whole path and reports the
round trip, not just the send rate.

---

## §2 · Architecture — ports-and-adapters, every integration behind a seam

```
┌─ CLIENT PLANE ─ Next.js 14 · :3060 ────────────────────────────────────────────┐
│  Trader Terminal │ Trading Desk (dockable) │ Market Depth Analysis │ Admin      │
└──────────────────────────────┬─────────────────────────────────────────────────┘
        REST (Next proxy)  +  SSE direct to :8090  (order/session/depth PUSHED)
┌─ ORDER PLANE ─ Spring Boot 3.2.5 · Java 21 · :8090 ────────────────────────────┐
│  OrderService ─▶ RiskService (session→kill-switch→limits→BP→wash→board rules)  │
│       └─▶ «MatchingGateway» seam — selected by exchange.mode (config only)      │
│             ├─ SimulatedMatchingEngine   (in-JVM price-time book)              │
│             └─ FixTradingGateway         (QuickFIX/J · NOS/Cancel/Replace)     │
│  ExecutionService ◀─ ExecutionReports    MarketSessionService (trading day)    │
└──────────────────────────────┬─────────────────────────────────────────────────┘
        FIX session :15000  ·  venue control :15001 (phase push + price sync 3s)
┌─ THE VENUE ─ LocalExchange, own process · :15000/:15001 ───────────────────────┐
│  QuickFIX/J acceptor · acks · 2-clip fills · opening auction (uncross)         │
│  phase-aware rejects · outcome mix (reject% / partial% 25·50·75 / full)        │
└──────────────────────────────┬─────────────────────────────────────────────────┘
┌─ MARKET-DATA PLANE ────────────────────────────────────────────────────────────┐
│  «ItchSource» seam: Simulator | SoupBinTCP | MoldUDP64 | replay (.itch)        │
│      └─▶ ItchSequencer (in-order · gap detect · retransmit · declared loss)    │
│           └─▶ ItchGateway: binary codec → per-security books → depth SSE push  │
│  MySQL :3306        QuestDB 8.1.1 :9009/:9000     Valkey :6379    FastAPI :8091│
│  (system of record) (ticks in via ILP,            (quotes, depth  (dsebd.org   │
│                      candles out via SAMPLE BY)    TTL, pub/sub)   + aux AI)   │
└────────────────────────────────────────────────────────────────────────────────┘
```

The seams are the architecture: `MatchingGateway` (simulator ⇄ real FIX), `ItchSource`
(simulator ⇄ SoupBinTCP ⇄ MoldUDP64 ⇄ replay), `TickStore` (QuestDB ⇄ no-op), `HotStore`
(Valkey ⇄ in-memory). Pointing at real DSE is **configuration** — `exchange.mode=dse-cert`, FIX
CompIDs, ITCH endpoint — zero code changes above a seam. The AI layer (Gemini advisor + on-prem
all-MiniLM-L6-v2 for semantic search and screen navigation) never touches the trading path.

---

## §3 · What the simulation plays — a trading day, not a demo loop

**The venue is a counterparty, not a mock.** A real QuickFIX/J *acceptor* in its own process. The
OMS dials it exactly as it would dial DSE, and the venue enforces what a venue enforces: orders in
CLOSED/HALTED are rejected by ExecutionReport (OrdStatus 8); in PRE_OPEN they are acked and rest —
nothing crosses; at the **opening bell** the pre-open book uncrosses in one sweep (that *is* the
opening auction). Marketable orders fill in two clips so the OMS walks `OPEN → PARTIAL → FILLED`;
IOC/FOK fill in one shot; non-marketable limits rest until the market comes to them — and
marketability is re-evaluated on every price push, because the OMS syncs live LTPs to the venue
every 3 s. The venue trades against the market the dealer actually sees.

**The session drives everything.** `MarketSessionService` owns the day: DSE hours 10:00–14:30
Asia/Dhaka, Sunday–Thursday, driven by admin buttons or the clock (`market.auto-schedule`). Every
transition is persisted (survives restart), audited, pushed to the venue, and broadcast over SSE.
At close, **DAY orders expire; GTC survives.**

**Where flow comes from.** Dragon Security is seeded with **500 investors** (random BO IDs,
low/mid/high buying power — ready for credit-control testing). Auto-simulation keeps synthetic
liquidity on the books; the harness generates randomised flow across multiple
broker/trader/client contexts, so load looks like a market, not one order repeated.

**ITCH does the full morning ritual.** Start Market emits the real day-start broadcast through the
binary codec: Timestamp, SystemEvent `O`/`S`, tick tables `L`/`M`, CompanyDirectory +
OrderbookDirectory per instrument (`P`/`R` — ISIN, board, lot, tick table, BDT), SystemEvent `Q`
(market hours), TradingAction `T` per book. A feed handler connecting at 09:55 builds its entire
universe from the feed alone. Close mirrors it: `H` per book, then `M`/`E`/`C`.

---

## §4 · QuestDB and Valkey — what they hold and what they change

Every fill lands in `MarketDataService.applyTrade()`: MySQL snapshot row (unchanged, still the
record) + **tick to QuestDB** + **quote to Valkey**.

- **QuestDB (tick time-series).** ILP text on a raw socket (`:9009`) — non-blocking 200k-slot
  queue, one background writer thread, so the fill path never waits. Table `market_tick`,
  day-partitioned, WAL. Candles come back over HTTP `:9000` as one `SAMPLE BY` query per
  timeframe. **Effect:** charts are real OHLC from actual prints — previously ticks were discarded
  and the chart was a synthetic random walk. MySQL is relieved of time-series load.
- **Valkey (hot store, Jedis 5.0.2; Memurai on dev Windows).** Quotes as hashes
  (`oms:quote:{id}`), depth as JSON with 30 s TTL (`oms:depth:{id}`) so stale depth expires rather
  than being served, plus a pub/sub seam for cross-instance fan-out. **Effect:** the live picture
  survives a backend restart and is readable by a second OMS instance — the piece that makes
  horizontal scaling possible, since order books and SSE emitters are otherwise per-JVM.

**Degrade-not-fail, by design and by test.** Kill either store: trading is untouched. QuestDB down
→ candles fall back to the MySQL trade table; Valkey down → quotes fall back to MySQL, depth to
the in-JVM book; both auto-reconnect (15 s ping); one warning per outage, not per order. Neither
holds anything that cannot be rebuilt from MySQL + the feed. **Note for the 8k discussion: these
stores serve the market-data read path — order throughput is bounded by the order write path (§6),
not by QuestDB/Valkey.**

---

## §5 · Technology inventory (verified from the build files)

| Layer | Technology | Role |
| --- | --- | --- |
| Order entry | QuickFIX/J **2.3.1** (core, messages-fixt11, messages-fix50sp1) | FIX 5.0 SP1 / FIXT.1.1 session, NOS/Cancel/Replace, ERs; async file logging |
| Market data | ITCH v2.2 own binary codec + SoupBinTCP + MoldUDP64 | Book reconstruction, day broadcasts, `ItchSequencer` gap recovery, `.itch` record/replay |
| Backend | Java **21**, Spring Boot **3.2.5** (web, data-jpa, validation), Lombok | Risk, matching, portfolio, bonds (price⇄yield), admin/RBAC, SSE hub with 10k-msg/s coalescing |
| Record | MySQL 8 `dse_oms` (connector **8.3.0**), HikariCP | Orders, trades, positions, limits, audit. Pool size = ceiling on concurrent orders |
| Tick store | QuestDB **8.1.1** (standalone process; ILP in, HTTP/SQL out, no client lib) | Every print; candles via `SAMPLE BY`. Optional, no-op fallback |
| Hot store | Valkey/Redis protocol via Jedis **5.0.2** (Memurai on dev) | Live quotes, depth TTL, pub/sub. Optional, in-memory fallback |
| AI | Gemini API + LangChain4j **0.31.0** + all-MiniLM-L6-v2 on-prem | Advisor, EN/বাংলা order bot, semantic search, screen navigation. Offline-capable |
| Frontend | Next.js **14.2.5**, React **18.3.1**, TS **5.5.3**, Tailwind **3.4.7**, lightweight-charts **4.1.3**, flexlayout-react, react-grid-layout | Terminal, dockable desk, depth analysis, dashboards; SSE direct; depth pushed with sequence numbers |
| Feed (aux) | Python FastAPI `:8091` | dsebd.org quotes + supplementary AI; OMS runs without it |
| Testing | JUnit 5, Mockito, AssertJ, MockMvc — **126 tests** | Includes real FIX and real SoupBinTCP sessions over loopback sockets |

Ports: backend **8090** · frontend **3060** · feed **8091** · FIX **15000** · venue control
**15001** · MySQL **3306** · QuestDB **9009/9000** · Valkey **6379**.

---

## §6 · Performance — where it stands, and the path to 8,000 orders/sec

**Today: 292 orders/sec sustained on the dev laptop** through the complete path — risk, MySQL
persistence, FIX round trip, execution apply, audit, SSE. Baseline was 112/sec; the gains were a
correctness bug (the venue filled non-marketable orders) and synchronous logging removed from the
hot path — found with the harness's per-phase timing:

| Phase | What it is | How it scales |
| --- | --- | --- |
| LOOKUP / RISK | ref-data reads + full pre-trade check | CPU-bound; ref data cached (never the kill-switch or session gate) |
| PERSIST / COMMIT | order insert + transaction flush/fsync | grows with concurrency — DB contention; the laptop's biggest cost |
| ROUTE | handoff to QuickFIX/J | grows with thread count — one FIX session serialises sends |

The harness is not the limit (unpaced against a mocked venue it drives 16,000+ submissions/sec),
and it now reports the **round trip**: executions, partial/full fills, exchange rejects, CPU,
heap, threads, queue depth — plus a risk-bypass mode whose delta against a normal run isolates
exactly what validation costs.

**The office-server plan, in order of expected yield:**

1. **Database first.** A real DB server (SQL Server / Oracle / SingleStore / Yugabyte are all on
   the table for UAT) on NVMe with proper group commit attacks COMMIT directly — the laptop pays a
   synchronous fsync per order. Raise `DB_POOL_SIZE` from 10 to ~2× cores (hard ceiling on
   in-flight orders; printed in every harness report).
2. **Unblock insert batching.** JPA `IDENTITY` keys disable JDBC batching; a sequence/hi-lo
   allocator or plain batched JDBC on the hot path turns N round-trips into one.
3. **Parallel FIX.** ROUTE serialises on one session. Real DSE memberships run multiple sessions —
   partition instruments across 2–4 sessions and ROUTE parallelises with them.
4. **Switch on coalescing.** `app.stream.coalesce=true` makes the SSE hub safe at 10k+ msg/sec
   (60 ms last-value-wins flush; order events always immediate).
5. **Then scale out.** Two OMS instances behind a load balancer — Valkey already makes
   quotes/depth cross-instance; the pub/sub seam carries SSE fan-out.

Each step is measured with the same harness on the same dashboard, so every claim about the server
is a number, not an opinion. 8,000/sec is a database-and-sessions problem, not an architecture
problem — the planes are already separated so each dial turns independently.

---

## §7 · Delivered this week — the four gaps from the 13 Jul minutes, closed

- **§14/§15/§16 Perf harness:** randomised generator, max-load mode, venue outcome mix
  (reject/partial 25·50·75/full), validation bypass (dev-gated), full metric set incl. fills,
  executions, CPU, queue depth.
- **§21 Depth:** pushed over SSE with per-instrument sequence numbers and snapshot re-sync;
  changed levels flash. Polling removed.
- **§6 Validation:** two real bugs fixed — a SUSPENDED client account could trade (status was
  never read), and board/trade-window rules (whole lots, Tk 5 lakh block floor, genuine odd lots)
  existed as data but were never enforced. Both now checked on placement *and* amendment.
- **§19 ITCH transports:** SoupBinTCP and MoldUDP64 are real implementations with gap recovery; a
  feed that loses messages says so loudly (`lost>0` on the connectivity screen) instead of serving
  a corrupt book. Writing the tests caught two further bugs (an abandoned gap mis-scored as
  recovered; a retransmit request decodable as a data packet).

---

*Naztech Engineering · dse-oms @ `253ae20` · 126 tests · FIX 5.0SP1 / ITCH v2.2*
