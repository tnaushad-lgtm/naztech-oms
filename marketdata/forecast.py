"""
Lightweight price analytics / forecast for the OMS.

Reads price history (or, when none exists yet, synthesises a deterministic series
from the current LTP) and fits a linear trend with numpy to project the next N
sessions. Returns the historical series, the forecast, a trend label and an R²
confidence — enough to drive a "AI price outlook" panel in the terminal.

This is intentionally simple & explainable; it can be upgraded to ARIMA/Prophet/an
ML model behind the same endpoint without touching the API.
"""
from __future__ import annotations

import datetime as dt
import hashlib
from typing import Dict, Any, List, Tuple

import numpy as np
from sqlalchemy import create_engine, text

DB_URL = "mysql+pymysql://root:gulshan@localhost:3306/dse_oms"
_engine = create_engine(DB_URL, pool_pre_ping=True, future=True)


def _security_id_and_ltp(symbol: str, exchange: str) -> Tuple[int | None, float]:
    sql = text("""
        SELECT s.id, COALESCE(m.ltp, m.close_price, s.face_value) AS ltp
        FROM security s
        JOIN exchange e ON e.id = s.exchange_id
        LEFT JOIN market_data m ON m.security_id = s.id
        WHERE s.symbol = :sym AND e.code = :ex
        LIMIT 1
    """)
    with _engine.connect() as c:
        row = c.execute(sql, {"sym": symbol.upper(), "ex": exchange.upper()}).fetchone()
    if not row:
        return None, 0.0
    return int(row[0]), float(row[1] or 0.0)


def _load_history(security_id: int) -> List[Tuple[str, float]]:
    sql = text("""
        SELECT trade_date, close_price FROM price_history
        WHERE security_id = :sid ORDER BY trade_date ASC
    """)
    with _engine.connect() as c:
        rows = c.execute(sql, {"sid": security_id}).fetchall()
    return [(str(r[0]), float(r[1])) for r in rows]


def _synth_history(symbol: str, ltp: float, days: int = 60) -> List[Tuple[str, float]]:
    """Deterministic synthetic history seeded by the symbol, ending near the LTP."""
    if ltp <= 0:
        ltp = 100.0
    seed = int(hashlib.md5(symbol.encode()).hexdigest(), 16) % (2 ** 32)
    rng = np.random.default_rng(seed)
    steps = rng.normal(0, 0.012, days)            # ~1.2% daily vol
    drift = rng.uniform(-0.0008, 0.0010)          # mild trend
    series = [ltp]
    for i in range(days - 1):
        series.append(max(0.5, series[-1] * (1 + drift + steps[i])))
    # rescale so the last point equals the real LTP
    factor = ltp / series[-1]
    series = [round(p * factor, 2) for p in series]
    today = dt.date.today()
    out = []
    for i, p in enumerate(series):
        d = today - dt.timedelta(days=(days - 1 - i))
        out.append((d.isoformat(), p))
    return out


def forecast(symbol: str, exchange: str = "DSE", horizon: int = 5) -> Dict[str, Any]:
    sid, ltp = _security_id_and_ltp(symbol, exchange)
    if sid is None:
        return {"error": f"unknown security {symbol} on {exchange}"}

    hist = _load_history(sid)
    synthetic = False
    if len(hist) < 10:
        hist = _synth_history(symbol, ltp)
        synthetic = True

    closes = np.array([p for _, p in hist], dtype=float)
    x = np.arange(len(closes), dtype=float)

    # linear trend fit
    slope, intercept = np.polyfit(x, closes, 1)
    fit = slope * x + intercept
    ss_res = float(np.sum((closes - fit) ** 2))
    ss_tot = float(np.sum((closes - closes.mean()) ** 2)) or 1.0
    r2 = max(0.0, 1.0 - ss_res / ss_tot)

    last_date = dt.date.fromisoformat(hist[-1][0])
    fc: List[Dict[str, Any]] = []
    for h in range(1, horizon + 1):
        xi = len(closes) - 1 + h
        yi = float(slope * xi + intercept)
        d = last_date + dt.timedelta(days=h)
        fc.append({"date": d.isoformat(), "close": round(max(0.0, yi), 2)})

    daily_ret = float(slope / closes[-1]) if closes[-1] else 0.0
    trend = "UP" if daily_ret > 0.0008 else "DOWN" if daily_ret < -0.0008 else "FLAT"

    return {
        "symbol": symbol.upper(),
        "exchange": exchange.upper(),
        "ltp": round(float(closes[-1]), 2),
        "horizon": horizon,
        "trend": trend,
        "expectedDailyReturnPct": round(daily_ret * 100, 3),
        "confidenceR2": round(r2, 3),
        "syntheticHistory": synthetic,
        "history": [{"date": d, "close": p} for d, p in hist[-60:]],
        "forecast": fc,
    }
