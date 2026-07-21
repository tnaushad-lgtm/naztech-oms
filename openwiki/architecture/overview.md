---
type: Architecture
title: Architecture overview
description: "High-level map of the OMS runtime: UI, Spring Boot backend, market-data/AI service, and MySQL storage, with emphasis on the FIX/ITCH connectivity seam."
tags:
  - architecture
  - oms
  - fix
  - itch
  - system
---

# Architecture overview

This repository is an **exchange-hosted OMS** with a **single connectivity seam**:

- **Orders** flow into the backend through `FixTradingGateway` (FIX).
- **Market data / order-book state** flows into the backend through `ItchGateway` (ITCH).

Everything else (order lifecycle, risk checks, portfolio updates, and the UI) is built to depend on those gateways instead of depending on a specific simulator vs live-venue implementation.

## Runtime components

- **Frontend (Next.js)**: provides terminal-style screens for trading, depth, RMS/Admin views, and an AI advisor UI. The wiki’s connectivity pages describe what those screens should reflect.
- **Backend (Spring Boot / Java)**: the core OMS services and controllers. The FIX/ITCH seam lives in the `backend/src/main/java/com/naztech/oms/exchange/*` packages.
- **Market-data / AI service (Python FastAPI)**: fetches market prices (bdshare) and AI/forecasting utilities, then POSTs to the backend.
- **Storage (MySQL / `dse_oms`)**: persists securities, users, orders, risk limits, and derived execution/blotter data.

## Core domain boundaries

1. **Exchange connectivity (FIX/ITCH seam)**: described in [Exchange connectivity](exchange-connectivity.md).
2. **Operational control plane**: described in [Connectivity operations](../operations/exchange-connectivity.md).
3. **Protocol-level integration reference**: documented in [DSE simulator integration guide](../source/DSE-SIMULATOR-INTEGRATION-GUIDE.md).

## Why the seam matters

The seam allows the system to run in:

- **Simulator mode** (in-process matching engine and seeded books)
- **Live-venue modes** (orders via FIX; books via ITCH over SoupBinTCP / MoldUDP64)

But even in live modes, the backend follows **source-of-truth rules**: venue data defines the book and live prices, and external ingestion must not overwrite venue prices.

## Next steps

- Start with [Exchange connectivity](exchange-connectivity.md) to understand replay recovery, day resets, and why FIX and ITCH are treated as separate failure domains.
- Use [Connectivity operations](../operations/exchange-connectivity.md) when you need to interpret the admin status payload or decide how to troubleshoot an incident.
