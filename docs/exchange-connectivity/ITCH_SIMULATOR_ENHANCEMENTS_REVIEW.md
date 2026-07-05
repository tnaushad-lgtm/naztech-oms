# ITCH Simulator — Enhancement Review & Comparison Report

**Scope:** Evaluate ten candidate capabilities against our in-house DSE ITCH v2.2 simulator, using
`martinobdl/ITCH` and `bbalouki/itch` for *implementation ideas only*. No third-party code is copied;
our additive-adapter architecture (business layer depends only on `MarketDataGateway`) is preserved.
**Rule:** recommend only changes that clearly improve **correctness**, **realism**, or **testability**,
then implement the approved items incrementally (each phase ends compile → test → commit).

---

## 1. The two reference tools (ideas, not code)

### martinobdl/ITCH — C++ TotalView-ITCH 5.0 book reconstructor
- Reads **recorded binary ITCH files** (`Reader`) at 1–2 M msg/s and reconstructs full-depth books.
- `OrderPool` keeps every live order by id so **reference-only messages** (Delete/Execute, which omit
  side/price/stock) can be applied — the core difficulty of ITCH.
- **Google Test** suites assert the *exact* book string after a sequence of `modifySize()` calls
  (`OrderBook_test`, `OrderPool_test`, `Order_test`, `Reader_test`) → order-book consistency testing.
- Writer emits CSV (messages + book snapshots), not binary.
- Not present: scenario/phase simulation, error injection, seeded generator, message-builder API.

### bbalouki/itch ("itchfeed") — Python ITCH 5.0 parser + object model
- `MessageParser.parse_file()` (chunked) and `parse_stream()` (from raw socket bytes) with
  **length-prefix framing** (`0x00` + length) — a simplified SoupBinTCP frame.
- Every message class has **`to_bytes()`** (encode) and **`decode()`** (fixed-point price scaling,
  48-bit timestamp split, ASCII de-padding).
- **`create_message(type, **kwargs)`** — a first-class **message-builder API** documented for
  “testing, simulation, data generation”.
- **`test_create_message_and_pack`** — parametrized over *all* message types: `create → to_bytes →
  re-parse → assert field equality` (round-trip validation).
- Rich message set incl. **MWCB circuit-breaker (V/W)**, **NOII auction imbalance (I)**, halts (H).
- Error taxonomy (unexpected byte / unknown type / malformed) with skip-vs-halt strategies.

---

## 2. What we already have (baseline)

| Component | Capability already covered |
|---|---|
| `ItchCodec` (encode/decode + `sizeOf`) | Big-endian fixed-width codec, exact inverses; `ItchCodecTest` round-trips |
| `ItchGateway.wire()` = `decode(encode(m))` | **Live** encode→decode round-trip on *every* simulated message |
| `ItchOrderBook` (`Map<orderNumber,Order>`) | Book reconstruction **with reference tracking** (A/F/E/C/D/U) |
| `ItchSimulator(seed)` | **Deterministic seeded** self-consistent stream; only executes/deletes orders it added |
| `ItchGateway.bootstrap()` from `securityRepo` | Trades **real DSE tickers** (GP, BRACBANK, … ~400) with real ref prices/tick sizes |
| `MarketDataGateway` interface | Business-layer seam — UI/services never touch ITCH types |

So five of the ten asks are **partially or fully in place**. The review below focuses on the *gaps*.

---

## 3. Capability-by-capability assessment

Legend — **Verdict:** ✅ Adopt · 🟡 Adopt (light) · 🔵 Phase later · ⚪ Already have (harden only).
Value tags: **[C]** correctness · **[R]** realism · **[T]** testability.

| # | Capability | Today | Gap | Verdict | Value | Effort |
|---|---|---|---|---|---|---|
| 1 | Deterministic replay from recorded sessions | live sim only | no record/replay of a byte session | ✅ | C·T | M |
| 2 | Scenario simulation (Open Auction / Continuous / Close / Circuit-Breaker) | one opening seq | no phase state-machine, no halt/CB/closing auction | ✅ | R | M |
| 3 | Encode→decode round-trip validation | `wire()` + partial test | not exhaustive over all 19 types + `sizeOf` invariant | ⚪→🟡 | C·T | S |
| 4 | Order-book consistency validation | book builds | no invariant checks (crossed book, qty conservation) | ✅ | C·T | S |
| 5 | Deterministic seed generator | yes, hardcoded `20260703L` | seed not externalized/configurable | ⚪→🟡 | T | XS |
| 6 | Bangladesh security profiles | real tickers, uniform behaviour | no per-name volatility/spread/liquidity profile | 🟡 | R | M |
| 7 | Error injection (dup / gap / reorder / loss) | none | no chaos layer; seq-gap/loss need a sequenced transport first | 🔵 | C·T | M |
| 8 | Binary recording & replay | codec only | no length-framed session writer/reader | ✅ | C·T | M |
| 9 | Message-builder API | record ctors in sim | no single validated builder facade | 🟡 | T | S |
| 10 | Transport abstraction (sim ↔ SoupBinTCP/MoldUDP64) | `MarketDataGateway` seam; source hardwired to sim | no `ItchSource` port inside the gateway | ✅ | C·R·T | M |

### Notes on the judgement calls
- **#10 is the keystone.** Today `ItchGateway` instantiates `ItchSimulator` directly. Introducing an
  `ItchSource` port (`Stream<Itch.Msg>` / push callback) with a `SimulatorSource` implementation makes
  **replay (#1/#8)** and a future **SoupBinTcpSource / MoldUdp64Source** drop-in with zero change to
  `ItchGateway`, `MarketDataService`, or the UI. Everything else composes on top of it.
- **#1 and #8 are one deliverable** — a **SoupBinTCP-style length-framed** binary recorder (2-byte BE
  length + payload) writing `.itch` files, plus a `FileReplaySource` that re-emits `Itch.Msg`. Record a
  sim or (later) a live session; replay it deterministically into tests or the gateway. This is also the
  literal on-ramp to real DSE capture.
- **#3 and #5 are hardening**, not new capability — we already round-trip live and seed deterministically;
  we just make the test exhaustive (`assertArrayEquals(bytes, encode(decode(bytes)))` + `len == sizeOf`
  for all 19 types) and lift the seed into `ItchProperties` (`itch.seed`).
- **#7 splits.** Message-level chaos (duplicate / reorder / corrupt-then-reject) is useful now at the
  source boundary to prove the decoder + book stay sane. True **sequence-gap / packet-loss** injection is
  only meaningful once the **MoldUDP64 sequence layer** exists, so that part is explicitly deferred with
  the real transport (honest scoping — no silent “done”).
- **#6** reuses DB data we already hold (asset class, category A/B/N/Z, price, lot size) to give
  blue-chips (GP, BRACBANK, WALTON, BATBC) tight spreads + deep books and thin small-caps wide spreads —
  a real, visible realism win for the demo, low risk.

Nothing is rejected outright; #7 is the only item partly deferred, and for an honest reason.

---

## 4. Recommended incremental plan (each phase: additive → compile → test → commit)

- **Phase A — Transport/Source port (#10).** Add `ItchSource` interface; `SimulatorSource` wraps the
  existing `ItchSimulator`; `ItchGateway` consumes an `ItchSource`. **Zero behaviour change** (pure
  refactor behind a seam). *Keystone for everything after.*
- **Phase B — Record & replay (#8 + #1).** `ItchSessionWriter` (length-framed `.itch`) + `FileReplaySource`.
  Optional `itch.record=true` captures the running feed; a replay source feeds tests/gateway.
- **Phase C — Correctness harness (#3 + #4 + #5 + #9).** Exhaustive round-trip test (19 types + `sizeOf`),
  `ItchBookInvariants` validator (no crossed book, non-negative qty, depth-qty conservation) used in tests
  and as an opt-in runtime assert, seed → `ItchProperties`, and an `ItchMessages` builder facade.
- **Phase D — Realism (#2 + #6).** Session **phase state-machine** (Pre-Open → Opening Auction with
  `Indicative`/NOII → Continuous → optional Halt/Circuit-Breaker → Closing Auction → Close, emitting the
  right `SystemEvent`/`TradingAction`) + **per-security behavioural profiles**.
- **Phase E — Resilience (#7, partial).** Message-level chaos injector at the source boundary now;
  sequence-gap / packet-loss deferred to land with the SoupBinTCP/MoldUDP64 transport.

**Architectural guarantee:** every phase is additive behind `MarketDataGateway` / the new `ItchSource`
port. No OMS service, controller, or UI component changes. FIX order-entry is untouched.

---

## 4a. Implementation status (delivered 2026-07-04)

Approved scope built and green (**41/41 tests**, additive, default behaviour unchanged) — see
[`PHASE-ITCH-SOURCES.md`](PHASE-ITCH-SOURCES.md):

- ✅ **Phase A (#10)** — `ItchSource` transport seam + `SimulatorSource`; `ItchGateway` now source-driven.
- ✅ **Phase B (#8 + #1)** — `ItchSessionWriter` (raw-frame `.itch` recorder), `FileReplaySource`,
  `RecordingSource`; byte-for-byte record/replay; the raw-frame recorder is the live-capture hook for #10.
- ✅ **Order-book invariants (#4)** — `ItchBookInvariants` (structural + crossed-market), opt-in runtime.
- ✅ **Seed externalised (#5)** — `itch.seed` (bonus; supports reproducible capture/replay).

**Deferred by decision** until after DSE certification (unless needed for a demo/test): scenario/phase
engine (#2), Bangladesh behavioural profiles (#6), chaos/error injection (#7), and the lighter test/builder
polish (#3 exhaustive-table, #9 facade).

## 5. What we deliberately do NOT copy
- No third-party source is imported; these repos are **read for ideas** (their licences are MIT / open,
  but our implementation stays original and DSE-v2.2-specific, not NASDAQ-5.0).
- We keep our **19-type DSE v2.2** message set (not NASDAQ 5.0’s type list) — e.g. our `Q` = Trade,
  `Z` = Index Value, `R`/`P` DSE directories — so we do **not** adopt bbalouki’s NASDAQ type codes.
- We do not replace the order book, codec, or gateway; we wrap and extend them.
