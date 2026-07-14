# PROMPT — Build "DSE-SIM": a standalone Dhaka Stock Exchange simulator

> Paste everything below this line as the FIRST message in a fresh Claude Code session,
> inside an empty project folder.

---

You are building a **standalone exchange simulator that plays the Dhaka Stock Exchange (DSE)** —
every role the real exchange performs — as its own project. It must run as independent
processes that a separate OMS (built by another team) connects to over **real wire protocols**:
FIX for order entry in, ITCH for market data out. The OMS team must never need a line of my
code — only my ports, my dictionaries, and my session settings. If the simulator is correct,
their OMS should later point at real DSE with config changes only.

## Suggested stack (choose equivalents if you prefer, but keep the protocols real)
Java 21, QuickFIX/J (FIX 5.0 SP1 application messages over FIXT.1.1 transport —
`quickfixj-messages-fix50sp1`), plain Java NIO/sockets for ITCH transports, no Spring needed —
these are standalone daemons. Build with Maven. JUnit 5 + AssertJ for tests.

## The five roles DSE performs — all in scope

### 1. Order-entry gateway (FIX acceptor)
- A QuickFIX/J **acceptor** on :15000. Members (OMSes) are initiators with per-broker
  CompID pairs. Support multiple concurrent member sessions.
- Handle NewOrderSingle (35=D), OrderCancelRequest (35=F), OrderCancelReplaceRequest (35=G).
  Respond with ExecutionReports (35=8) and OrderCancelReject (35=9).
- **The ExecutionReport contract — get this exactly right, OMSes break on each point:**
  - Echo **ClOrdID(11) byte-for-byte**; on cancel/replace reports also carry **OrigClOrdID(41)**
    with the member's original ClOrdID (their DB knows nothing else).
  - **ExecID(17) unique across restarts and ≤ 28 chars** (members store it in bounded
    UNIQUE columns; a duplicate silently rolls back their fill).
  - **CumQty(14), LeavesQty(151), AvgPx(6) on every report** — members treat the exchange's
    cumulative figures as authoritative.
  - Never replay a fill. Each LastQty(32)>0 must be a new execution.
- Time-in-force: DAY rests, IOC/FOK fill-or-die immediately, GTC survives the session.

### 2. Central limit order book (the matching engine)
- Per-instrument book, **price-time priority**, partial fills, aggressor pays the passive price.
- **Marketability is re-evaluated continuously**, not once at entry: a resting bid must trade
  the moment the market falls to it. (Our first version answered "is it marketable?" once and
  remembered the answer — resting orders then sat forever while price walked through them.)
- **Continuous trading + opening auction.** In PRE_OPEN the book builds but nothing crosses;
  at the bell, uncross the book (single sweep) — that IS the opening auction.
- DSE microstructure: lot sizes, tick-size table, share categories A/B/N/Z/G, boards
  (Main/SME/Block/Odd-lot), DSE daily **circuit-breaker price bands** around reference price
  (reject orders priced outside the band).

### 3. The trading session (the venue owns the clock, not the client)
- Phases: CLOSED → PRE_OPEN → OPEN → HALTED → CLOSED. DSE hours 10:00–14:30 Asia/Dhaka,
  Sunday–Thursday. Clock-driven, plus a manual override.
- **Enforce phases at the venue**: CLOSED/HALTED → reject with an ER (OrdStatus=8) and a
  human-readable Text; PRE_OPEN → ack and queue; only OPEN crosses. Cancels stay legal in HALTED.
  (A real exchange cannot be talked into trading before the bell — neither can you.)
- At close: expire DAY orders (ERs with ExecType=Expired), run the ITCH end-of-day sequence.

### 4. Market-data generation at volume (many brokerage houses)
- An **agent-based flow generator**: N simulated member firms (e.g. 30–50 brokers, each with
  many trader agents) posting, amending, cancelling and crossing orders directly into the book —
  NOT random ticks painted on a chart. Prices must move because orders trade.
- Configurable: messages/sec (target: sustain 10,000+ book events/sec), instrument universe
  (~400 DSE symbols), volatility regimes, per-broker activity weights, occasional bursts
  (news events), and a **deterministic seed** so a session is exactly reproducible for tests.
- Every synthetic order flows through the SAME matching engine and produces the SAME ITCH
  messages as member orders — one book, one truth.

### 5. Market-data broadcast (ITCH)
- Implement a binary **ITCH-style codec** (DSE uses Nasdaq X-stream ITCH v2.2-family messages):
  Seconds/Timestamp, SystemEvent, tick-table messages, CompanyDirectory, OrderbookDirectory,
  TradingAction, AddOrder (A/F), OrderExecuted (E/C), OrderDelete (D), OrderReplace (U),
  Trade (Q). Big-endian, fixed-width fields. Unit-test encode→decode round-trips byte-for-byte.
- **The morning ritual**: on open, broadcast in order — SystemEvent(start), tick tables,
  CompanyDirectory + OrderbookDirectory per instrument, SystemEvent(market hours),
  TradingAction(T) per book. A consumer connecting before the open must be able to build its
  entire universe from the feed alone. Mirror sequence at close.
- **Two transports, both real:**
  - **SoupBinTCP** server: `[2-byte length][1-byte type][payload]` framing (length INCLUDES the
    type byte — off-by-one here dissolves the stream into noise), Login Accepted carries the
    starting sequence, sequenced-data packets are implicitly numbered, heartbeats both ways;
    a client reconnecting with a sequence number gets a replay from there (that IS the
    recovery mechanism — keep the day's messages to serve it).
  - **MoldUDP64** multicast: 20-byte header (10-byte session, 8-byte seq, 2-byte count),
    length-prefixed messages, heartbeats carry next-seq (how a quiet consumer learns it fell
    behind), count 0xFFFF = end of session, and a **unicast rewind/retransmission server**
    answering "resend N from seq S".
- Record every session to a length-framed capture file; support replay at configurable speed.

### 6. Control plane + observability
- A small HTTP admin API (JDK HttpServer is fine) on :15001: session phase control, halt one
  instrument (TradingAction H/T), inject a price band change, set flow-generator rate/seed,
  and a **/status** JSON (phase, books, sessions connected, msg rates, ITCH seq numbers).
- A per-run **outcome-mix dial** for member testing: of marketable member orders, reject X%,
  partially fill Y% (filling 25/50/75%), fill the rest — so the OMS team can test every branch
  of their execution handling on demand.
- A minimal read-only web console (single static page is fine): session state, top-of-book for
  a chosen instrument, message rates, connected members.

## Engineering rules (each of these cost us real debugging time)
- **No per-order console logging on the hot path** — a Windows console write per order will
  throttle the whole venue; log at DEBUG, write files asynchronously off the session thread.
- Matching on a single thread per book (or one thread, sharded books) — correctness first;
  the book is not the bottleneck, I/O is.
- ITCH consumers must survive YOUR restarts: sequence numbers restart cleanly with a new
  session name; never reuse a session name with different message history.
- Fail loudly, never silently: an unroutable order, an unknown MsgType, a client on the wrong
  dictionary — log it and reject it; silence costs the member team a debugging afternoon.
- Everything configurable has a sane default; the whole thing must run on one laptop with
  `mvn exec:java` + one .bat/.sh per process.

## Build in phases — finish each with green tests before the next
1. **Book + FIX**: matching engine with price-time/partial-fill unit tests; FIX acceptor wired
   to it; an integration test that runs a REAL QuickFIX/J initiator over loopback and asserts
   the full ER contract (ack → partial → filled, cancel, cancel-reject).
2. **Session lifecycle**: phases, Dhaka calendar, pre-open queue + opening uncross, DAY expiry,
   circuit-breaker bands. Tests: order in pre-open does NOT fill until the bell; closed-market
   reject carries the reason.
3. **ITCH codec + day broadcast**: byte-exact round-trip tests; directory-first ordering test.
4. **Transports**: SoupBinTCP with replay-on-reconnect; MoldUDP64 + rewind server; a
   sequencer test proving gap → retransmit → in-order delivery, and duplicate suppression.
5. **Flow generator**: agent-based brokers, deterministic seed, 10k events/sec sustained on a
   laptop, record/replay.
6. **Harness + console**: measure and print orders/sec and ITCH msgs/sec; the admin/status page.

Start now with Phase 1. Create the project skeleton, write a CLAUDE.md capturing these
conventions for future sessions, and show me the matching-engine tests first.

---

*Prepared by Tareq Naushad / Naztech, from the lessons of the Dragon Security OMS build
(`dse-oms`, 126 tests, FIX 5.0 SP1 + ITCH v2.2). The two integration points an OMS team will
care about most are the ExecutionReport contract (§1) and the morning broadcast ordering (§5) —
those are where an OMS and a venue disagree first.*
