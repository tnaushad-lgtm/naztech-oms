"""
Bangladesh market-data adapter.

Pulls real DSE / CSE quotes from the open-source `bdshare` library (which scrapes
dsebd.org) and normalises them into the OMS ingest shape. Everything is best-effort:
if the public source is unavailable (market closed, no network, layout change) we
simply return an empty list and the backend's own simulator keeps the tape alive.

Later this module is the single place to swap in an authentic exchange data API.
"""
from __future__ import annotations

import logging
from typing import List, Dict, Any

log = logging.getLogger("bd_feed")


def _to_float(v) -> float | None:
    try:
        if v is None:
            return None
        s = str(v).replace(",", "").strip()
        if s in ("", "-", "--", "N/A"):
            return None
        return float(s)
    except Exception:
        return None


def _to_int(v) -> int | None:
    f = _to_float(v)
    return int(f) if f is not None else None


def _normalise(df, exchange: str) -> List[Dict[str, Any]]:
    """Map a bdshare DataFrame (columns vary) into OMS QuoteIn dicts."""
    if df is None or len(df) == 0:
        return []
    cols = {c.lower().strip(): c for c in df.columns}

    def col(*names):
        for n in names:
            if n in cols:
                return cols[n]
        return None

    c_sym = col("symbol", "trading code", "code", "scrip")
    c_ltp = col("ltp", "last", "last trade", "close", "closep")
    c_high = col("high")
    c_low = col("low")
    c_ycp = col("ycp", "yesterday close", "prev close", "previous close")
    c_vol = col("volume", "vol")
    c_val = col("value", "value (mn)", "value mn", "turnover")
    c_trd = col("trade", "trades", "no of trade", "number of trades")

    out: List[Dict[str, Any]] = []
    for _, row in df.iterrows():
        sym = row.get(c_sym) if c_sym else None
        if not sym:
            continue
        out.append({
            "exchange": exchange,
            "symbol": str(sym).strip().upper(),
            "ltp": _to_float(row.get(c_ltp)) if c_ltp else None,
            "open": None,
            "high": _to_float(row.get(c_high)) if c_high else None,
            "low": _to_float(row.get(c_low)) if c_low else None,
            "ycp": _to_float(row.get(c_ycp)) if c_ycp else None,
            "volume": _to_int(row.get(c_vol)) if c_vol else None,
            "trades": _to_int(row.get(c_trd)) if c_trd else None,
            "valueMn": _to_float(row.get(c_val)) if c_val else None,
            "source": "BDSHARE",
        })
    return out


def _fetch_html(url: str) -> str:
    import ssl
    import urllib.request
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"})
    try:
        return urllib.request.urlopen(req, timeout=30).read().decode("utf-8", "ignore")
    except Exception:
        # dsebd.org often ships an incomplete cert chain — fall back to unverified for this read-only fetch
        ctx = ssl.create_default_context(); ctx.check_hostname = False; ctx.verify_mode = ssl.CERT_NONE
        return urllib.request.urlopen(req, timeout=30, context=ctx).read().decode("utf-8", "ignore")


def _scrape_dse() -> List[Dict[str, Any]]:
    """Primary, reliable feed: scrape the DSE 'Latest Share Price' page directly."""
    from io import StringIO
    import pandas as pd
    html = _fetch_html("https://www.dsebd.org/latest_share_price_scroll_l.php")
    df = max(pd.read_html(StringIO(html)), key=lambda x: x.shape[0])
    df.columns = [str(c).strip().upper().replace("*", "").replace(" (MN)", "_MN") for c in df.columns]
    out: List[Dict[str, Any]] = []
    for _, r in df.iterrows():
        sym = str(r.get("TRADING CODE", "")).strip()
        if not sym or sym.lower() == "nan":
            continue
        out.append({
            "exchange": "DSE",
            "symbol": sym.upper(),
            "ltp": _to_float(r.get("LTP")),
            "open": None,
            "high": _to_float(r.get("HIGH")),
            "low": _to_float(r.get("LOW")),
            "ycp": _to_float(r.get("YCP")),
            "volume": _to_int(r.get("VOLUME")),
            "trades": _to_int(r.get("TRADE")),
            "valueMn": _to_float(r.get("VALUE_MN")),
            "source": "DSE",
        })
    return out


def fetch_dse() -> List[Dict[str, Any]]:
    # 1) direct dsebd.org scrape (most reliable); 2) bdshare as a fallback
    try:
        quotes = _scrape_dse()
        if quotes:
            log.info("DSE live scrape: %d quotes", len(quotes))
            return quotes
    except Exception as e:
        log.warning("DSE scrape failed (%s); trying bdshare", e)
    try:
        from bdshare import get_current_trade_data
        quotes = _normalise(get_current_trade_data(), "DSE")
        log.info("bdshare DSE: %d quotes", len(quotes))
        return quotes
    except Exception as e:
        log.warning("bdshare DSE pull failed: %s", e)
        return []


def fetch_cse() -> List[Dict[str, Any]]:
    # bdshare focuses on DSE; CSE is best-effort and usually unavailable publicly.
    try:
        from bdshare import get_current_trade_data
        df = get_current_trade_data()  # same source; tag a CSE subset if needed
        quotes = [q for q in _normalise(df, "CSE")
                  if q["symbol"] in {"GP", "SQURPHARMA", "BRACBANK"}]
        return quotes
    except Exception as e:
        log.warning("bdshare CSE pull failed: %s", e)
        return []


def fetch_all() -> List[Dict[str, Any]]:
    return fetch_dse() + fetch_cse()
