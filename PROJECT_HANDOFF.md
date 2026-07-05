# Naztech OMS — Project Handoff / Continuation Guide

> Give this file (with the repo) to anyone continuing the project — including a fresh Claude Code
> session on another machine/account. It captures everything needed to run and extend the system.
> When you open this folder in Claude Code, ask it to *"read PROJECT_HANDOFF.md and continue"*.

## 1. What this is
An **Exchange-Hosted Order Management System (OMS)** for the Dhaka & Chittagong Stock Exchanges
(DSE / CSE), built for the DSE tender (License & AMC model). It covers the full order lifecycle,
pre-trade risk (RMS), a pluggable matching gateway (simulated now, FIX later), on-prem AI, and a
premium themeable UI. **Status:** working demonstration build; tender submitted & in evaluation.

## 2. Architecture & ports
| Part | Tech | Port | Folder |
|------|------|------|--------|
| Backend | Java 21 / Spring Boot 3.2.5 / JPA / LangChain4j MiniLM / QuickFIX/J | 8090 | `backend/` (`com.naztech.oms`) |
| Market-data + AI feed | Python 3.12 / FastAPI / APScheduler | 8091 | `marketdata/` |
| Frontend | Next.js 14 / TypeScript / Tailwind / lightweight-charts / react-grid-layout / flexlayout-react | 3060 | `frontend/` |
| Database | MySQL 8, schema `dse_oms` | 3306 | `db/` |

Data flow: Python scrapes **dsebd.org** every 30s → POSTs to backend `/api/market/ingest` → backend
stores + fans out to the browser via **SSE**. Stock last-prices are real (scraped); the order book,
trades and index intraday drift are **simulated** by the in-app matching engine (real depth/tick data
isn't publicly available). `app.matching.price-sim=false` keeps scraped prices from being overwritten.

## 3. Prerequisites
- JDK 17, Maven; Node 18+ & npm; Python 3.12; MySQL 8 (local, user `root` / password `gulshan` — change as needed).
- Internet access (the feed scrapes dsebd.org; first backend run downloads the MiniLM model once).

## 4. First-time setup (from a clean clone)
```bash
# 4.1 Database — create schema, seed, then import the real DSE universe
mysql -u root -p < db/schema.sql
mysql -u root -p < db/seed.sql
python db/import_dse_securities.py      # ~405 real DSE tickers + prices (scrapes dsebd.org)
python db/enrich_dse_securities.py      # real company names + sectors
python db/seed_demo_activity.py         # realistic demo orders/trades/news (run with app stopped)

# 4.2 Secrets (NOT in git) — create backend/secrets.properties with a Gemini key:
#     app.ai.gemini.key=YOUR_GEMINI_API_KEY
#     (Ask the previous owner for the key, or use your own from aistudio.google.com.
#      Without a key, the AI Advisor falls back to a rule-based offline mode.)

# 4.3 Install frontend deps
cd frontend && npm install && cd ..
```

## 5. Running it
- **Easiest:** double-click **`start-all.bat`** (Windows) — opens 3 windows: backend, market-data, frontend
  (frontend builds then serves in production). Then open http://localhost:3060 (LAN: http://<host-ip>:3060).
- **Restart just the backend** after Java changes: **`restart-backend.bat`**.
- **Manual:** `cd backend && mvn spring-boot:run` · `cd marketdata && uvicorn app:app --host 0.0.0.0 --port 8091`
  · `cd frontend && npm run dev -- -H 0.0.0.0` (dev) or `npm run build && npm run start` (prod).
- **Demo logins** (all password `demo123`): `investor1`..`investor5` (clients), `dealer1` (broker staff),
  `rms`, `exchadmin`, `brokeradmin`, `trader1`, `viewer`.

## 6. What's built
- Trader Terminal (market watch, watchlists, candlestick+volume chart, **market depth/order book**,
  order ticket with pre-trade AI risk, blotter with amend/cancel, portfolio, news, AI semantic search).
- **Trading Desk** — dockable workspace (flexlayout-react): drag/split/resize/maximise/close panels.
- Customisable **Dashboard** (react-grid-layout widgets + named presets).
- **Market Screener**, **Portfolio** (equity curve, allocation, holdings, realised/unrealised P&L),
  **Reports** (trade/order book, position statement, EOD; CSV + print-to-PDF).
- **RMS**: editable pre-trade limits, per-broker kill-switch, live AI risk-alert feed.
- **Exchange Admin**: broker onboarding, system totals, user hierarchy, audit trail.
- **AI Investment Advisor**: Gemini 2.5 Flash grounded in live OMS data; EN/বাংলা chat + voice (TTS),
  screenshot upload (vision), DSE Status button.
- 5 selectable themes; multi-user LAN access; single-session role-based auth.
- **User manual**: `docs/manual/Naztech_OMS_User_Manual.docx` (regen: `node docs/manual/capture.js`
  then `python docs/manual/build_manual.py`).

## 7. Key files
- Backend services: `backend/src/main/java/com/naztech/oms/service/` — `OrderService`, `RiskService`,
  `SimulatedMatchingEngine` (behind `MatchingGateway`), `PortfolioService`, `AiAdvisorService`.
- Config: `backend/src/main/resources/application.properties` (+ gitignored `secrets.properties`).
- Frontend: `frontend/app/*` (pages), `frontend/components/*`, `frontend/lib/*` (`api.ts` host-aware base,
  `terminalStore.tsx` shared state, `session.ts`).
- Feed: `marketdata/bd_feed.py` (dsebd.org scrape), `marketdata/app.py`.

## 8. Known constraints / gotchas
- **Real vs simulated:** prices real (scraped); depth/trades/index-drift simulated. Swap `MatchingGateway`
  for a real FIX gateway and the feed for a licensed exchange feed in production — screens don't change.
- **gemini-2.5-flash is a "thinking" model** — set `generationConfig.thinkingConfig.thinkingBudget=0`
  (already done) or reasoning tokens truncate answers.
- Never run `next build` while `next dev` runs on the same folder (corrupts `.next`).
- Re-running `db/seed.sql` truncates `security` — re-run the import+enrich scripts afterwards.
- LAN access: services bind `0.0.0.0`; open Windows Firewall inbound for 3060/8090/8091; frontend derives
  the API/feed host from the browser URL, so use the host IP (not `localhost`) from other machines.

## 9. Suggested next steps (roadmap)
Authentic licensed exchange data feed; real FIX Matching-Engine gateway; HA/DR & DC/DR; production auth
hardening/MFA; performance/stress proof; mobile apps. (Out of scope for this demo slice.)
