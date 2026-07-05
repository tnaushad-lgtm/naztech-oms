# Phases 6 & 7 — Admin console + Performance hardening (DONE)

Status: ✅ compiles, **35/35 backend tests pass**, frontend type-checks (Java 21). Additive; demo unchanged by default.

## Phase 6 — Exchange Connectivity admin page
- **Backend** `ConnectivityController` (`/api/admin/connectivity/*`):
  - `GET /status` — mode, FIX session (loggedOn, sessionId, host/port, sender→target, protocol, last event/heartbeat, next seq out/in, last msg in/out), ITCH (enabled, transport, depth source). Works in any mode.
  - `POST /reconnect` — requests a fresh FIX logon.
  - `POST /test-order` — places a tiny canned order (routes over FIX in dse-cert/prod, else simulator).
  - `GET /logs` — raw FIX message + event logs (for the Download button).
- **Frontend** `app/admin/connectivity/page.tsx` + nav entry **"Exchange Link"**: live green/red status banner, FIX session card, ITCH card, and **Reconnect / Test Order / Download Logs** buttons. Polls status every 2s.

## Phase 7 — 10k msg/sec hardening
- **`StreamService` coalescing** (`app.stream.coalesce`, default **off** → demo identical): with a real ITCH feed exceeding 10k msg/sec, buffering + a 60ms flush keeps the browser stable — `market`/`indices` collapse to **last-value-wins**, `trade` is **rate-capped** (25/flush, bounded 500 backlog), and `order` updates always go out **immediately**.
- Test `StreamServiceCoalesceTest`: 10k events → 1 market + 1 indices + 25 trades per flush; orders never buffered; off-by-default keeps prior behaviour.

## To see it
Rebuild the frontend (new page/nav) and restart the backend (new endpoints), then open **Admin → Exchange Link**.
For the 10k-safe mode set `app.stream.coalesce=true`.

## Remaining for real-DSE certification (needs DSE access)
- DSE-specific FIX enrichment: `<Parties>` PartyRole=5, `OrderRestrictions(529)`, bond `Yield(236)`/accrued interest.
- Durable QuickFIX/J `JdbcStore` for sequence numbers (in `dse_oms`).
- ITCH real transports: SoupBinTCP (point-to-point) + MoldUDP64 (multicast) + binary replay.
- Server-side authz filter for the connectivity endpoints; tighten CORS.
