# Phase 3 — ITCH v2.2 decoder + local simulator (DONE)

**Goal:** understand and generate the DSE binary market-data feed (ITCH v2.2) so the live
market-data pipeline can be built and demoed without the real DSE feed. Status: ✅ compiles,
**29/29 tests pass** (Java 21). No existing module touched.

## What was added — package `com.naztech.oms.exchange.itch`
| File | Purpose |
|---|---|
| `Itch.java` | Immutable record per ITCH message (all **19** types: T S L M P R H A F E C B D U I Q O Z N), exact wire fields + market/reference sentinels |
| `ItchCodec.java` | Binary **encode/decode** — big-endian, fixed-width, null-terminated News; exact inverses |
| `ItchOrderBook.java` | Rebuilds one book from Add/Execute/Delete/Replace → aggregated OMS `Depth` (prices scaled by `10^priceDecimals`) |
| `ItchSimulator.java` | Emits a *self-consistent* stream: opening sequence (T/S/R/reference-price) + continuous order-book & trade flow, deterministic by seed |

## Verified (9 new tests)
- **`ItchCodecTest`** — every message type round-trips (encode→decode→equal), encoded size matches the spec, the `0x7FFFFFFF` "market" sentinel survives, fixed Alpha fields pad/trim correctly, full 19-type coverage.
- **`ItchOrderBookTest`** — builds scaled depth, aggregates same-price levels (qty + order count), applies Execute/Delete/Replace correctly, ignores reference-price/market orders, and rebuilds a **consistent** ladder (bids↓, asks↑, never negative) from a 800+-message simulator stream.

## Deferred to Phase 4 (market-data integration) — intentional
- Introduce the `MarketDataGateway` seam and refactor `MarketDataService.ingest()` to implement it.
- `ItchGateway` wiring the decoded stream into `market_data` / `trade` / depth (so the UI shows live ITCH).
- SoupBinTCP / MoldUDP64 frame transports (point-to-point + multicast) and binary replay from recorded files.

## Note
The whole backend is now **Java 21** (was 17) to match the dev/deploy environment and avoid a mixed baseline.
