# NAZTECH OMS — Exchange-Hosted Order Management System (DSE / CSE)

A demonstrable, multi-asset **Exchange-Hosted OMS** built for the Dhaka Stock Exchange RFP
*"Supply, Implementation & Support of an Exchange-Hosted OMS — License & AMC Model"*, designed
to also serve **CSE** (exchange is a first-class dimension throughout).

This is an **iteration-1 vertical slice / bid-demo foundation** — a credible, runnable system that
maps to the RFP's high-level requirements with a **pluggable matching gateway** so a real
FIX/FAST/ITCH engine drops in later. It is **not** a production exchange system (those require ME
certification, hardened HA/DR, and years of work — see *Roadmap / Out of scope*).

```
┌─────────────────┐     SSE + REST      ┌──────────────────────────┐     JDBC      ┌─────────┐
│  Next.js 14 UI  │ ◄─────────────────► │  Spring Boot OMS (Java)   │ ◄──────────► │  MySQL  │
│  :3060          │                     │  :8090                    │              │ dse_oms │
│ terminal/rms/   │                     │  • order lifecycle        │              └─────────┘
│ admin (glass UI)│                     │  • pre-trade RMS          │                   ▲
└─────────────────┘                     │  • matching-engine sim    │                   │ ingest
        ▲                               │  • on-prem AI (MiniLM)    │ ◄─── POST /ingest ┤
        │ AI forecast                   └──────────────────────────┘                   │
        └───────────────────────────────  Python FastAPI :8091  ───────────────────────┘
                                          bdshare feed + numpy forecast
```

## Tech stack
- **Backend** — Java 17, Spring Boot 3.2.5, Spring Data JPA, **LangChain4j all-MiniLM-L6-v2** (in-process, offline AI)
- **Market-data / AI** — Python 3.12, FastAPI, **bdshare** (real DSE/CSE quotes), numpy forecast, APScheduler
- **Frontend** — Next.js 14, TypeScript, Tailwind CSS, framer-motion, **lightweight-charts** (TradingView)
- **DB** — MySQL 8 (`dse_oms`)

---

## Run it

### 0. Prerequisites
MySQL running locally (`root` / `gulshan`), JDK 17, Maven, Node 18+, Python 3.10+.

### 1. Database
```bash
cd dse-oms/db
# loads schema + seed (35 real DSE/CSE securities, demo broker, users, risk limits, portfolio)
mysql -u root -pgulshan < schema.sql
mysql -u root -pgulshan < seed.sql
```
(Or run the bundled loader once: it executes both files.)

### 2. Backend  →  http://localhost:8090
```bash
cd dse-oms/backend
mvn spring-boot:run
```
First start downloads the MiniLM model artifact and warms the semantic index (a few seconds).

### 3. Market-data / AI service  →  http://localhost:8091
```bash
cd dse-oms/marketdata
pip install -r requirements.txt
uvicorn app:app --port 8091
```
Pulls real DSE/CSE prices via `bdshare` and POSTs them to the backend. If the public source is
unreachable, the backend's built-in simulator keeps the tape live.

### 4. Frontend  →  http://localhost:3060
```bash
cd dse-oms/frontend
npm install
npm run dev
```

### Demo logins (password `demo123`)
| User | Role | Notes |
|------|------|----------|
| `dealer1` | Dealer | Trades on behalf of clients |
| `rms` | RMS Manager | Limits + kill-switch |
| `exchadmin` | Exchange Admin | Control plane |
| `investor1` … `investor5` | Investor (CLIENT) | **5 investors with different portfolios** (profit/loss mix) — scoped to their own account; use these to test the AI Advisor |
| `brokeradmin` / `trader1` / `viewer` | Broker admin / Trader / View-only | — |

### AI Investment Advisor
The floating **AI Advisor** (every page) answers investor questions grounded in **live OMS data**
(the user's portfolio + current market): "is GP good today?", "which sector is doing well?", "am I in
profit?", "show me Z-category companies", "order types?". It supports **English/বাংলা voice** (Web
Speech) in & out, **screenshot upload** (analyse a DSE/CSE screen), and a **DSE Status** button.
- It uses **Gemini 2.5 Flash** when `GEMINI_API_KEY` is set in the backend environment (enables free
  conversation, Bangla, and screenshot vision). **Without a key it still works** via an OMS-grounded
  rule-based fallback. Responses are informational only — not licensed financial advice.
- Enable Gemini:  `setx GEMINI_API_KEY "your-key"`  (new terminal) then restart the backend.

---

## What it does (demo script)
1. **Sign in** as `dealer1`. The **Trader Terminal** shows a live market watch, ticker tape,
   TradingView chart with an **AI 5-day outlook**, market-depth ladder, order ticket and portfolio.
2. **AI search** — type *"squre pharma"* or *"telecom"*: the in-process MiniLM model returns
   ranked matches (typo-tolerant, semantic) with match %.
3. **Place an order** — pick a security, set side/qty/price. The ticket shows a **live AI pre-trade
   risk score** (fat-finger / oversize / wash-trade detection). Submit a MARKET order → it routes to
   the matching engine, **fills**, prints to the tape, and **updates your portfolio & blotter** live.
4. **Get rejected** — try an oversized order: the **RMS** blocks it with the exact reason.
5. **RMS view** (`rms`) — see the configured broker/trader/client limits and a **live AI risk alert
   feed** of rejected / elevated-risk orders.
6. **Admin view** (`exchadmin`) — exchange control plane: KPIs, **onboard a TREC holder**, user
   hierarchy, on-prem AI status, and the **audit trail**.

---

## RFP requirement → implementation map

| RFP requirement (High-Level Scope §E / §F) | Where it lives |
|---|---|
| Web-based, Exchange-hosted, central control | Next.js terminal + `AdminController` control plane |
| Multi-exchange (DSE **+ CSE**), smart routing hook | `exchange` table everywhere; `MatchingGateway` abstraction |
| Multi-asset (equity/SME/ATB/bond/sukuk/MF/ETF/index) | `security.asset_class`, seeded across all classes |
| Multiple trading windows (Normal/Spot/Block/Odd-lot/Foreign…) | `oms_order.trade_window`, order ticket selector |
| Full order lifecycle (entry/modify/cancel/close-out) | `OrderService`, `oms_order.status`, `order_event` audit |
| Order validity (DAY/GTD/GTC/GTS) | `oms_order.validity` |
| Pre-trade risk: firm/trader/client limits | `RiskService` + `risk_limit` (BROKER/TRADER/CLIENT) |
| Wash-sale control, stop-loss, mark-to-market | `RiskService` wash check, `SimulatedMatchingEngine` stop arming, MTM limit field |
| Routing to Matching Engine (FIX/FAST/ITCH) | `MatchingGateway` interface (`SimulatedMatchingEngine` now → FIX gateway later) |
| Market data: depth, gainers/losers, active, indices | `MarketDataController` + depth ladder + movers |
| GUI: dashboard, charts, ticker, market depth, positions | terminal page + `PriceChart` (lightweight-charts) |
| Real-time monitoring (status, flow, alerts) | SSE `StreamService`, RMS alert feed, admin KPIs |
| News / announcements | `news` table + `NewsController` |
| Reports / EOD / audit | blotter, `order_event`, `audit_log` (field-level audit trail) |
| Security: auth, single-session, hashing | `AuthService` (SHA-256, single `session_token`) |
| User hierarchy (broker→branch→dealer→client, RMS, admin) | `app_user.role` + `parent_id` |
| Broker onboarding/offboarding | Admin → onboard TREC holder |
| Scalability / modular architecture | stateless REST + service modules + pluggable gateway |
| **AI value-add (RFP §3.13 welcomes these)** | on-prem MiniLM semantic search + explainable risk scoring + price forecast |

### Why the AI is **on-prem / in-process**
For capital-market infrastructure, data locality is a compliance posture, not a feature. The
semantic search and risk scoring run **inside the JVM** (LangChain4j all-MiniLM-L6-v2) — **no order
or ticker data leaves the exchange**. A pluggable adapter can add cloud LLM (e.g. Gemini) for
natural-language features later without changing this default.

---

## Verified
- **Backend:** 5 `@SpringBootTest` integration tests pass against the real DB — login, market watch,
  **order → risk → matching → fill → portfolio**, risk rejection, typo-tolerant semantic search
  (`mvn test`).
- **Python:** forecast endpoint validated against the DB; bdshare feed degrades gracefully offline.
- **Frontend:** clean production build (`npm run build`) — all routes, types and bundles.

> Note: this sandbox blocks JVM loopback sockets, so the Spring server is verified via MockMvc tests
> rather than a live port here. On a normal machine all four parts run together as above.

## Roadmap / Out of scope for iteration 1
Real FIX/FAST/ITCH ME certification · true multicast market data · full HA/DR + DC↔DR sync ·
hardware sizing · production auth/MFA hardening · mobile apps (Android/iOS) · 4,000 orders/sec
stress proof. These are deliberately deferred and belong in the formal tender response.
