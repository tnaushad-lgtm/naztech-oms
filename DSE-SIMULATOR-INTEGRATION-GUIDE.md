# DSE Simulator — OMS Integration Guide

How to connect **any OMS** to the DSE simulator: consume **market data over ITCH (SoupBinTCP)** and
send **orders over FIX 5.0 SP1**, receiving execution reports back. The simulator speaks the same
protocols as real DSE X‑stream, so an OMS written against this guide only needs a **host/port change**
to point at the real exchange later.

> This guide is self‑contained and byte‑accurate against the running simulator. You can implement the
> integration in any language; the wire formats below are all you need.

---

## 0. TL;DR

| You want | Protocol | Transport | Endpoint (example) | Direction |
|---|---|---|---|---|
| Market data (book, trades, index, news) | **ITCH v2.2** | **SoupBinTCP v3.0** (TCP) | `10.33.1.23:9012` | exchange → OMS |
| Send / cancel orders, get fills | **FIX 5.0 SP1** | QuickFIX/J (TCP) | `10.33.1.23:9014` | OMS ⇄ exchange |
| Control / inspect the sim (optional) | HTTP REST | Tomcat | `http://10.33.1.23:8090` | OMS → sim |

Two independent TCP connections. ITCH is **read‑only** (you receive a stream); FIX is **request/response**
(you send orders, receive execution reports). They are correlated only by you (via `Symbol` = order‑book id).

---

## 1. Endpoints & configuration

Replace `10.33.1.23` with your simulator host.

| Service | Default port | Notes |
|---|---|---|
| ITCH SoupBinTCP | `9012` | plain TCP, binds all interfaces |
| FIX acceptor | `9014` | QuickFIX/J, binds all interfaces |
| REST/UI | `8090` (embedded) or Tomcat's `8080` | control only; OMS does not need it |

**Symbols.** Every instrument is identified by an integer **order‑book id** (`1001`–`1300`, ~300
listed securities). In ITCH it is a `u32`; in FIX it is the `Symbol(55)` string (e.g. `"1004"`).
**Do not hardcode the list** — you learn it from the ITCH `R` (Order Book Directory) messages at
start of day. The index book is `9001` (DSEX), delivered via `Z` messages, not tradable.

**Prices.** ITCH prices are integers scaled by `10^priceDecimals` (DSE uses **2 decimals**, from the
`R` message): raw `21050` = `210.50`. On FIX you use **human decimal prices** (`Price(44) = 210.50`);
QuickFIX/J handles the type. Quantities are whole shares everywhere.

---

## 2. Part A — Market data over ITCH (SoupBinTCP)

### 2.1 SoupBinTCP transport

SoupBinTCP is a thin point‑to‑point framing over TCP. **Every packet:**

```
[ u16 length ][ u8 type ][ payload (length-1 bytes) ]
   big-endian     ASCII        type-specific
length = payload length + 1 (it includes the type byte). Max payload ~65534 bytes.
```

**Packet types**

| Dir | Char | Meaning |
|---|---|---|
| C→S | `L` | Login Request |
| C→S | `R` | Client Heartbeat (optional; sim does not require it) |
| C→S | `O` | Logout Request |
| S→C | `A` | Login Accepted |
| S→C | `J` | Login Rejected |
| S→C | `S` | **Sequenced Data** (carries one ITCH message) |
| S→C | `H` | Server Heartbeat (empty, ~1/s when idle) |
| S→C | `Z` | End of Session |

### 2.2 Login handshake

**Login Request payload** — four fixed‑width ASCII fields, space‑padded:

| Field | Width | Content |
|---|---|---|
| Username | 6 | e.g. `OMS` (left‑justified, space‑padded) |
| Password | 10 | blank (10 spaces) — simulator ignores it |
| Session | 10 | blank = latest session; or a specific session id |
| Sequence | 20 | **requested start sequence**, right‑justified integer |

So the full request packet is `[u16 = 47]['L'][46-byte payload]`.

**Requested sequence semantics** (this is how replay & recovery work — there is no separate recovery
protocol):

- `1` → **full replay**: the server streams the entire day from the first message. Use on first connect.
- `N` → **resume at N**: use `lastAppliedSeq + 1` after a disconnect to get exactly the missed messages.
- `0` → only brand‑new messages from now (skip history).

**Login Accepted payload:** `Session(10) + Sequence(20)`, where Sequence is the **actual** first
sequence the server will send. Sequence numbers are **implicit**: after login, each `S` (Sequenced
Data) packet is the next number. Track it yourself: `seq = acceptedSeq; for each S packet: apply(seq++);`.

### 2.3 Reference client (pseudocode)

```
socket = tcp.connect(host, 9012)
send(packet('L', username[6] + spaces[10] + session[10] + rjust(requestedSeq, 20)))
accepted = readPacket()                       // expect type 'A'
nextSeq  = parseInt(accepted.payload[10:30])  // first sequence
loop:
    p = readPacket()
    switch p.type:
        'S': handleItch(nextSeq++, p.payload)  // p.payload is ONE ITCH message
        'H': /* heartbeat, ignore */
        'Z': break                             // end of session
on disconnect:
    reconnect and login again with requestedSeq = lastAppliedSeq + 1   // recovery
```

Persist `lastAppliedSeq` (in memory is fine; on disk if you must survive an OMS restart without a
full replay). Heartbeats need no reply; the connection staying open is enough.

### 2.4 ITCH encoding rules

- **Integers**: unsigned **big‑endian**. `u8`(1), `u16`(2), `u32`(4), `u64`(8). Mask to a wider
  signed type so large values don't read negative.
- **Alpha (fixed)**: ASCII, padded with spaces or NUL; trim trailing space/NUL.
- **Alpha (variable)**: NUL‑terminated ASCII (used only in News text fields).
- **First field of every message** (except the type byte) is a `u32` **timestamp** = nanoseconds
  within the current second. The current second comes from the `T` message. **Full event time =
  `lastTSecond × 1e9 + msgNanos`.**
- **Price**: `u32`, scale by `10^decimals` (2). **Quantity**: `u64` or `u32`, whole shares.

### 2.5 ITCH message catalog

All messages are exchange → OMS. Sizes are bytes; the leading `type(1)` byte is shown separately.
`ts` is the `u32` intra‑second nanos described above.

| Type | Name | Payload layout (after type byte) | What to do |
|---|---|---|---|
| `T` | Seconds | `second u32` | Set `lastTSecond = second`. Timebase for all following msgs. |
| `S` | System Event | `ts u32, group alpha(8), code u8, orderbook u32` | Session phase. **`code='O'` = start of day → reset your books.** |
| `R` | **Order Book Directory** | `ts u32, ob u32, priceType u8, isin alpha(12), secCode alpha(12), ccy alpha(3), group alpha(8), minQty u64, qtyTickId u32, priceTickId u32, priceDecimals u32, delistDate u32, delistTime u32, marketType u8, company u32, listingType u8, sector alpha(12), instrument alpha(12), name alpha(60)` | **Security master.** Record `ob → {secCode, name, priceDecimals}`. This is where you discover every tradable symbol and its price scale. |
| `H` | Trading Action | `ts u32, ob u32, state u8, reason u8` | Per‑book trading state (e.g. `T`=trading, `H`=halted, `P`=pre‑open). |
| `A` | Add Order | `ts u32, orderNo u64, side u8('B'/'S'), qty u64, ob u32, price u32, yield u32` | Add resting order to book `ob`. **Special: `orderNo==0 && qty==0` → `price` is the book's REFERENCE price, not an order.** |
| `F` | Add Order + Participant | same as `A` plus `company u32` before `yield` | Same as `A` (participant id extra). |
| `E` | Order Executed | `ts u32, orderNo u64, execQty u64, matchNo u64` | Partial/full fill of a resting order. **No price on the wire — use the resting order's price.** Reduce the order; record a trade. |
| `C` | Order Executed w/ Price | `ts u32, orderNo u64, execQty u64, matchNo u64, printable u8('Y'/'N'), execPrice u32, ob u32` | Fill at a stated price. Count as a trade only if `printable=='Y'`. |
| `D` | Order Delete | `ts u32, orderNo u64` | Remove the order from its book. |
| `U` | Order Replace | `ts u32, origOrderNo u64, newOrderNo u64, qty u64, price u32, yield u32` | Delete `orig`, add `new` with `qty`/`price`. **No side on the wire — inherit the original order's side.** |
| `Q` | Trade (non‑order/auction/off‑book) | `ts u32, execQty u64, ob u32, printable u8, price u32, yield u32, matchNo u64` | A trade not tied to a visible order. **Special: `matchNo==0 && execQty==0` → `price` is the CLOSE price.** Else count if `printable=='Y'`. |
| `B` | Broken Trade | `ts u32, matchNo u64, reason u8` | Bust a previously reported trade — subtract its qty/turnover (keep `matchNo → {ob,price,qty}` to undo). |
| `I` | Indicative (auction) | `ts u32, theoQty u64, ob u32, bestBid u32, bestOffer u32, theoPrice u32, flags u8` | Pre‑open indicative/theoretical uncross price. |
| `O` | Best Bid/Offer | `ts u32, ob u32, bidPrice u32, bidQty u64, askPrice u32, askQty u64` | Top‑of‑book snapshot (optional convenience). |
| `Z` | Index Value | `ts u32, indexOb u32, value u64` | Index level (DSEX = book `9001`), `value` scaled by 100. |
| `N` | News | `ts u32, ob u32, newsId u32, firmId alpha(30), title alphaNull, reference alphaNull, text alphaNull` | Headline/news for a symbol. |
| `P` | Company Directory | `ts u32, company u32, name alpha(30), extra alpha(30)` | Company master (optional). |
| `L` | Price Tick Size | `ts u32, u32, u32, u32` | Tick table (informational). |
| `M` | Quantity Tick Size | `ts u32, u32, u64, u64` | Lot table (informational). |

### 2.6 The five decode gotchas (get these right and depth is correct)

1. **`E`/`D`/`U` carry only an order number** — no book, no price, no side. Keep a global map
   `orderNo → book` (populated on `A`/`F`), so you can find the owning book; take price/side from the
   resting order. This is the crux of rebuilding depth from the feed.
2. **Reference price** arrives as an `A` with `orderNo==0 && qty==0` — store `price` as the book's
   reference, **do not** add it as liquidity.
3. **Close price** arrives as a `Q` with `matchNo==0 && execQty==0` — store `price` as the close.
4. **De‑dupe auction matches by `matchNo`.** Both sides of an opening‑auction cross can be reported;
   count volume/turnover **once per `matchNo`**.
5. **Timestamps**: reconstruct `lastTSecond × 1e9 + msgNanos`; reset `lastTSecond` on each `T`.

### 2.7 Building your market model

From the folded stream you can maintain, per book: **depth ladder** (price → aggregated qty/orders on
each side), **LTP / open / high / low / close**, **volume / turnover / VWAP**, **1‑minute OHLCV
candles** (bucket trades by `eventNanos / 60e9`), a **trade tape** (time & sales), the **index**, and
**news**. A complete, working reference implementation of exactly this fold lives in
`dse-oms-core` → `market/MarketDataService.java` (and the decoder in `itch/ItchDecoder.java` +
`itch/ByteReader.java`) — copy or port it.

---

## 3. Part B — Order entry & execution over FIX 5.0 SP1

### 3.1 Session

- **Protocol**: FIXT.1.1 transport, application FIX 5.0 SP1.
- **You are the initiator**; the exchange is the acceptor.
- `SenderCompID = OMS`, `TargetCompID = DSE` (from your side).
- **Data dictionaries**: `FIXT11.xml` + `FIX50SP1.xml` (standard QuickFIX/J dictionaries, on your
  classpath). `DefaultApplVerID = FIX.5.0SP1`.
- On logon the exchange sends a **Position Report (AP)** carrying your firm **trading limit**.

**QuickFIX/J initiator config (`oms-fix.cfg`)** — point `SocketConnectHost` at the simulator:

```ini
[default]
ConnectionType=initiator
HeartBtInt=30
ReconnectInterval=5
FileStorePath=/var/oms/fixstore          # absolute, writable; survives restarts (Resend Request)
UseDataDictionary=Y
TransportDataDictionary=FIXT11.xml
AppDataDictionary=FIX50SP1.xml
DefaultApplVerID=FIX.5.0SP1
ResetOnLogon=Y
ValidateIncomingMessage=N

[session]
BeginString=FIXT.1.1
SenderCompID=OMS
TargetCompID=DSE
SocketConnectHost=10.33.1.23
SocketConnectPort=9014
StartTime=00:00:00
EndTime=00:00:00                          # 00:00:00–00:00:00 = session always active
```

Any FIX engine works; QuickFIX/J (Java/C++/.NET/Python) is the reference. If you use it, standard
`Application` + `MessageCracker` is all you need.

### 3.2 Outbound — OMS → exchange

**New Order Single (35=D)**

| Tag | Field | Value |
|---|---|---|
| 11 | ClOrdID | your unique order id (also your correlation key) |
| 55 | Symbol | order‑book id, e.g. `"1004"` |
| 54 | Side | `1`=Buy, `2`=Sell |
| 38 | OrderQty | shares |
| 40 | OrdType | `1`=Market, `2`=Limit |
| 44 | Price | required for Limit; human decimal, e.g. `210.50` |
| 59 | TimeInForce | `0`=Day, `3`=IOC |
| 60 | TransactTime | now |

**Order Cancel Request (35=F)** — `11` new id, `41` OrigClOrdID (order to cancel), `55` Symbol,
`54` Side, `38` OrderQty, `60` TransactTime.

**Order Cancel/Replace Request (35=G)** — `11`, `41` OrigClOrdID, `55`, `54`, `38` new qty, `44`
new price.

**Order Status Request (35=H)** — `11` ClOrdID, `55`, `54`. Exchange replies with an Execution
Report snapshot.

**Trade Capture Report (35=AE)** — report an off‑market/negotiated trade: `571` TradeReportID,
`55`, `31` LastPx, `32` LastQty, `856` TradeReportType (`0`=Submit). Exchange acks with `AR` +
confirms with an `AE`.

### 3.3 Inbound — exchange → OMS

**Execution Report (35=8)** — the workhorse; every order state change arrives as one.

| Tag | Field | Use |
|---|---|---|
| 37 | OrderID | exchange order id |
| 11 | ClOrdID | correlate to your order |
| 150 | ExecType | `0`=New, `F`=Trade(fill), `4`=Canceled, `5`=Replaced, `8`=Rejected, `I`=OrderStatus, `C`=Expired |
| 39 | OrdStatus | `0`=New, `1`=PartiallyFilled, `2`=Filled, `4`=Canceled, `5`=Replaced, `8`=Rejected |
| 32 | LastQty | fill qty (when `ExecType=F`) |
| 31 | LastPx | fill price (when `ExecType=F`) |
| 14 | CumQty | cumulative filled |
| 151 | LeavesQty | remaining (`0` ⇒ done) |
| 54 / 55 | Side / Symbol | |
| 58 | Text | reason on reject |

Rule of thumb: **apply a fill only on `ExecType=F` with `LastQty>0`** (update your position with
`LastQty`/`LastPx`); otherwise just update `OrdStatus`/`CumQty`/`LeavesQty`.

**Order Cancel Reject (35=9)** — `37`, `11`, `41` OrigClOrdID, `39` OrdStatus, `434`
CxlRejResponseTo, `58` Text. Your cancel/replace was refused (already filled or unknown).

**Trade Capture Report Ack (35=AR)** / **Trade Capture Report (35=AE)** — ack + confirm of a trade
you reported via `AE`.

**Position Report (35=AP)** — sent on logon (and on intra‑day change): your firm **trading limit**
in a `NoPosAmt` group (`707` PosAmtType=`LMT`, `708` PosAmt), party in `453` NoPartyIDs.

### 3.4 Order lifecycle

```
OMS ──New Order Single (D)──▶ Exchange
OMS ◀─Execution Report (8) ExecType=0 New────  (accepted, resting)
OMS ◀─Execution Report (8) ExecType=F PartiallyFilled──  (each partial fill)
OMS ◀─Execution Report (8) ExecType=F Filled, LeavesQty=0──  (fully filled)
        …or…
OMS ──Order Cancel (F)──▶ Exchange
OMS ◀─Execution Report (8) ExecType=4 Canceled──   (or Cancel Reject (9))
```

A market order with no opposite liquidity, or a rejected order, comes back immediately as `ExecType=8
Rejected` with a `Text(58)` reason. A reference implementation lives in `dse-oms-core` →
`fix/OmsFixApp.java` (build/parse) + `oms/OrderService.java` (fold reports into orders & positions).

---

## 4. Part C — Putting it together

### 4.1 Recommended OMS architecture

```
              ┌─────────────────────────── your OMS ───────────────────────────┐
 ITCH 9012 ──▶│ SoupBin client → ITCH decoder → MarketDataService (books, tape, │
 (TCP, RO)    │                                   candles, index, positions view)│
              │                                                                  │
 FIX 9014  ⇄  │ FIX initiator (OmsFixApp) → OrderService (orders + positions)    │
              └──────────────────────────────────────────────────────────────────┘
```

- Run the **ITCH client** and the **FIX initiator** as two independent components. They do not talk to
  each other directly — the only shared key is `Symbol` (= order‑book id).
- Market data tells you what's tradable and at what price; FIX is how you act.

### 4.2 Minimal config (Spring‑style example)

```yaml
dse:
  md:                       # ITCH market data
    host: 10.33.1.23
    port: 9012
    username: OMS
    session: ""             # blank = latest
    request-seq: 1          # 1 = full replay on first connect; resume at lastApplied+1 after a drop
    reconnect-delay-ms: 3000
  fix:                      # order entry (host/port also settable in oms-fix.cfg)
    enabled: true
    host: 10.33.1.23        # exchange FIX host
    port: 9014
```

### 4.3 Bring‑up checklist

1. **Reachability**: `Test-NetConnection 10.33.1.23 -Port 9012` and `-Port 9014` succeed.
2. **ITCH**: connect with `request-seq=1`; you should receive a burst of `R` (directory), then `A`/`E`/
   `Q` … Confirm you built ~300 securities and non‑empty depth for active books.
3. **FIX**: logon succeeds (you receive a Position Report `AP`). Your status endpoint shows
   `connected=true`.
4. **Round trip**: send a New Order Single that crosses the book → expect `ExecType=0 New` then
   `ExecType=F` fill(s). The same trade also appears on the ITCH tape.
5. **Recovery**: kill the ITCH socket; on reconnect request `lastApplied+1` and confirm you get only
   the gap, in order.

> Fills require the simulator's market to be **open**. If you see orders resting but never filling,
> open a trading session on the simulator (or run its demo day) so there is crossing liquidity.

### 4.4 Going to real DSE later

Keep ITCH decode + FIX message handling identical. Change only: host/ports, `SenderCompID`/
`TargetCompID`, credentials (real SoupBin username/password), the data dictionaries if the real
venue ships its own, and price‑decimal assumptions (read them from the `R` message rather than
hardcoding `2`).

---

## 5. Appendix

### 5.1 Enum quick reference

| Where | Field | Values |
|---|---|---|
| ITCH `A`/`F` | side | `B`=Buy, `S`=Sell |
| ITCH `C`/`Q` | printable | `Y`=counts as trade, `N`=non‑printable |
| ITCH `S` | code | `O`=start of day (reset) + phase markers |
| FIX | Side(54) | `1`=Buy, `2`=Sell |
| FIX | OrdType(40) | `1`=Market, `2`=Limit |
| FIX | TimeInForce(59) | `0`=Day, `3`=IOC |
| FIX | ExecType(150) | `0`New `F`Trade `4`Canceled `5`Replaced `8`Rejected `I`Status `C`Expired |
| FIX | OrdStatus(39) | `0`New `1`PartiallyFilled `2`Filled `4`Canceled `5`Replaced `8`Rejected |

### 5.2 Symbols / prices

- `Symbol` = order‑book id, integer `1001`–`1300` (discovered from ITCH `R`; ~300 securities). Index
  book `9001` is not tradable.
- ITCH price = integer × `10^priceDecimals` (decimals from `R`, = 2). FIX `Price(44)` = human decimal.
- Quantities are whole shares.

### 5.3 Troubleshooting

| Symptom | Cause / fix |
|---|---|
| Can't connect to 9012/9014 | Firewall; or the acceptor bound to loopback (`SocketAcceptAddress` must be `0.0.0.0`). |
| ITCH connects but no data | You requested `seq=0` (only new) before any activity, or the sim market is closed. Use `seq=1` and run a day. |
| Depth wrong / orders "stuck" | Missing the `orderNo → book` map for `E`/`D`/`U`, or not inheriting side on `U`. |
| Double‑counted volume | Not de‑duping trades by `matchNo`. |
| FIX logon fails | CompID mismatch (`OMS`/`DSE`), wrong `DefaultApplVerID`, or missing data dictionaries. |
| Orders rest but never fill | Market not open / no crossing liquidity on that book. |
| Prices off by 100× | Forgot to scale ITCH price by `10^decimals`. |

### 5.4 Reference implementation

A complete, working OMS integration you can read or port:

```
dse-oms-core/
  net/SoupBinClient.java        SoupBinTCP client (login, sequence, reconnect)
  net/SoupBin.java              transport constants
  itch/ItchDecoder.java         byte-accurate ITCH decoder → ItchListener
  itch/ByteReader.java          big-endian primitives
  market/MarketDataService.java folds ITCH → books/depth/candles/index/news
  fix/OmsFixApp.java            builds NOS/Cancel, parses Execution Reports
  fix/FixInitiatorConfig.java   QuickFIX/J initiator wiring (host/port override)
  oms/OrderService.java         orders + positions from execution reports
```
