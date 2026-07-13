# Phase 8 — closing the gaps from the 13 Jul 2026 minutes

Four items from the meeting with Naz bhai, on the OMS / FIX / ITCH / performance side (the CCD
items are tracked separately). Each is described here as *what was actually missing* and *what now
exists*, because in three of the four cases the OMS looked finished and was not.

---

## 1. The performance harness (§14, §15, §16)

**What was missing.** The old harness placed the same order, for the same stock, from the same
account, at the same price, thousands of times. That is a benchmark of row contention on three
database rows, not of an OMS. It also reported only what went *out* — orders per second — and
nothing about what came *back*. A rate with no executions behind it is not a working system; it is
a fast way to lose orders.

**What exists now.**

| §14 asked for | Where it lives |
| --- | --- |
| Random stock, quantity, price | `LoadTest.Request.randomSecurity / randomQty / randomPrice` |
| Multiple broker / trader / client contexts | `randomAccount`, `randomDealer` — pools resolved once, in `LoadTestService.buildPool` |
| Controlled rate **and** max load | `targetPerSec` (wall-clock slot pacing) vs `maxLoad` (unpaced; reports the ceiling) |
| Configurable outcome mix at the exchange | `LocalExchange.OutcomeMix` — `rejectPct` / `partialPct` / `partialFillPct` (25 / 50 / 75), pushed to the venue's `POST /config` before the run |
| Validation-bypass mode (§16's "first test") | `OrderService.place(req, actor, bypassRisk)` — refuses to bypass anything unless `app.loadtest.enabled=true`, so it cannot exist in UAT or production |
| Orders generated / submitted / accepted / rejected | `LoadTest.Status` |
| **Partial fills, full fills, executions processed** | `ExecutionStats`, fed from `ExecutionService` (FIX) and `SimulatedMatchingEngine` |
| **CPU, memory, thread count** | `ExecutionStats.resources()` |
| **Queue depth** | `generated − submitted` = orders inside the OMS right now. Plus `backlog`: how far the pacer has fallen behind the clock. |

The difference between a bypass run and a normal one **is** the cost of pre-trade risk. That is the
only way to tell a slow database from a slow rule engine, and without it you optimise the wrong one.

Quantities are drawn in whole lots. An off-lot quantity would simply be rejected by the lot check,
and the run would measure the reject path.

---

## 2. Market depth: pushed, not polled (§21)

**What was missing.** The ladder polled `/depth` every two seconds. Two seconds is an eternity on a
book — levels appear and are gone again inside the gap — so the dealer was shown a book that had
already stopped being true, while the OMS answered the same question sixty times a minute per open
terminal whether or not anything had changed. Both halves of that are wrong.

**What exists now.** `DepthBroadcaster`:

- a terminal registers interest (`POST /api/market/{id}/depth/watch`); nothing is computed or sent
  for a book nobody is looking at, and the registration lapses on its own, so a closed tab stops
  costing anything without having to say so;
- the book is pushed on the SSE stream as a `depth` event **only when it has changed** — an unchanged
  book costs one book build and a string compare, and no bytes on the wire;
- every push carries a **sequence number**. A client that sees it skip has missed a frame and
  re-syncs from `GET /api/market/{id}/depth/snapshot`, which returns the book *and* the sequence it is
  current as of;
- the UI (`useDepth`, `DepthLadder`, `FullLadder`) **flashes the levels whose size moved**. On a live
  book the numbers change faster than anyone can watch, so the ladder has to point at what moved
  rather than leave it to be noticed.

That is the same snapshot-plus-incremental contract the ITCH feed itself uses (§19), for the same
reason: a book you cannot resynchronise is a book you cannot trust.

---

## 3. Validation gaps (§6)

Two of these were real bugs, not omissions.

**`client_account.status` was never read.** The column existed, the admin screen set it, and pre-trade
risk ignored it — so a SUSPENDED client's orders were validated, routed and filled exactly like an
active one's. This is the kind of gap that is invisible until a regulator asks why.
Now: `RiskService` rejects on it, in both `check` and `checkAmend`.

**`oms_order.trade_window` was a label, not a rule.** It had been stored since the first schema and
shown in the blotter, and nothing ever checked it. `TradeWindowRules` now enforces DSE's:

- **Normal / Spot** — whole market lots.
- **Block** — a floor (`rms.block-min-value`, default Tk 5 lakh). Below it the trade belongs in the
  public market; sending it to the block market is how a broker gets a letter from the exchange.
- **Odd-lot** — exists precisely for holdings *smaller* than one market lot. An odd-lot order for a
  whole lot is a contradiction. And where the market lot is one share — as it is for every DSE equity
  in the seed today — there is no such thing as an odd lot at all, and the OMS says so plainly rather
  than accepting the order and letting the exchange refuse it.
- An **unknown** window is rejected rather than quietly treated as normal.

An amend re-checks all of it: an amend can walk an order out of its own market (a block trade amended
down below the floor), and the original code would have let it.

---

## 4. ITCH: transports and gap recovery (§19)

**What was missing.** `itch.transport` accepted `soupbintcp` and `moldudp64` and did nothing with
them — the config option existed, the implementation did not, and any value but `simulator` silently
simulated.

**What exists now.**

- **`SoupBinTcp`** — the TCP framing: `[2-byte length][type][payload]`, where the length counts the
  type byte. The sequence number is *implicit* (it appears once, in Login Accepted), which is why
  recovery on this transport is a **reconnect**: `SoupBinTcpSource` logs back in asking for the
  sequence it still wants, and the server replays. A dropped connection and a missed message are the
  same event, handled the same way.
- **`MoldUdp64`** — the multicast framing: a 20-byte header (session, 8-byte sequence, 2-byte count)
  and length-prefixed messages. Count `0` is a heartbeat (which still carries the sequence — during a
  lull it is the *only* way to learn you have fallen behind) and `0xFFFF` is end-of-session.
  `MoldUdp64Source` joins the group and, on a gap, sends a **retransmission request** to the rewind
  server naming the sequence and count it wants back.
- **`ItchSequencer`** — the part that matters. It delivers in order and only in order; **buffers**
  anything early rather than applying it out of sequence; **drops** duplicates so a retransmission that
  raced the original does not book the same trade twice; and reports the gap so the transport can ask
  for it again.

  When the retransmit never comes and the buffer fills, it does not quietly carry on. It **declares the
  loss**, says so at ERROR, and resynchronises. A visible resync is recoverable; a quietly corrupt book
  is not — an *Add* whose *Delete* was dropped leaves phantom liquidity on the ladder for the rest of
  the day, and a dealer trading against it is trading against something that is not there.

Feed health (`gapsDetected` / `gapsRecovered` / **`lost`**) is on `/api/admin/connectivity/status`.
`lost > 0` is the number that matters: not "the feed hiccupped", but "we asked and never got it", after
which every book is suspect and wants a snapshot.

A live transport that cannot connect **throws**, and `ItchGateway` falls back to the simulator with a
loud log line. It must never fall back silently: a feed producing nothing looks exactly like a market
with no trades in it, and those are very different situations.

---

## Configuration

```properties
# depth push
app.depth.push-ms=400
app.depth.watch-ttl-ms=45000

# board rules
rms.block-min-value=500000

# raw-throughput mode (dev only — it is what gates the risk bypass)
app.loadtest.enabled=true
```

```yaml
itch:
  transport: soupbintcp        # or moldudp64
  host: <feed host>            # soupbintcp
  port: <port>                 # or the multicast port for moldudp64
  group: <multicast group>     # moldudp64
  session: <session name>      # blank accepts any
  username: <login>            # password goes in secrets.properties
  rewind-host: <rewind server> # moldudp64 retransmission; without it a gap cannot be recovered
  rewind-port: <port>
```

## Tests

126 backend tests, all green. New: `ItchSequencerTest` (7), `ItchTransportTest` (10 — including a
real SoupBinTCP session over a loopback socket), `DepthBroadcasterTest` (6), `TradeWindowRulesTest`
(7), plus outcome-mix cases in `LocalExchangeTest` and randomisation/bypass/max-load cases in
`LoadTestServiceTest`.

Two of those tests found real bugs while being written:

- the sequencer was scoring a **given-up-on** gap as a **recovered** one — so a feed that had lost
  messages would have reported "1 gap, 1 recovered, healthy", which is exactly the lie the class exists
  to prevent;
- a MoldUDP64 retransmission *request* is a bare header whose count is a **demand**, not a payload —
  decoding one as a data packet goes looking for messages that were never there.
