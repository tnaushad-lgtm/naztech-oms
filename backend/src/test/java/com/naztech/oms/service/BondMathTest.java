package com.naztech.oms.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Bond price⇄yield engine: perpetual pricing, par identity, inverse round-trip, monotonicity, accrued. */
class BondMathTest {

    private final LocalDate settle = LocalDate.of(2026, 7, 4);
    private final LocalDate mat = LocalDate.of(2034, 6, 30);   // TB10Y2034

    @Test
    void perpetual_price_is_coupon_over_yield() {
        // PBLPBOND: face 5000, coupon 10%, perpetual → price = face·coupon/yield
        assertThat(BondMath.priceFromYield(5000, 10, 2, null, settle, 10).doubleValue()).isCloseTo(5000, within(0.5));
        assertThat(BondMath.priceFromYield(5000, 10, 2, null, settle, 8).doubleValue()).isCloseTo(6250, within(1.0));
    }

    @Test
    void fixed_bond_at_coupon_yield_prices_at_par() {
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
