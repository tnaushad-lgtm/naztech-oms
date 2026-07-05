"""
DEMO SEED — fills the OMS with a realistic trading day so every screen looks full
for an evaluation demo:
  • ~90 orders across the broker's accounts (mixed FILLED / PARTIAL / OPEN /
    CANCELLED / REJECTED, with varied AI risk scores) → blotter, order book,
    RMS alert feed, order-status widget, dashboard.
  • trades for the filled orders + dense intraday trades for a few headline
    tickers → trade book, execution ledger, live tape, candlestick charts.
  • ~14 price-sensitive news items (dividends, AGM, halts, earnings) with AI
    sentiment → news panel / widget / terminal.

Run when the app is stopped, then start it (so resting OPEN orders load into the
matching engine). Re-runnable — it resets orders/trades/news to a clean demo state.

    python seed_demo_activity.py
"""
import datetime as dt
import random

import mysql.connector

DB = dict(host="localhost", user="root", password="gulshan", database="dse_oms")
random.seed(28)

LIQUID = ["GP", "BRACBANK", "SQURPHARMA", "BEXIMCO", "ROBI", "WALTONHIL", "BXPHARMA",
          "CITYBANK", "LHBL", "UPGDCL", "BATBC", "RENATA", "OLYMPIC", "BERGERPBL",
          "SUMITPOWER", "TITASGAS", "ISLAMIBANK", "EBL", "MARICO", "BSRMLTD"]
HEADLINE = ["GP", "BEXIMCO", "BRACBANK", "SQURPHARMA", "ROBI", "WALTONHIL"]

NEWS = [
    ("PRICE_SENSITIVE", "SQURPHARMA", "Square Pharma declares 60% cash dividend", "POSITIVE",
     "The Board recommended 60% cash dividend for the year ended 30 June; record date to follow."),
    ("EARNINGS", "GP", "Grameenphone Q2 net profit rises 8% YoY", "POSITIVE",
     "GP posted higher revenue on data growth; EPS improved over the same quarter last year."),
    ("HALT", "BEXIMCO", "Trading of BEXIMCO halted pending price-sensitive disclosure", "NEGATIVE",
     "DSE suspended trading of BEXIMCO for one hour pending a material announcement."),
    ("DIVIDEND", "BRACBANK", "BRAC Bank board recommends 15% stock dividend", "POSITIVE",
     "The bank's board approved a 15% stock dividend subject to shareholder and regulatory approval."),
    ("AGM", "WALTONHIL", "Walton Hi-Tech announces record date for AGM", "NEUTRAL",
     "The company set the record date for its upcoming Annual General Meeting and dividend entitlement."),
    ("PRICE_SENSITIVE", "ROBI", "Robi reports subscriber growth, ARPU improves", "POSITIVE",
     "Robi added net subscribers in the quarter with a modest rise in average revenue per user."),
    ("DIVIDEND", "BERGERPBL", "Berger Paints declares 250% cash dividend", "POSITIVE",
     "Strong margins supported a generous cash dividend for shareholders this year."),
    ("EARNINGS", "OLYMPIC", "Olympic Industries margins squeezed by input costs", "NEGATIVE",
     "Higher raw-material prices pressured gross margins despite steady volume."),
    ("PRICE_SENSITIVE", "RENATA", "Renata gets BSEC nod for rights issue", "NEUTRAL",
     "The regulator approved the company's rights issue to fund capacity expansion."),
    ("GENERAL", None, "DSEX crosses 5,700 on strong turnover", "POSITIVE",
     "The broad index advanced as telecom, pharma and bank sectors led gains on robust volume."),
    ("GENERAL", None, "BSEC tightens margin rules for Z-category shares", "NEGATIVE",
     "Regulator lowered margin eligibility for poorly performing Z-category securities."),
    ("PRICE_SENSITIVE", "LHBL", "LafargeHolcim Bangladesh to expand cement capacity", "POSITIVE",
     "A new line is planned to meet rising construction demand over the next two years."),
    ("EARNINGS", "CITYBANK", "City Bank posts double-digit profit growth", "POSITIVE",
     "Higher net interest income and lower provisions lifted the bank's bottom line."),
    ("HALT", "BSRMLTD", "BSRM trading suspended ahead of board meeting", "NEUTRAL",
     "Trading paused ahead of a board meeting to consider the periodic financials."),
]

WINDOWS = ["NORMAL", "NORMAL", "NORMAL", "SPOT", "BLOCK"]
STATUS_MIX = (["FILLED"] * 58 + ["PARTIAL"] * 14 + ["OPEN"] * 12 +
              ["CANCELLED"] * 9 + ["REJECTED"] * 7)
REJECTS = ["CLIENT order-value limit exceeded", "Insufficient buying power",
           "Wash-sale control: opposite-side open order exists", "TRADER order-qty limit exceeded"]


def main():
    c = mysql.connector.connect(**DB)
    cur = c.cursor(dictionary=True)
    cur.execute("SELECT id FROM exchange WHERE code='DSE'"); dse = cur.fetchone()["id"]
    cur.execute("SELECT id FROM broker WHERE trec_code='TREC-101'"); broker = cur.fetchone()["id"]
    cur.execute("SELECT id FROM app_user WHERE username='dealer1'"); dealer = cur.fetchone()["id"]
    cur.execute("SELECT id FROM client_account WHERE broker_id=%s", (broker,))
    accounts = [r["id"] for r in cur.fetchall()]

    # security id + ltp for the demo universe
    cur.execute("""SELECT s.symbol, s.id, m.ltp FROM security s JOIN market_data m ON m.security_id=s.id
                   WHERE s.exchange_id=%s AND s.symbol IN (%s)""" %
                (dse, ",".join(["%s"] * len(LIQUID))), tuple(LIQUID))
    sec = {r["symbol"]: (r["id"], float(r["ltp"] or 10)) for r in cur.fetchall()}
    if not sec:
        print("No liquid securities found — run import_dse_securities.py first."); return

    # ---- reset to a clean demo state ----
    w = c.cursor()
    for t in ("order_event", "trade", "oms_order"):
        w.execute(f"DELETE FROM {t}")
    w.execute("DELETE FROM news")

    now = dt.datetime.now()
    session_start = now.replace(hour=10, minute=5, second=0, microsecond=0)
    if session_start > now:
        session_start = now - dt.timedelta(hours=4)

    def t_rand():
        span = max(60, int((now - session_start).total_seconds()))
        return session_start + dt.timedelta(seconds=random.randint(0, span))

    # ---- news ----
    for i, (cat, sym, title, senti, body) in enumerate(NEWS):
        w.execute("INSERT INTO news (exchange_id, category, symbol, title, body, sentiment, published_at)"
                  " VALUES (%s,%s,%s,%s,%s,%s,%s)",
                  (dse, cat, sym, title, body, senti, now - dt.timedelta(hours=i * 4 + random.randint(0, 3))))

    # ---- orders + trades ----
    oref = 5000; tref = 9000; n_orders = 90; n_trades = 0
    statuses = STATUS_MIX * 2; random.shuffle(statuses); statuses = statuses[:n_orders]
    for k in range(n_orders):
        symbol = random.choice(list(sec)); sid, ltp = sec[symbol]
        side = random.choice(["BUY", "SELL"])
        otype = random.choices(["LIMIT", "MARKET"], weights=[8, 2])[0]
        price = round(ltp * random.uniform(0.98, 1.02), 1)
        qty = random.choice([100, 200, 500, 1000, 2000, 5000])
        status = statuses[k]
        created = t_rand()
        oref += 1
        filled = 0; avg = 0.0; risk = round(random.uniform(5, 45), 1); reason = None
        if status == "FILLED":
            filled = qty; avg = price
        elif status == "PARTIAL":
            filled = max(100, int(qty * random.uniform(0.3, 0.7)) // 100 * 100); avg = price
        elif status == "REJECTED":
            risk = round(random.uniform(70, 100), 1); reason = random.choice(REJECTS)
        if random.random() < 0.18 and status != "REJECTED":
            risk = round(random.uniform(45, 80), 1)   # some elevated-risk orders for the RMS feed
        w.execute(
            "INSERT INTO oms_order (order_ref, exchange_id, broker_id, dealer_id, account_id, security_id,"
            " side, order_type, trade_window, validity, price, quantity, filled_qty, avg_fill_price, status,"
            " reject_reason, risk_score, created_at, updated_at)"
            " VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,'DAY',%s,%s,%s,%s,%s,%s,%s,%s,%s)",
            (f"ORD-DMO-{oref}", dse, broker, dealer, random.choice(accounts), sid, side, otype,
             random.choice(WINDOWS), price, qty, filled, avg, status, reason, risk, created, created))
        oid = w.lastrowid
        w.execute("INSERT INTO order_event (order_id,event_type,detail,created_at) VALUES (%s,'ACCEPTED','Order accepted',%s)", (oid, created))
        if status in ("FILLED", "PARTIAL") and filled > 0:
            tref += 1; n_trades += 1
            buy_id = oid if side == "BUY" else None
            sell_id = oid if side == "SELL" else None
            w.execute("INSERT INTO trade (trade_ref, security_id, buy_order_id, sell_order_id, price, quantity,"
                      " aggressor_side, executed_at) VALUES (%s,%s,%s,%s,%s,%s,%s,%s)",
                      (f"TRD-DMO-{tref}", sid, buy_id, sell_id, avg, filled, side, created + dt.timedelta(seconds=2)))
            w.execute("INSERT INTO order_event (order_id,event_type,detail,created_at) VALUES (%s,'FILL',%s,%s)",
                      (oid, f"Filled {filled} @ {avg}", created + dt.timedelta(seconds=2)))

    # ---- dense intraday trades for headline tickers (for candles + tape) ----
    for symbol in HEADLINE:
        if symbol not in sec:
            continue
        sid, ltp = sec[symbol]; p = ltp
        for j in range(120):
            p = round(max(1, p * (1 + (random.random() - 0.5) * 0.004)), 2)
            tref += 1; n_trades += 1
            ts = session_start + dt.timedelta(seconds=int(j / 120 * max(60, (now - session_start).total_seconds())))
            w.execute("INSERT INTO trade (trade_ref, security_id, price, quantity, aggressor_side, executed_at)"
                      " VALUES (%s,%s,%s,%s,%s,%s)",
                      (f"TRD-DMO-{tref}", sid, p, random.choice([100, 200, 300, 500]),
                       random.choice(["BUY", "SELL"]), ts))

    c.commit()
    print(f"Demo seeded: {n_orders} orders, {n_trades} trades, {len(NEWS)} news items "
          f"across {len(accounts)} accounts. Restart the backend so OPEN orders go live.")
    cur.close(); w.close(); c.close()


if __name__ == "__main__":
    main()
