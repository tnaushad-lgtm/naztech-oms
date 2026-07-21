---
type: Quickstart
title: OpenWiki quickstart
description: Entry point for the repository wiki. Summarizes the OMS, the FIX/ITCH connectivity seam, market-data replay behavior, admin connectivity controls, and where to look next when updating the documentation.
tags: [openwiki, quickstart, oms, fix, itch]
---

# OpenWiki quickstart

This wiki documents the exchange-hosted OMS built around a single connectivity seam: in simulator mode the OMS uses an in-process matching engine, and in live-venue modes the same business layer is fed by FIX for orders and ITCH for market data.

Start here, then follow the focused pages below:

- [Exchange connectivity](architecture/exchange-connectivity.md) — the FIX/ITCH seam, live transport modes, replay recovery, and why venue prices remain authoritative.
- [Connectivity operations](operations/exchange-connectivity.md) — the admin status surface, reconnect behavior, and log inspection.
- [README](source/README.md) — the repository overview and run instructions that anchor the product story.
- [DSE simulator integration guide](source/DSE-SIMULATOR-INTEGRATION-GUIDE.md) — the wire-level ITCH + FIX reference used for external venue integration.
- [nFIX logon issue note](source/NFIX_FIX_LOGON_ISSUE_FOR_JEWEL.md) — source evidence that the ITCH side is healthy while the FIX acceptor was still failing logon.

## What the wiki covers

- The OMS is an exchange-hosted, DSE/CSE-oriented trading system with an admin control plane and a pluggable matching gateway.
- Market data now comes through the `ItchGateway` transport seam, which can consume a simulator, SoupBinTCP, or MoldUDP64 feed and reset day stats before a full replay.
- Connectivity health is exposed through the admin API, including FIX session state and ITCH feed liveness.
- Recent fixes made the ITCH side more resilient across long outages and venue restarts, and prevented external ingest from overwriting live venue prices.

## Where to go next

- Use [Exchange connectivity](architecture/exchange-connectivity.md) for the source-backed explanation of the FIX/ITCH architecture.
- Use [Connectivity operations](operations/exchange-connectivity.md) when changing the admin status view, reconnect flow, or log download path.
- Use the source pages when you need protocol-level evidence or want to verify a claim against the integration guide.

## Backlog
- DSE certification hardening: durable QuickFIX/J sequence storage and live-venue transport validation; deferred because the repository still uses simulator and trial-style wiring in several paths.
