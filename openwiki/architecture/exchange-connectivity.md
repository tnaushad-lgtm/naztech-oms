---
type: Architecture
title: Exchange connectivity
description: Architecture page for the OMS exchange seam. Explains how FIX order routing, ITCH market data, replay recovery, and live transport health fit together, and why the backend treats the venue as the source of truth in live modes.
tags: [architecture, oms, fix, itch, market-data]
---

# Exchange connectivity

The OMS is built around a stable connectivity seam: `FixTradingGateway` submits orders to the venue, while `ItchGateway` rebuilds books and market data from the venue feed. The rest of the application — order entry, risk, portfolio, and UI — depends on those gateways rather than on a particular simulator or exchange implementation.

## Core architecture

- `backend/src/main/java/com/naztech/oms/exchange/fix/FixTradingGateway.java` routes orders to the exchange over FIX only after the enclosing transaction commits, so rapid execution reports cannot outrun the persistence layer.
- `backend/src/main/java/com/naztech/oms/exchange/itch/ItchGateway.java` is the market-data gateway. It seeds the simulator, or in live modes consumes SoupBinTCP/MoldUDP64, rebuilds per-security books, and resets daily aggregates before a full replay.
- `backend/src/main/java/com/naztech/oms/exchange/itch/SoupBinTcpSource.java` handles live ITCH session recovery. It logs in at sequence 1 on first connect, then resumes from the exact next sequence after a drop, and now keeps retrying indefinitely instead of giving up after a short outage.
- `backend/src/main/java/com/naztech/oms/repo/MarketDataRepo.java` provides the bulk reset used when ITCH is about to replay an entire day, preventing volume and turnover from stacking across restarts.

## Source of truth rules

The backend treats the live venue as authoritative in `dse-cert` and `dse-prod` modes:

- `MarketDataController` rejects external price ingest in live venue modes, so the Python scraper cannot overwrite venue prices.
- `ItchGateway` does not invent a second directory on top of a live feed; it maps feed instruments onto existing securities and lets the venue define the book.
- `ConnectivityController` reports ITCH health as a live-feed property, not a message-count property. A quiet market can still be healthy.

This is the key relationship: venue data -> defines -> market state, while external ingest -> must not overwrite -> live prices.

## Recovery and restart behavior

The transport code is designed so a long outage does not require a JVM restart:

- `SoupBinTcpSource` reconnects forever, backing off to a ceiling and resuming from the next expected sequence.
- When the session rolls back after a venue restart, the source marks the session reset so the gateway can clear stale books and rebuild from the replay.
- `ItchGateway` resets cumulative counters before a full replay so the first replayed trade seeds the day cleanly.

## FIX and ITCH relationship

FIX and ITCH are separate links to the venue, but they describe the same market in different ways:

- FIX carries order entry and order-cancel requests.
- ITCH carries market data, depth, and session liveness.
- `ConnectivityController` exposes both so operators can see whether an order-routing problem is actually a FIX issue or an ITCH issue.

## Related pages

- [Connectivity operations](../operations/exchange-connectivity.md)
- [DSE simulator integration guide](../source/DSE-SIMULATOR-INTEGRATION-GUIDE.md)
