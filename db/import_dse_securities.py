"""
Import the FULL Dhaka Stock Exchange ticker universe (with live prices) into the
local dse_oms database, straight from dsebd.org's "Latest Share Price" page.

- Existing curated securities keep their nicer names/sectors (INSERT IGNORE).
- market_data is refreshed for every ticker from the live feed.
- Safe to re-run any time (idempotent). During Dhaka market hours it pulls live LTP.

Usage:  python import_dse_securities.py
"""
import ssl, sys, urllib.request
from io import StringIO
import pandas as pd
import mysql.connector

DSE_URL = "https://www.dsebd.org/latest_share_price_scroll_l.php"
DB = dict(host="localhost", user="root", password="gulshan", database="dse_oms")


def fetch_table():
    req = urllib.request.Request(DSE_URL, headers={"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"})
    try:
        html = urllib.request.urlopen(req, timeout=30).read().decode("utf-8", "ignore")
    except Exception:
        # dsebd.org often ships an incomplete cert chain — fall back to unverified for this read-only fetch
        ctx = ssl.create_default_context(); ctx.check_hostname = False; ctx.verify_mode = ssl.CERT_NONE
        html = urllib.request.urlopen(req, timeout=30, context=ctx).read().decode("utf-8", "ignore")
    tables = pd.read_html(StringIO(html))
    df = max(tables, key=lambda x: x.shape[0])
    df.columns = [str(c).strip().upper().replace("*", "").replace(" (MN)", "_MN") for c in df.columns]
    return df


def asset_class(code: str) -> str:
    c = code.upper()
    if c.endswith("MF"):
        return "MUTUAL_FUND"
    if "SUKUK" in c:
        return "SUKUK"
    if "BOND" in c:
        return "CORP_BOND"
    if c.startswith("TB") and any(ch.isdigit() for ch in c):
        return "GOVT_BOND"
    if c.startswith("ETF") or c.endswith("ETF"):
        return "ETF"
    return "EQUITY_MAIN"


def num(v):
    try:
        x = float(str(v).replace(",", ""))
        return 0.0 if x != x else x  # NaN guard
    except Exception:
        return 0.0


def main():
    print("Fetching DSE latest share prices …")
    df = fetch_table()
    print(f"  parsed {len(df)} securities; columns: {list(df.columns)}")

    conn = mysql.connector.connect(**DB)
    cur = conn.cursor()
    cur.execute("SELECT id FROM exchange WHERE code='DSE'")
    dse = cur.fetchone()[0]

    inserted = updated_md = 0
    for _, r in df.iterrows():
        code = str(r.get("TRADING CODE", "")).strip()
        if not code or code.lower() == "nan":
            continue
        ltp, high, low = num(r.get("LTP")), num(r.get("HIGH")), num(r.get("LOW"))
        ycp, change = num(r.get("YCP")), num(r.get("CHANGE"))
        trade, value_mn, volume = int(num(r.get("TRADE"))), num(r.get("VALUE_MN")), int(num(r.get("VOLUME")))
        ac = asset_class(code)

        # add the security if new (keeps curated rows untouched)
        cur.execute(
            "INSERT IGNORE INTO security (exchange_id, symbol, name, asset_class, board, sector,"
            " face_value, lot_size, tick_size, market_lot, status, category, is_shariah)"
            " VALUES (%s,%s,%s,%s,'MAIN',%s,10,1,0.10,1,'ACTIVE','A',0)",
            (dse, code, code, ac, "Mutual Fund" if ac == "MUTUAL_FUND" else None),
        )
        if cur.rowcount == 1:
            inserted += 1

        cur.execute("SELECT id FROM security WHERE exchange_id=%s AND symbol=%s", (dse, code))
        sid = cur.fetchone()[0]

        change_pct = round(change / ycp * 100, 2) if ycp else 0.0
        cur.execute(
            "INSERT INTO market_data (security_id, ltp, open_price, high_price, low_price, close_price,"
            " ycp, bid, ask, volume, trades, value_mn, change_pct, source, updated_at)"
            " VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,'DSE',NOW())"
            " ON DUPLICATE KEY UPDATE ltp=VALUES(ltp), high_price=VALUES(high_price), low_price=VALUES(low_price),"
            " ycp=VALUES(ycp), close_price=VALUES(close_price), bid=VALUES(bid), ask=VALUES(ask),"
            " volume=VALUES(volume), trades=VALUES(trades), value_mn=VALUES(value_mn),"
            " change_pct=VALUES(change_pct), source='DSE', updated_at=NOW()",
            (sid, ltp, ycp or ltp, high, low, ycp, ycp, round(ltp - 0.10, 2), round(ltp + 0.10, 2),
             volume, trade, value_mn, change_pct),
        )
        updated_md += 1

    conn.commit()
    cur.execute("SELECT COUNT(*) FROM security WHERE exchange_id=%s", (dse,))
    total = cur.fetchone()[0]
    cur.close(); conn.close()
    print(f"Done. New securities added: {inserted}. Market-data rows refreshed: {updated_md}.")
    print(f"Total DSE securities now in DB: {total}.")


if __name__ == "__main__":
    sys.exit(main())
