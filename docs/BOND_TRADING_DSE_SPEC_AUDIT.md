# Bond Trading with Yield — DSE spec vs what the OMS implements

**Prepared for Naz bhai · 14 July 2026**
Source spec: `DSE Bond Trading with Yield_for Vendor_23-05-2026.pdf` (BRS V2, DSE-V2-18/05/2026),
read in full. Audited against `service/BondMath.java`, `service/BondService.java`,
`service/OrderService.java`, the FIX/ITCH layer, the `security` schema and the seed.

---

## Short answer

We have a **working end-to-end bond pipeline** — a dealer can place a bond order by price or by
yield, the yield is converted to a clean price at entry and frozen, it goes out over FIX as tag 236,
the ITCH messages carry yield, and the ticket shows accrued interest and dirty price. **The plumbing
is right and it matches several parts of the spec exactly.**

But the **pricing mathematics does not match the DSE formula**, and I can prove it against DSE's own
worked examples. Plus several structural requirements — accrued interest on trades, the bond boards
and products, coupon schedules, negative yield, settlement-date basis — are **not implemented**. This
is demo-grade fixed-income, not certification-grade. For a UAT against real DSE it needs work, and the
list below is exactly what.

---

## 1. What is implemented and correct ✅

| DSE requirement (§) | In the OMS |
| --- | --- |
| Trade a bond by **clean price OR yield** (§1.1) | `OrderService.applyBondPricing` + `BondService.quote` — both bases work |
| **Yield entered → converted to clean price, order submitted with price not yield** (§1.1) | Yes — `applyBondPricing` sets the order price to the derived clean price |
| **Clean price NOT recalculated on future trading days** (§1.1) | Yes — the clean price is computed once at entry and stored on the order; nothing re-derives it |
| Display **yield alongside price** on the order (§1.1.2, §1.2) | `BondQuote` carries yield, clean, accrued, dirty; the ticket shows them |
| **Dirty price = clean + accrued** (§1.1.2) | `BondMath.dirty()` |
| **Yield on FIX** New Order Single (D) (§1.1.8) | `FixMessageFactory` sets tag **236 = Yield** for yield-basis orders, omitting tag 44 |
| **Yield on ITCH** order/trade + **Maturity Date** on reference data (§1.1.7) | The ITCH codec carries yield on `[A]/[F]/[U]/[Q]` and maturity + yield-decimals on `[R]` — this one matches the spec cleanly |
| **4 decimals for yield** (§1.1 standards) | Rounded to 4 dp throughout |
| `price_basis` (PRICE/YIELD) recorded per order | `oms_order.price_basis`, `order_yield` columns |

---

## 2. Where the MATH is wrong ⚠️ — proven against DSE's own examples

Our `BondMath` uses a simplified textbook present-value loop. The DSE formula (§1.1.2) discounts over a
**fractional first period** — the `d/T` "stub", where `d` = days from settlement to the next coupon and
`T` = days in that coupon period. **We ignore the stub entirely**, and we discount by whole integer
periods. Two consequences, both demonstrated:

**Example B.1 (§Appendix B, the N=1 case)** — bond 1 month from maturity, coupon 12.05%, yield 10.20%:

| | Clean price |
| --- | --- |
| **DSE spec expects** | **100.1336** |
| Our `BondMath` returns | **100.0000** ❌ |

We return exactly par — because our `periods()` rounds "1 month ÷ 6-month period" down to **zero
remaining periods** and the code bails out to face value. **Any bond inside its final coupon period is
mispriced to par.** DSE even gives this case its own dedicated formula (the N=1 simple-interest form);
we don't have it.

**Example B.3 (§Appendix B, the Excel `YIELD` example)** — 13.8-year bond, price 79.9245:

| | Yield |
| --- | --- |
| **DSE / Excel `YIELD()` expects** | **8.0906%** |
| Our `BondMath` returns | **8.0692%** ❌ (≈2 basis points off) |

Two basis points on a long government bond is real money and more than "rounding". It comes from the
same omission — no stub period, so we're pricing as if settlement always lands on a coupon date.

**Root causes in `BondMath.java`:**
- `priceFromYield` discounts `k = 1..n` whole periods; **no `d/T` fractional first period**.
- `periods()` uses `Math.round(months / (12/freq))`, which collapses to 0 near maturity → returns face.
- No dedicated **N=1 formula** (DSE requires it: `Dirty = (1 + c/f) / (1 + (d/T)·(y/f)) · M`).

---

## 3. Day count and settlement date ❌

| DSE requires | We do |
| --- | --- |
| **Actual/Actual** day count (required & preferred; §1.1, examples also show 30/360) | `BondMath` uses **ACT/365** for accrued and whole-period discounting for price — neither is Actual/Actual |
| Calculations based on **settlement date** (T+1 spot / T+2 others), **including weekends & holidays** in the accrual period (§1.1.2, §1.1 standards) | `BondService.quote` uses `LocalDate.now()` — the **trade date**, not the settlement date. No settlement calendar, no holiday handling |
| **Record date / ex-coupon**: when settlement crosses the coupon date, accrued interest changes (worked example A.2 — Trader B is entitled to only 1 day of accrued) | **Not implemented.** We always accrue from the last coupon to "today"; we don't detect settlement crossing a coupon date |

The accrued-interest *structure* is actually close (`Face · c/f · daysAccrued/daysInPeriod`), and it
happened to match B.1 to within a rounding step in my check — but it's counting ACT/365 calendar days
from a stepped-back date, not Actual/Actual from the real last-coupon date on the settlement basis.

---

## 4. Negative yield ❌

DSE §1.1.3 explicitly requires the system to **calculate and accept negative yields**. Our
`yieldFromPrice` bisects with a lower bound of `0.0001%` — it **cannot return a negative yield**. A bond
trading above par near maturity can genuinely have one; we'd report the floor instead.

---

## 5. Structural things not modelled ❌

| DSE requirement | Status |
| --- | --- |
| **Accrued interest on every trade** and on **FIX Execution Report (8)** (§1.1.2, §1.1.8) | ❌ The `trade` table has **no** accrued / yield / clean / dirty columns. We compute accrued for the ticket but never persist it on the trade or return it on the ExecReport — so "settlement price = clean + accrued" can't be reconstructed downstream |
| **Boards**: `YIELDDBT`, `ALTDBT`, `BUYDBT` (§1.1.4) | ❌ Seed has a generic `BOND` board |
| **Products**: `GOVDBT`, `CORPDBT`, `SUKUK`, `AMORT`, `ZERO`, `NOTES`, `PRPTL` (§1.1.5) | ❌ We have asset classes `SUKUK` / `CORP_BOND` / `GOVT_BOND`; the product/board taxonomy isn't modelled |
| **Config**: coupon **schedule** (payment dates), **issue date**, **total issued quantity**, day-count-convention at product level (§1.1.1, §1.2) | ❌ `security` has only `coupon_rate`, `coupon_freq`, `maturity_date`, `face_value`. No schedule, issue date, issued qty or convention field |
| **Reference Price Limits / circuit breaker on clean price** (§1.1.6) | ❌ Not implemented for bonds |
| **Amortizing / zero-coupon / convertible notes / perpetual "trade same as equity"** on `ALTDBT` (§1.1.5) | ⚠️ We treat perpetual specially (`price = coupon/yield`); DSE says perpetual `PRPTL` trades **like equity**. The four "trade-same-as-equity" types aren't routed as such |
| **No opening session** for bonds — continuous + post-close only (§1.1 standards) | ❌ Bonds use the same session model as equities |

---

## 6. Recommendation — in priority order

The good news: the hard part (end-to-end wiring, FIX 236, ITCH yield, frozen clean price, the
price/yield/accrued UI) is already done. The gaps are contained in **`BondMath` plus some schema**, so
this is a bounded piece of work, not a rewrite.

1. **Rewrite `BondMath` to the DSE formula** (§1.1.2): the `d/T` stub period, the dedicated **N=1**
   case, **Actual/Actual** day count, and **allow negative yield** (drop the bisection floor, or switch
   to Newton-Raphson which handles it naturally). Then **pin it with tests against the spec's own worked
   examples** — B.1 must give 100.1336, B.3 must give 8.0906%. Those numbers become the acceptance test.
2. **Compute on the settlement date, not `now()`** — add the T+1/T+2 settlement calendar with DSE
   holidays, and handle **ex-coupon** (settlement crossing the coupon date), per example A.2.
3. **Persist accrued interest, yield and clean price on the trade**, and put accrued on the FIX
   ExecReport (8) — so settlement price is reconstructable, which is the whole point of §1.1.2.
4. **Model the boards and products** (`YIELDDBT`/`ALTDBT`/`BUYDBT`; `GOVDBT`/`CORPDBT`/`SUKUK`/`AMORT`/
   `ZERO`/`NOTES`/`PRPTL`) and add the missing security config (coupon schedule, issue date, issued
   quantity, day-count convention).
5. **Reference Price Limits (circuit breaker) on clean price** for the bond boards, and the bond
   session model (no opening auction).

Item 1 is the one that matters most and I can do it precisely — DSE handed us the exact expected
numbers, so there is no ambiguity about "correct".

---

*The honest one-liner for Naz bhai: the bond feature demos convincingly and is wired correctly through
FIX and ITCH, but its pricing engine is a textbook approximation, not DSE's formula — it returns par
for a bond in its last coupon period and is ~2bp off on a long bond, it can't produce a negative yield,
and it doesn't yet persist accrued interest on trades. All fixable, and item 1 above is the core of it.*
