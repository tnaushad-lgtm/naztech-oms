package com.naztech.oms.service;

import com.naztech.oms.api.Dtos.BondQuote;
import com.naztech.oms.entity.Security;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;

/** Bond helpers: identify fixed-income instruments and compute price/yield/accrued quotes via {@link BondMath}. */
@Service
public class BondService {

    public boolean isBond(Security s) {
        if (s == null || s.getAssetClass() == null) return false;
        String ac = s.getAssetClass().toUpperCase();
        return ac.contains("BOND") || ac.equals("SUKUK");
    }

    /**
     * The settlement date for a bond trade struck today, per the DSE BRS: <b>T+2</b> for the public,
     * block and regular markets, <b>T+1</b> for the spot market — counted in <em>trading days</em>,
     * because a trade cannot settle on a day the market is shut. The Bangladeshi weekend is Friday and
     * Saturday.
     *
     * <p>This matters because accrued interest belongs to whoever holds the bond <em>at settlement</em>
     * (BRS §1.1.2 — "based on settlement date rather than trade date"): computing it off the trade date
     * pays a seller for days they did not hold, and around a coupon date it charges the buyer for a
     * coupon they will never receive.
     */
    public LocalDate settlementDate(LocalDate tradeDate, String tradeWindow) {
        int days = "SPOT".equalsIgnoreCase(tradeWindow) ? 1 : 2;
        LocalDate d = tradeDate;
        while (days > 0) {
            d = d.plusDays(1);
            if (d.getDayOfWeek() != DayOfWeek.FRIDAY && d.getDayOfWeek() != DayOfWeek.SATURDAY) {
                days--;
            }
        }
        return d;
    }

    /** Quote on the NORMAL (T+2) market. */
    public BondQuote quote(Security s, String basis, BigDecimal value) {
        return quote(s, basis, value, "NORMAL");
    }

    /**
     * Quote a bond from a given basis: {@code YIELD} → derive clean price; {@code PRICE} → derive yield.
     * Also returns accrued interest, the dirty (settlement) price, and the settlement date itself —
     * all computed <b>as of settlement</b>, not as of the trade.
     */
    public BondQuote quote(Security s, String basis, BigDecimal value, String tradeWindow) {
        LocalDate settle = settlementDate(LocalDate.now(), tradeWindow);
        double face = s.getFaceValue() == null ? 100.0 : s.getFaceValue().doubleValue();
        double coupon = s.getCouponRate() == null ? 0.0 : s.getCouponRate().doubleValue();
        int freq = (s.getCouponFreq() == null || s.getCouponFreq() <= 0) ? 2 : Math.min(12, s.getCouponFreq());
        LocalDate mat = s.getMaturityDate();

        BigDecimal clean, yld;
        if ("YIELD".equalsIgnoreCase(basis)) {
            yld = value;
            clean = BondMath.priceFromYield(face, coupon, freq, mat, settle, value.doubleValue());
        } else {
            clean = value;
            yld = BondMath.yieldFromPrice(face, coupon, freq, mat, settle, value.doubleValue());
        }
        BigDecimal accrued = BondMath.accrued(face, coupon, freq, mat, settle);
        BigDecimal dirty = BondMath.dirty(clean, accrued);
        return new BondQuote(s.getSymbol(), BigDecimal.valueOf(face), s.getCouponRate(), freq,
                mat == null ? null : mat.toString(), yld, clean, accrued, dirty, settle.toString());
    }

    /** Accrued interest per unit for a trade in this bond settling per the given window. Zero for non-bonds. */
    public BigDecimal accruedFor(Security s, String tradeWindow) {
        if (!isBond(s)) return BigDecimal.ZERO;
        double face = s.getFaceValue() == null ? 100.0 : s.getFaceValue().doubleValue();
        double coupon = s.getCouponRate() == null ? 0.0 : s.getCouponRate().doubleValue();
        int freq = (s.getCouponFreq() == null || s.getCouponFreq() <= 0) ? 2 : Math.min(12, s.getCouponFreq());
        return BondMath.accrued(face, coupon, freq, s.getMaturityDate(),
                settlementDate(LocalDate.now(), tradeWindow));
    }

    /** The yield implied by a traded clean price, for stamping on the trade record. Null for non-bonds. */
    public BigDecimal yieldAt(Security s, BigDecimal cleanPrice, String tradeWindow) {
        if (!isBond(s) || cleanPrice == null || cleanPrice.signum() <= 0) return null;
        double face = s.getFaceValue() == null ? 100.0 : s.getFaceValue().doubleValue();
        double coupon = s.getCouponRate() == null ? 0.0 : s.getCouponRate().doubleValue();
        int freq = (s.getCouponFreq() == null || s.getCouponFreq() <= 0) ? 2 : Math.min(12, s.getCouponFreq());
        return BondMath.yieldFromPrice(face, coupon, freq, s.getMaturityDate(),
                settlementDate(LocalDate.now(), tradeWindow), cleanPrice.doubleValue());
    }
}
