# CLAUDE.md — Naztech Exchange-Hosted OMS

Project instructions for Claude Code. Read this + `PROJECT_HANDOFF.md` + `README.md` first.

## What this is
An **Exchange-Hosted Order Management System (OMS)** for the Dhaka & Chittagong Stock Exchanges
(DSE/CSE, Bangladesh), built by **Naztech** for broker **Dragon Security**. Institutional stack:
exchange connectivity (FIX/ITCH), pre-trade risk (RMS), portfolio, admin/RBAC, and an AI layer.

## Stack & ports
- **Backend** — Java 21, Spring Boot 3.2.5, package `com.naztech.oms` → **:8090**
- **Frontend** — Next.js 14 + TypeScript + Tailwind (`frontend/`) → **:3060**
- **Market-data/AI** — Python FastAPI (`marketdata/`) → **:8091** (supplementary; OMS runs without it)
- **DB** — MySQL schema `dse_oms`, local dev creds `root` / `gulshan` (schema/seed in `db/*.sql`)

## How to run  (⚠️ read this — the agent CANNOT start the server itself)
The embedded Tomcat/JVM cannot bind a port from an agent tool-shell, so **the human double-clicks a
`.bat`** to run the app. The agent verifies the backend with **`mvn test`** (MockMvc `@SpringBootTest`
against the real local MySQL) — those DO run.
- `start-all.bat` — start all three services (simulator mode).
- `start-local-exchange.bat` — the **local exchange** (`exchange/fixsim/`): a QuickFIX/J acceptor on
  :15000 that plays the venue — acks, part-fills, then completes each order, and handles cancels.
  Start it **before** the backend and leave the window open; it *is* the exchange.
- `connect-local-exchange.bat` — backend in **FIX + ITCH** mode pointed at that local exchange.
  This is the reliable way to demo real FIX order routing: no trial, no paywall, works offline.
- `connect-dse-sim.bat` — same, but pointed at the hosted **FIXSIM** trial. ⚠️ FIXSIM silently drops
  every logon unless the account carries a paid subscription ($500/mo), so this currently does not
  connect. Kept for when a real DSE/FIXSIM endpoint is available.
- `restart-backend.bat` / `rebuild-frontend.bat` / `start-marketdata.bat` — restart one service.
- **Never run `next dev` while `next start` serves the same folder** — it corrupts `.next`. Rebuild via `rebuild-frontend.bat`.
- Frontend SSE connects **directly to :8090** (a Next proxy buffers event-streams).

## Secrets
Copy `backend/secrets.sample.properties` → `backend/secrets.properties` (gitignored) and fill the
**Gemini API key** (`app.ai.gemini.key`). Without it the OMS still runs (falls back to on-prem MiniLM
+ regex). Never commit `secrets.properties`, `fixlog/`, `fixstore/`, `itchlog/`, `.next/`, `target/`, `node_modules/`.

## Architecture (ports-and-adapters — extend at the seams, don't rewrite)
- **Order routing:** `MatchingGateway` interface, selected by `exchange.mode` (`simulator` | `dse-cert` | `dse-prod`): `SimulatedMatchingEngine` (in-JVM book) vs `FixTradingGateway` (real exchange over FIX).
- **FIX:** QuickFIX/J, **FIX 5.0 SP1 over FIXT.1.1** — session mgmt, NewOrderSingle/Cancel/Replace, ExecutionReport; see `exchange/fix/`.
- **Market data:** `MarketDataGateway` + `ItchSource` seam. Real binary **ITCH v2.2** codec + order-book reconstruction in `exchange/itch/` (toggle `itch.enabled`; transport `simulator|soupbintcp|moldudp64`; record/replay to `.itch`).
- **RMS:** pre-trade checks (firm/trader/client limits, buying-power, wash-trade guard, fat-finger, kill-switch).
- **Bonds:** price⇄yield / accrued / dirty (`service/BondMath.java`).
- **AI:** Gemini advisor + multilingual (EN/বাংলা) order bot; on-prem all-MiniLM semantic security search.

## Conventions
- **Bangladesh-accurate:** DSE/CSE, BO account IDs, share categories A/B/N/Z/G, boards (Main/SME/Block/Odd-lot/Spot), T+2 settlement, tick/lot, commission/tax.
- **Commit per feature** with a clear message. Add a backend + frontend + a test for each feature.
- Explore before large changes; preserve existing features (FIX, RMS, AI, bonds, admin, portfolio, heatmap, alerts).

## Where to look
- `PROJECT_HANDOFF.md`, `README.md` — full overview & run steps.
- `docs/exchange-connectivity/` — FIX/ITCH design notes.
- Backend tests under `backend/src/test/` — the reliable way to verify without launching the app.

## Roadmap / open items
FIX-session-down hardening (reject orders cleanly when the session is down); a **CCD / credit-control
module** (roles, GUI-vs-transaction permissions, parent→child→sub-child limit inheritance, on-behalf
delegation, per-BO/per-ticker restrictions); advanced order types (Market-at-best, FOK/FAK, disclosed
"drip" qty, block trades); and the DSE-observed feature backlog (separate prompts).

<!-- OPENWIKI:START -->

## OpenWiki

This repository uses OpenWiki for recurring code documentation. Start with `openwiki/quickstart.md`, then follow its links to architecture, workflows, domain concepts, operations, integrations, testing guidance, and source maps.

The scheduled OpenWiki GitHub Actions workflow refreshes the repository wiki. Do not hand-edit generated OpenWiki pages unless explicitly asked; prefer updating source code/docs and letting OpenWiki regenerate.

<!-- OPENWIKI:END -->
