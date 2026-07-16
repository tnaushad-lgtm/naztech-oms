package com.naztech.oms.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The bond engine, held to the numbers DSE wrote down.
 *
 * <p>The BRS ("Bond Trading with Yield", V2 23-05-2026) includes worked examples with exact expected
 * outputs — B.1 prices a bond in its final coupon period, B.3 solves a yield the same way Excel's
 * {@code YIELD(..., basis=1)} does. Those figures are the acceptance test: DSE computed them, so
 * "correct" is not a matter of opinion here. The previous engine failed both (it returned par for
 * B.1 and was ~2bp off on B.3), which is exactly why these tests pin the spec's numbers and not
 * numbers derived from our own code.
 */
class BondMathTest {

    private final LocalDate settle = LocalDate.of(2026, 7, 4);
    private final LocalDate mat = LocalDate.of(2034, 6, 30);   // TB10Y2034

    // ---------------------------------------------------------------- the DSE worked examples

    @Test
    @DisplayName("BRS example B.1: bond in its FINAL coupon period — the N=1 simple-interest formula")
    void dseExampleB1_finalCouponPeriod() {
        // Coupon 12.05% semi-annual, face 100, settle 02/04/2026, maturity 08/05/2026, yield 10.20%.
        // The BRS expects: dirty 104.9603, accrued 4.8267, clean 100.13366.
        // The old engine returned exactly 100.0000 here — it rounded "one month left" down to zero
        // whole periods and bailed out to par. Every bond inside its last coupon period was par.
        LocalDate s = LocalDate.of(2026, 4, 2);
        LocalDate m = LocalDate.of(2026, 5, 8);

        double clean = BondMath.priceFromYield(100, 12.05, 2, m, s, 10.20).doubleValue();
        double accrued = BondMath.accrued(100, 12.05, 2, m, s).doubleValue();

        assertThat(clean).isCloseTo(100.1337, within(0.001));      // BRS prints 100.13366
        assertThat(accrued).isCloseTo(4.8267, within(0.001));
        assertThat(clean + accrued).isCloseTo(104.9603, within(0.001));
    }

    @Test
    @DisplayName("BRS example B.3: price 79.9245 must solve to 8.0906% — Excel YIELD, Actual/Actual")
    void dseExampleB3_priceToYield() {
        // 5.65% semi-annual, face 100, settle 13/10/2022, maturity 28/07/2036. The BRS quotes Excel:
        // YIELD(...) = 8.0906040%. The old engine gave 8.0692% — two basis points, from ignoring the
        // d/T stub period. Two basis points on a 14-year government bond is money, not rounding.
        LocalDate s = LocalDate.of(2022, 10, 13);
        LocalDate m = LocalDate.of(2036, 7, 28);

        double y = BondMath.yieldFromPrice(100, 5.65, 2, m, s, 79.9245).doubleValue();
        assertThat(y).isCloseTo(8.0906, within(0.001));

        // …and the round trip reproduces the price (Excel PRICE(...) = 79.92450).
        double p = BondMath.priceFromYield(100, 5.65, 2, m, s, 8.0906).doubleValue();
        assertThat(p).isCloseTo(79.9245, within(0.005));
    }

    // ---------------------------------------------------------------- BRS requirements

    @Test
    @DisplayName("BRS §1.1.3: a bond above par near maturity has a NEGATIVE yield, and we can say so")
    void negativeYieldIsCalculated() {
        // One month to maturity, 2% coupon, priced well above the pull-to-par: the arithmetic makes
        // the yield negative. The old bisection floor of +0.0001% made this impossible to express —
        // it reported the floor instead of the truth.
        LocalDate s = LocalDate.of(2026, 7, 14);
        LocalDate m = LocalDate.of(2026, 8, 14);

        double y = BondMath.yieldFromPrice(100, 2.0, 2, m, s, 100.60).doubleValue();
        assertThat(y).isLessThan(0);

        // and it round-trips: pricing at that negative yield recovers the clean price
        double p = BondMath.priceFromYield(100, 2.0, 2, m, s, y).doubleValue();
        assertThat(p).isCloseTo(100.60, within(0.005));
    }

    @Test
    @DisplayName("BRS A.2 (ex-coupon): settlement just after a coupon date accrues days, not a period")
    void accruedResetsAtTheCouponDate() {
        // Semi-annual coupons anchored at maturity 2030-01-10 → a coupon falls on 2026-07-10.
        // Settling one day AFTER it must accrue one day's interest; one day BEFORE it, nearly the
        // full period. This is the whole reason the BRS insists on settlement-date arithmetic: the
        // buyer must not pay accrued for a coupon the seller keeps.
        LocalDate m = LocalDate.of(2030, 1, 10);

        double dayAfter = BondMath.accrued(100, 5.0, 2, m, LocalDate.of(2026, 7, 11)).doubleValue();
        double dayBefore = BondMath.accrued(100, 5.0, 2, m, LocalDate.of(2026, 7, 9)).doubleValue();

        assertThat(dayAfter).isCloseTo(100 * 0.025 / 184, within(0.001));   // one day of a 184-day period
        assertThat(dayBefore).isGreaterThan(2.4);                            // ~the whole 2.5 coupon
    }

    @Test
    @DisplayName("day count is Actual/Actual — a 181-day period divides by 181, not 180 or 365/2")
    void actualActualDayCount() {
        // From BRS B.1: period 2025-11-08 → 2026-05-08 is 181 actual days; settle 2026-04-02 leaves
        // 36 days, so 145 accrued. 100·(12.05%/2)·145/181 = 4.8267. A 30/360 or ACT/365 count gives
        // a visibly different number, so this pins the convention itself.
        double a = BondMath.accrued(100, 12.05, 2, LocalDate.of(2026, 5, 8), LocalDate.of(2026, 4, 2))
                .doubleValue();
        assertThat(a).isCloseTo(100 * (0.1205 / 2) * 145.0 / 181.0, within(0.0001));
    }

    // ---------------------------------------------------------------- structural properties (kept)

    @Test
    void perpetual_price_is_coupon_over_yield() {
        // PBLPBOND: face 5000, coupon 10%, perpetual → price = face·coupon/yield
        assertThat(BondMath.priceFromYield(5000, 10, 2, null, settle, 10).doubleValue()).isCloseTo(5000, within(0.5));
        assertThat(BondMath.priceFromYield(5000, 10, 2, null, settle, 8).doubleValue()).isCloseTo(6250, within(1.0));
    }

    @Test
    void fixed_bond_at_coupon_yield_prices_near_par() {
        double p = BondMath.priceFromYield(100, 8.5, 2, mat, settle, 8.5).doubleValue();
        assertThat(p).isCloseTo(100, within(1.0));   // yield ≈ coupon ⇒ price ≈ par
    }

    @Test
    void price_and_yield_are_inverses() {
        double price = BondMath.priceFromYield(100, 8.5, 2, mat, settle, 9.25).doubleValue();
        double y = BondMath.yieldFromPrice(100, 8.5, 2, mat, settle, price).doubleValue();
        assertThat(y).isCloseTo(9.25, within(0.02));
    }

    @Test
    void higher_yield_means_lower_price() {
        double pLow = BondMath.priceFromYield(100, 8.5, 2, mat, settle, 7).doubleValue();
        double pHigh = BondMath.priceFromYield(100, 8.5, 2, mat, settle, 11).doubleValue();
        assertThat(pLow).isGreaterThan(pHigh);
    }

    @Test
    void accrued_nonnegative_and_dirty_ge_clean() {
        var accrued = BondMath.accrued(100, 8.5, 2, mat, settle);
        var clean = BondMath.priceFromYield(100, 8.5, 2, mat, settle, 8.5);
        assertThat(accrued.signum()).isGreaterThanOrEqualTo(0);
        assertThat(BondMath.dirty(clean, accrued).doubleValue()).isGreaterThanOrEqualTo(clean.doubleValue());
        assertThat(BondMath.accrued(5000, 10, 2, null, settle).signum()).isZero();  // perpetual → no schedule
    }
}
