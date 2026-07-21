---
type: Operations
title: Connectivity operations
description: Operational guide for the OMS connectivity control plane. Covers the admin status payload, reconnect behavior, raw FIX log access, and the practical meaning of ITCH liveness fields.
tags: [operations, oms, fix, itch, admin]
---

# Connectivity operations

This page is the operator-facing companion to the architecture page. It explains what the admin connectivity endpoint reports, how reconnect behaves, and what to inspect when FIX or ITCH looks unhealthy.

## Admin status surface

`backend/src/main/java/com/naztech/oms/controller/ConnectivityController.java` exposes `/api/admin/connectivity/status` with two major sections:

- `fix` — whether FIX is enabled, the session id, last events, heartbeat timing, sequence counters, and session identifiers.
- `itch` — whether ITCH is enabled, the active transport, and a nested `feed` object when the gateway is live.

The nested ITCH feed status includes:

- `expectedSeq`, `delivered`, `duplicates`, `gapsDetected`, `gapsRecovered`, and `lost`
- `idleMs`, `live`, `session`, and the last applied sequence

The important operational rule is that liveness is based on heartbeats and recent packets, not on market volume. A quiet market can still be a healthy venue connection.

## Reconnect behavior

`/api/admin/connectivity/reconnect` asks the FIX session to log on again if a session exists. This is a session control action, not a market-data fix; it helps when order routing is disconnected or the session needs to be re-established.

For ITCH, the reconnect story lives in `SoupBinTcpSource`:

- first connect requests a full replay from sequence 1
- reconnect resumes from the next missing sequence
- retries continue indefinitely, so a temporary venue outage can recover without human intervention

That means operations should not treat an ITCH outage as permanent until the source has been down long enough to justify a deeper investigation.

## Raw FIX logs

`/api/admin/connectivity/logs` returns the tail of the raw FIX messages and event logs from the local `fixlog/` directory. The controller renders FIX SOH delimiters as `|` so the log is easier to read in a browser.

Use this when you need to answer questions like:

- Did the logon reach the venue?
- Was a NewOrderSingle sent after the transaction committed?
- Did execution reports arrive before or after the order was visible locally?

## Practical troubleshooting guide

- If FIX is down but ITCH is healthy, order routing will stall while market data may still update.
- If ITCH is down, the book can freeze even though order entry still works over FIX.
- If the live venue restarts, watch for a session rollback and a book rebuild rather than assuming the feed is permanently broken.
- If external ingest is being refused, that is expected in live-venue modes because the venue is the source of price truth.

## Related pages

- [Exchange connectivity](../architecture/exchange-connectivity.md)
- [README](../source/README.md)
