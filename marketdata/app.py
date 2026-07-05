"""
NAZTECH OMS — Market-Data & AI micro-service (Python / FastAPI).

Responsibilities:
  • pull real DSE/CSE quotes (bdshare) and push them to the Java OMS ingest API
  • expose an explainable price-forecast endpoint for the terminal's "AI outlook"
  • stay decoupled: the Java backend remains the single DB writer for market data

Run:  uvicorn app:app --port 8091 --reload
"""
from __future__ import annotations

import logging
import os

import httpx
from apscheduler.schedulers.background import BackgroundScheduler
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

import bd_feed
import forecast as fc

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
log = logging.getLogger("marketdata")

BACKEND_URL = os.getenv("OMS_BACKEND_URL", "http://localhost:8090")
FEED_ENABLED = os.getenv("OMS_FEED_ENABLED", "true").lower() == "true"
FEED_INTERVAL_SEC = int(os.getenv("OMS_FEED_INTERVAL", "30"))

app = FastAPI(title="Naztech OMS Market-Data & AI", version="1.0.0")
app.add_middleware(
    CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"]
)

_last_feed = {"ran": None, "ingested": 0, "ok": False, "error": None}


def push_quotes(quotes) -> int:
    if not quotes:
        return 0
    try:
        r = httpx.post(f"{BACKEND_URL}/api/market/ingest",
                       json={"quotes": quotes}, timeout=15.0)
        r.raise_for_status()
        n = r.json().get("ingested", 0)
        return int(n)
    except Exception as e:
        log.warning("ingest POST failed: %s", e)
        _last_feed["error"] = str(e)
        return 0


def run_feed_once() -> int:
    quotes = bd_feed.fetch_all()
    n = push_quotes(quotes)
    _last_feed.update(ran=__import__("datetime").datetime.now().isoformat(),
                      ingested=n, ok=(n > 0), error=_last_feed.get("error"))
    log.info("feed cycle: %d quotes ingested", n)
    return n


# ----------------------------------------------------------------- endpoints
@app.get("/health")
def health():
    return {"status": "up", "backend": BACKEND_URL, "feedEnabled": FEED_ENABLED}


@app.get("/feed/status")
def feed_status():
    return _last_feed


@app.post("/feed/pull-now")
def pull_now():
    n = run_feed_once()
    return {"ingested": n}


@app.get("/ai/forecast/{symbol}")
def ai_forecast(symbol: str, exchange: str = "DSE", horizon: int = 5):
    return fc.forecast(symbol, exchange, horizon)


# ----------------------------------------------------------------- scheduler
_scheduler: BackgroundScheduler | None = None


@app.on_event("startup")
def _startup():
    global _scheduler
    if FEED_ENABLED:
        _scheduler = BackgroundScheduler(daemon=True)
        _scheduler.add_job(run_feed_once, "interval", seconds=FEED_INTERVAL_SEC,
                           next_run_time=__import__("datetime").datetime.now())
        _scheduler.start()
        log.info("feed scheduler started (every %ds -> %s)", FEED_INTERVAL_SEC, BACKEND_URL)


@app.on_event("shutdown")
def _shutdown():
    if _scheduler:
        _scheduler.shutdown(wait=False)
