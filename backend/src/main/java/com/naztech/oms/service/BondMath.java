package com.naztech.oms.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Demo-grade bond pricing so the OMS can quote and trade DSE bonds on <b>price or yield</b>:
 * clean price ⇄ yield-to-maturity, plus accrued interest (ACT/365) and dirty price. Prices are
 * absolute per bond unit (in the bond's face-value currency); coupon and yield are annual percentages.
 * Perpetual bonds (no maturity) use price = face · coupon / yield. This is a pragmatic approximation
 * for the demo, not a certified fixed-income analytics library.
 */
public final class BondMath {

    private BondMath() {}

    /** Clean price per bond unit implied by an annual yield-to-maturity (%). */
    public static BigDecimal priceFromYield(double face, double couponPct, int freq, LocalDate maturity,
                                            LocalDate settle, double yieldPct) {
        int f = Math.max(1, Math.min(12, freq));   // clamp: coupon freq must divide 12 & keep stepMonths >= 1
        double coupon = face * (couponPct / 100.0) / f;   // coupon per period
        double i = (yieldPct / 100.0) / f;                // periodic yield
        if (maturity == null) {                           // perpetual: price = coupon / i
            return i <= 0 ? bd(face) : bd(coupon / i);
        }
        int n = periods(settle, maturity, f);
        if (n <= 0) return bd(face);
        double pv = 0;
        for (int k = 1; k <= n; k++) pv += coupon / Math.pow(1 + i, k);
        pv += face / Math.pow(1 + i, n);
        return bd(pv);
    }

    /** Annual yield-to-maturity (%) implied by a clean price — monotone bisection. */
    public static BigDecimal yieldFromPrice(double face, double couponPct, int freq, LocalDate maturity,
                                            LocalDate settle, double cleanPrice) {
        double lo = 0.0001, hi = 200.0;                   // % yield search bounds
        for (int it = 0; it < 200; it++) {
            double mid = (lo + hi) / 2;
            double p = priceFromYield(face, couponPct, freq, maturity, settle, mid).doubleValue();
            if (p > cleanPrice) lo = mid; else hi = mid;   // price falls as yield rises
        }
        return bd((lo + hi) / 2);
    }

    /** Accrued interest per bond unit since the last coupon (ACT/365). Zero for perpetual/unknown schedule. */
    public static BigDecimal accrued(double face, double couponPct, int freq, LocalDate maturity, LocalDate settle) {
        if (maturity == null || !maturity.isAfter(settle)) return BigDecimal.ZERO;   // perpetual or matured → no accrual
        int f = Math.max(1, Math.min(12, freq));   // clamp: coupon freq must divide 12 & keep stepMonths >= 1
        LocalDate last = lastCoupon(maturity, f, settle);
        LocalDate next = last.plusMonths(12 / f);
        long daysSince = Math.max(0, ChronoUnit.DAYS.between(last, settle));
        long daysPeriod = Math.max(1, ChronoUnit.DAYS.between(last, next));
        double coupon = face * (couponPct / 100.0) / f;
        return bd(coupon * (daysSince / (double) daysPeriod));
    }

    public static BigDecimal dirty(BigDecimal clean, BigDecimal accrued) {
        return clean.add(accrued).setScale(4, RoundingMode.HALF_UP);
    }

    private static int periods(LocalDate settle, LocalDate maturity, int freq) {
        long months = ChronoUnit.MONTHS.between(settle.withDayOfMonth(1), maturity.withDayOfMonth(1));
        return (int) Math.max(0, Math.round(months / (12.0 / freq)));
    }

    /** Most recent coupon date on or before settle, stepping back from maturity by the coupon period. */
    private static LocalDate lastCoupon(LocalDate maturity, int freq, LocalDate settle) {
        int stepMonths = 12 / freq;
        LocalDate d = maturity;
        while (d.isAfter(settle)) d = d.minusMonths(stepMonths);
        return d;
    }

    private static BigDecimal bd(double v) { return BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP); }
}
