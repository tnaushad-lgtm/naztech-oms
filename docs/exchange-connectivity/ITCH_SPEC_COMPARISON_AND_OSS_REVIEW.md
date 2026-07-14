# ITCH: DSE vs Nasdaq spec comparison, and a review of the open-source options

**Prepared for Naz bhai · 14 July 2026** · Sources: the two PDFs in
`C:\naztech_marketing\dragon_security\fix_api_docs\` (read in full) and the four GitHub projects
from the 13 Jul WhatsApp thread (fetched and read at source level).

---

## Headline

**The two specs are not the same protocol, and they are not compatible at the byte level.**

Every ITCH library on that list — Juncture, Kodiak, runk/itch, lunary — implements **Nasdaq
TotalView-ITCH 5.0** (the US equities feed). DSE runs **Nasdaq X-stream / INET ITCH v2.2** (the
international platform, the same family as Nasdaq OMX Nordic). They share a name, a philosophy and
their transports, but **not their wire format**. Feeding DSE bytes to a TotalView parser does not
produce errors — it produces *plausible-looking garbage*, which is the worse failure.

**Our own ITCH implementation is already a byte-exact, complete DSE X-stream v2.2 codec** — all 19
message types, every field offset verified against the spec today. On the axes that matter for
Dhaka it is **ahead of all four projects**. We should not adopt any of them wholesale. There are,
however, **five real gaps in our code** and **two genuinely useful things to take** from the
Nasdaq world. Details below.

---

## 1. Why the two specs are incompatible

The difference starts at **byte 1 of every single message**.

| | **Nasdaq TotalView-ITCH 5.0** | **DSE X-stream ITCH v2.2** |
|---|---|---|
| **Common header** | Type(1) + **Stock Locate(2)** + **Tracking Number(2)** + **Timestamp(6)** = **11 bytes** | Type(1) + **Timestamp(4)** = **5 bytes** |
| **How time works** | 6-byte nanoseconds-since-midnight **in every message** | 4-byte nanos **since the last `[T]` Timestamp-Seconds message** — a separate message type DSE has and Nasdaq does not |
| **Instrument key** | Stock Locate (2-byte int) **+ 8-char symbol** | **Orderbook ID (4-byte int)** — no symbol on the wire |
| **Price** | 4-byte, **fixed 4 decimals** | 4-byte, **decimals vary per instrument**, read from the `[R]` Orderbook Directory |
| **Quantity** | 4-byte | **8-byte** |
| **Bonds / yield** | *none* | **Yield fields on `[A]`, `[F]`, `[U]`, `[Q]`** — DSE trades bonds on yield |
| **Transports** | SoupBinTCP, MoldUDP64, BinaryFILE | SoupBinTCP v3.0, MoldUDP64 (spec §1.3) — **the same** |

A TotalView parser reads bytes 1–2 as a Stock Locate and bytes 5–10 as a timestamp. DSE has a
4-byte timestamp sitting at byte 1. **Every field after the first byte is misaligned.** This is not
a porting exercise; it is a different protocol that happens to share message-type letters.

### Message sets differ too

**DSE has, and Nasdaq does not:** `[T]` Timestamp-Seconds, `[L]` Price Tick Size table, `[M]`
Quantity Tick Size table, `[P]` Company Directory, `[Z]` Index Value, `[O]` Best Bid & Offer, and
**yield on the order/trade messages**.

**Nasdaq has, and DSE does not:** Reg SHO `[Y]`, LULD Auction Collar `[J]`, Market-Wide Circuit
Breaker `[V]`/`[W]`, IPO Quoting `[K]`, MPID Position `[L]`, Operational Halt `[h]`, Retail
Interest `[N]`, **and `[X]` Order Cancel (a partial size reduction)** — DSE only has `[D]` Delete
(full removal). These are all **US regulatory constructs (SEC rules)**. They have no meaning in
Bangladesh and there is nothing to lose by not having them.

**Conclusion on the specs:** they are cousins, not twins. Anything Nasdaq-specific in those
libraries is not a feature we are "missing" — it is US regulation we do not have.

---

## 2. The four GitHub projects — verdict on each

| Project | Language / License | ITCH variant | Transports | Order book | Simulator | Verdict |
|---|---|---|---|---|---|---|
| **paritytrading/juncture** | Java 11, **Apache-2.0** | TotalView 5.0 | **No** (lives in sibling repo *nassau*) | No | No (but has encoders) | ⚠️ **Reference only** |
| **ben-haim/Kodiak** | Java, **NO LICENSE** | TotalView 5.0 | MoldUDP64 inline, no SoupBin | **Yes** (5 levels) | No | ❌ **Rule out** |
| **runk/itch** | TypeScript, MIT | TotalView 5.0 (11 of 23 msgs) | No — file reader only | Minimal | No | ❌ **Not usable** |
| **lunyn-hft/lunary** | Rust, **AGPL-3.0** | TotalView 5.0 | No | No | No | ❌ **License blocker** |

**Juncture** — the one Naz bhai favoured — is *5 Java files*. It is a clean, well-written
ByteBuffer codec for TotalView 5.0 and nothing else. No transports, no order book, no simulator.
Its last functional release was 2022; recent commits are all Dependabot noise. As a **style
reference** it is good; as a dependency it gives us nothing we don't have.

**Kodiak must be ruled out on two hard grounds**, independent of protocol: it has **no LICENSE file
at all** (legally all-rights-reserved — we cannot use it in a commercial broker product), and its
`pom.xml` depends on `com.clearpool:MessageObjects`, a **private snapshot that was never published
to Maven Central**. It will not build from a clean checkout. Last commit: **2014**. It is a dump of
a defunct firm's internal repo.

**lunary** is AGPL-3.0. Linking it into our backend would impose copyleft on our server-side code
via the network clause. That alone ends the conversation for a commercial product, before we even
get to the fact that it is a file parser with no transports, no book, and no FFI surface for Java.

---

## 3. What IS worth taking

Two things, both real:

### (a) `paritytrading/nassau` — for transport confidence, not for adoption
Nassau (Apache-2.0, same author as Juncture) has production-grade **SoupBinTCP and MoldUDP64**
implementations, including a **MoldUDP64 request/rewind server** and a `MessageStore`. Our
transports are already written and tested, but SoupBinTCP and MoldUDP64 are **identical across
Nasdaq and X-stream** — so nassau is the best available cross-check of our framing before we point
at real DSE. Worth reading their `MoldUDP64Server`/`RequestServer` against ours.

### (b) The free Nasdaq sample data — as a load-test corpus
`https://emi.nasdaq.com/ITCH/Nasdaq%20ITCH/` — confirmed publicly downloadable, **no credentials**
(e.g. `01302020.NASDAQ_ITCH50.gz`, 5.6 GB, HTTP 200). These are a full trading day of real
exchange traffic.

They are **useless as DSE protocol fixtures** (wrong variant — they would decode to garbage), but
they are excellent for **throughput and framing tests**: a real day at real message rates to prove
our decoder loop, our MoldUDP64 gap recovery, and our book reconstruction hold up at
10,000+ msg/sec. Budget the disk — uncompressed a day is 30–60 GB.

### Also worth knowing: Nasdaq has **GLIMPSE**, DSE's spec does not mention one
GLIMPSE is Nasdaq's point-to-point SoupBinTCP **snapshot service** — you connect mid-day, get the
current state of the book, then join the live feed. The DSE ITCH v2.2 spec describes no equivalent.
Our SoupBinTCP reconnect-and-replay covers the same need, but **this is a question to put to DSE**:
*"if our feed handler starts at 11:00, how do we get the current book — replay from the open, or is
there a snapshot service?"* Replaying a full day just to see the book is a real operational cost.

---

## 4. Our own implementation — audited against the spec today

I checked **every message, every field offset, every length** in
`backend/src/main/java/com/naztech/oms/exchange/itch/ItchCodec.java` against the DSE spec's tables.

**All 19 DSE message types are implemented byte-exactly:**
`T S L M P R H A F E C B D U I Q O Z N` — including the 171-byte `[R]` Orderbook Directory with
every field in the right place (ISIN, Sec Code, Currency, Group, Min Qty, tick-table cross
references, Price Decimals, Delisting, MarketType, ListingType, Sector, Instr, Security Name,
Maturity Date, **Yield Decimals**), the yield fields on `[A]`/`[F]`/`[U]`/`[Q]`, and the
null-terminated variable-length `[N]` News Item.

The three special cases in spec §5 and §6.4 are all handled:
- `[A]` with OrderNumber == 0 && Quantity == 0 → **reference-price update**, not a resting order ✅
- `[Q]` with MatchNumber == 0 && Quantity == 0 → **close price**, not a trade ✅
- Price `0x7FFFFFFF` → **market order sentinel**, excluded from the book ✅

Plus SoupBinTCP, MoldUDP64, sequence-gap detection with retransmission, book reconstruction,
invariant checking, and record/replay. **No project on the list has this much.**

### The five real gaps I found

| # | Gap | Why it matters |
|---|---|---|
| **1** | **`PRICE_DECIMALS` is hardcoded to `2`** (`ItchGateway.java:48`) | Spec §6.5 says prices must be scaled by **the decimals from each instrument's `[R]` message**. We are right *by luck* for DSE equities (2 dp) and **wrong for bonds**, whose yield carries 4 decimals. Against a real feed with a 3-decimal instrument, every price on the ladder is off by 10×. **This is a production bug.** |
| **2** | **The feed contradicts our own session state** | Per Table 33, `[S]` event **'S' = PRE-OPEN** and **'Q' = OPEN**. Our `ItchDayBroadcast.open()` emits **both in the same burst**, even when `MarketSessionService` is in PRE_OPEN. So the feed announces "market hours started" while the OMS is still pre-open. Should be: Start Market → `'S'` only; opening bell → `'Q'`. |
| **3** | **`[I]` Indicative Price/Quantity is never emitted** | The record and codec exist; nothing sends it. DSE sends `[I]` **throughout the pre-open auction** carrying the **Theoretical Opening Price**, the theoretical opening quantity, and the Cross Type (`'O'` open / `'I'` intraday / `'C'` closing auction). This is *the number a dealer watches before the bell*, and we don't show it. **Best feature to add.** |
| **4** | **Halt uses a trading state the spec doesn't define** | We send `[H]` TradingAction with state `'H'`. DSE's alphabet is **`'T'` trading / `'V'` suspended**. And per Table 33 a halt should emit **SystemEvent `'A'`**, resume **SystemEvent `'B'`** — we emit neither. We also never send `'I'` (Accepting/Holiday) or `'V'` (Post-Close). |
| **5** | **No Broken Trade `[B]` handling** | The codec has it; nothing emits or consumes it. **If DSE busts a trade, our positions and P&L stay wrong for ever.** This is an OMS gap, not just an ITCH one — there is no trade-bust path anywhere in the system. |

Lower priority: we ignore `[Z]` Index, `[N]` News and `[O]` BBO on the inbound path (we synthesise
index drift instead of consuming real `[Z]` values), and we model **one feed** where DSE publishes
**six channels** (TotalView, TotalView-Extended with participant IDs, News, Index, Basic/BBO, Last
Sale) — a subscription decision to make before go-live.

---

## 5. Recommendation

1. **Do not adopt any of the four projects.** Three fail on licence or build; all four are the wrong
   ITCH variant. Our codec is more complete and more correct *for Dhaka* than any of them.
2. **Fix gap #1 (hardcoded price decimals) before any real-feed work.** It is small and it is a
   genuine bug.
3. **Build the pre-open auction properly** — gaps #2 and #3 together. Emit `'S'` at pre-open, stream
   `[I]` indicative prices during the auction, emit `'Q'` at the bell. This gives the dealer an
   Indicative Open price on screen, which is a visible, demonstrable feature and is exactly what the
   real exchange does.
4. **Add trade-bust handling** (gap #5) — it is a correctness hole in the OMS, not just the feed.
5. **Read `paritytrading/nassau`'s SoupBinTCP/MoldUDP64** as a cross-check of our transport framing.
   Same protocols, independent implementation, Apache-2.0.
6. **Pull one Nasdaq sample day** and use it as a throughput corpus for the decoder and the gap
   recovery — free, real, and 10k+ msg/sec.
7. **Ask DSE two questions:** (a) is there a **snapshot/GLIMPSE-style service** for a mid-day join,
   or must a handler replay from the open? (b) which **ITCH channels** will Dragon Security be
   entitled to — TotalView, or TotalView-Extended with participant IDs?

**The short answer for Naz bhai:** the Nasdaq libraries look attractive because ITCH is a famous
name, but DSE's ITCH is a different dialect of it. We are not missing features by not using them —
the features they have that we don't are US securities regulation. What we *are* missing is a
handful of things in DSE's own spec that we simply haven't built yet, and those are listed above.
