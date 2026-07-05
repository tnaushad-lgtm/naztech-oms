# ITCH Sources — Transport seam + Record/Replay + Book Invariants (DONE)

**Status:** ✅ additive · **41/41 backend tests pass** (Java 21) · `@SpringBootTest` binds the new config ·
default behaviour unchanged (still simulates). Scope approved 2026-07-04 from
[`ITCH_SIMULATOR_ENHANCEMENTS_REVIEW.md`](ITCH_SIMULATOR_ENHANCEMENTS_REVIEW.md): Phase A + Phase B +
the order-book invariant validator; realism/scenario/chaos deferred to post-DSE-cert.

## What was delivered

### Phase A — Transport/source seam (`ItchSource`)
- **`ItchSource`** interface — `open()` (session-start seed), `poll()` (next batch), `isActive()`, `name()`,
  `close()`. This is the port that makes the message *source* pluggable independently of the
  `MarketDataGateway` business seam.
- **`SimulatorSource`** — wraps the existing `ItchSimulator` (deterministic per seed).
- **`ItchGateway`** now builds and consumes an `ItchSource` in `bootstrap()`; `tick()` pulls `source.poll()`.
  Pure refactor — in default (simulator) mode the behaviour is byte-identical to before.

### Phase B — Record & replay (transport-level, raw-frame)
- **`ItchSessionWriter`** — length-framed `.itch` capture: 8-byte magic `DSEITCH1` then
  `[4-byte BE length][frame]` records. `record(Itch.Msg)` writes the exact wire frame (`ItchCodec.encode`);
  `recordFrame(byte[])` records **raw socket payloads verbatim** — the hook a future live SoupBinTCP /
  MoldUDP64 reader uses to capture real sessions byte-for-byte. Flushes per frame.
- **`FileReplaySource`** — reads frames, `ItchCodec.decode`s each (the real decode path), emits messages;
  verifies the magic header; deterministic, byte-for-byte reproducible.
- **`RecordingSource`** — decorator that tees any `ItchSource` into an `ItchSessionWriter` and passes
  through unchanged; recording IO errors are logged, never fatal to the feed.

### Order-book invariant validator (`ItchBookInvariants`)
- **`checkStructural`** — invariants that must always hold for a correctly reconstructed book: every level
  has positive qty and ≥1 order; price levels strictly monotonic and distinct (bids high→low, asks low→high).
- **`check`** — structural **plus** a crossed-market flag (best bid > best ask), a data-quality signal.
- Opt-in runtime: `itch.validate=true` logs any violations each tick from the live feed.

## Config (all env-overridable; defaults preserve current behaviour)
`application.yml` → `itch.*`: `seed` (20260703), `burst` (30), `tick-ms` (1200),
`replay` / `replay-file`, `record` / `record-file` (`./itchlog/session.itch`), `validate` (false).

| Goal | Set |
|---|---|
| Capture the running feed | `ITCH_ENABLED=true ITCH_RECORD=true` → writes `./itchlog/session.itch` |
| Replay a capture (deterministic) | `ITCH_REPLAY=true ITCH_REPLAY_FILE=./itchlog/session.itch` |
| Warn on book anomalies | `ITCH_VALIDATE=true` |
| Reproducible simulation | same `ITCH_SEED` ⇒ identical stream |

## Architecture guarantee
Everything is additive behind the `ItchSource` seam and `MarketDataGateway`. No OMS service, controller,
UI, or FIX code changed. A real **SoupBinTCP / MoldUDP64** feed is a new `ItchSource` implementation only —
and record/replay + invariant validation apply to it unchanged.

## Tests (6 new → 41/41 total)
- `ItchBookInvariantsTest` (3): crossed-book detection · clean two-sided book passes · a 300-burst,
  two-instrument simulated session routed through the codec keeps all structural invariants at every step.
- `ItchRecordReplayTest` (3): record → replay reproduces the stream **byte-for-byte** · `RecordingSource`
  tees and passes through · a non-capture file is rejected.

## Deferred (post-DSE-cert, unless needed for a demo/test)
Scenario/phase engine (opening/closing auctions, circuit breaker), Bangladesh per-security behavioural
profiles, and chaos/error injection (message dup/reorder now; sequence-gap/packet-loss to land with the
real sequenced transport).

## To see it live
Restart the backend (`connect-dse-sim.bat`, i.e. `itch.enabled=true`). Optionally `set ITCH_RECORD=true`
first to capture `./itchlog/session.itch`, then restart with `ITCH_REPLAY=true` +
`ITCH_REPLAY_FILE=./itchlog/session.itch` to replay that exact session into the terminal.
