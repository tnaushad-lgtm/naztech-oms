package com.naztech.oms.exchange.fixsim;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Last-traded prices for the seeded DSE/CSE instruments, used by {@link LocalExchange} to price
 * MARKET orders (which carry no Price(44)) and yield-basis bond orders (which carry Yield(236)
 * instead of a price).
 *
 * <p>These mirror {@code db/seed.sql}. The local exchange has no database connection by design —
 * it is a standalone counterparty process, not part of the OMS — so the handful of prices it needs
 * to quote realistically live here. An unknown symbol falls back to {@link #FALLBACK}.
 */
final class ReferencePrices {

    static final BigDecimal FALLBACK = new BigDecimal("100.00");

    private static final Map<String, String> LTP = Map.ofEntries(
            Map.entry("GP", "305.40"),
            Map.entry("ROBI", "28.70"),
            Map.entry("SQURPHARMA", "212.30"),
            Map.entry("BXPHARMA", "118.60"),
            Map.entry("RENATA", "720.10"),
            Map.entry("BEXIMCO", "102.40"),
            Map.entry("BRACBANK", "47.80"),
            Map.entry("CITYBANK", "22.10"),
            Map.entry("ISLAMIBANK", "35.60"),
            Map.entry("EBL", "33.40"),
            Map.entry("WALTONHIL", "545.20"),
            Map.entry("BATBC", "498.70"),
            Map.entry("MARICO", "2310.50"),
            Map.entry("OLYMPIC", "178.30"),
            Map.entry("LHBL", "68.90"),
            Map.entry("BSRMLTD", "95.20"),
            Map.entry("UPGDCL", "142.60"),
            Map.entry("SUMITPOWER", "34.10"),
            Map.entry("TITASGAS", "38.50"),
            Map.entry("BERGERPBL", "1620.40"),
            Map.entry("KRISHIBID", "14.20"),
            Map.entry("MAMUNAGRO", "21.80"),
            Map.entry("1JANATAMF", "6.40"),
            Map.entry("GREENDELMF", "8.70"),
            Map.entry("BEXGSUKUK", "96.50"),
            Map.entry("PBLPBOND", "4980.00"),
            Map.entry("TB10Y2034", "99.85"));

    private ReferencePrices() {
    }

    static BigDecimal of(String symbol) {
        if (symbol == null) {
            return FALLBACK;
        }
        String px = LTP.get(symbol.toUpperCase());
        return px == null ? FALLBACK : new BigDecimal(px);
    }
}
