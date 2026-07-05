"""
Enrich the local DSE securities with their REAL company name + sector (and refine
asset class for funds/bonds), scraped from each company's dsebd.org page.

Run AFTER import_dse_securities.py:  python enrich_dse_securities.py
Safe to re-run. Threaded, so all ~400 companies take well under a minute.
"""
import html as _html
import re
import ssl
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed

import mysql.connector

DB = dict(host="localhost", user="root", password="gulshan", database="dse_oms")
CTX = ssl.create_default_context()
CTX.check_hostname = False
CTX.verify_mode = ssl.CERT_NONE

NAME_RE = re.compile(r"Company Name:\s*<i>\s*([^<]+?)\s*</i>", re.I)
SECT_RE = re.compile(r"<th>\s*Sector\s*</th>\s*<td>\s*([^<]+?)\s*</td>", re.I)


def fetch(code):
    url = f"https://www.dsebd.org/displayCompany.php?name={code}"
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"})
    try:
        h = urllib.request.urlopen(req, timeout=20, context=CTX).read().decode("utf-8", "ignore")
    except Exception:
        return code, None, None
    n = NAME_RE.search(h)
    s = SECT_RE.search(h)
    name = _html.unescape(n.group(1)).strip() if n else None
    sector = _html.unescape(s.group(1)).strip() if s else None
    return code, name, sector


def asset_for(sector):
    s = (sector or "").lower()
    if "mutual fund" in s:
        return "MUTUAL_FUND"
    if "sukuk" in s:
        return "SUKUK"
    if "treasury" in s or "government" in s:
        return "GOVT_BOND"
    if "bond" in s or "debenture" in s:
        return "CORP_BOND"
    return None  # leave the existing asset class


def main():
    conn = mysql.connector.connect(**DB)
    cur = conn.cursor()
    cur.execute("SELECT id FROM exchange WHERE code='DSE'")
    dse = cur.fetchone()[0]
    cur.execute("SELECT symbol FROM security WHERE exchange_id=%s", (dse,))
    codes = [r[0] for r in cur.fetchall()]
    print(f"Enriching {len(codes)} DSE securities (name + sector) …")

    results = []
    with ThreadPoolExecutor(max_workers=12) as ex:
        futs = [ex.submit(fetch, c) for c in codes]
        for k, f in enumerate(as_completed(futs), 1):
            results.append(f.result())
            if k % 50 == 0:
                print(f"  …{k}/{len(codes)}")

    named = sectored = 0
    for code, name, sector in results:
        sets, vals = [], []
        if name:
            sets.append("name=%s"); vals.append(name); named += 1
        if sector:
            sets.append("sector=%s"); vals.append(sector); sectored += 1
            ac = asset_for(sector)
            if ac:
                sets.append("asset_class=%s"); vals.append(ac)
        if not sets:
            continue
        vals += [dse, code]
        cur.execute(f"UPDATE security SET {', '.join(sets)} WHERE exchange_id=%s AND symbol=%s", vals)

    conn.commit()
    cur.execute("SELECT COUNT(DISTINCT sector) FROM security WHERE exchange_id=%s AND sector IS NOT NULL", (dse,))
    nsec = cur.fetchone()[0]
    cur.close(); conn.close()
    print(f"Done. Names set: {named}, sectors set: {sectored}, distinct sectors: {nsec}.")


if __name__ == "__main__":
    main()
