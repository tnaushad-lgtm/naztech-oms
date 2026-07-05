package com.naztech.oms.service;

import com.naztech.oms.api.Dtos.BondQuote;
import com.naztech.oms.entity.Security;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
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
     * Quote a bond from a given basis: {@code YIELD} → derive clean price; {@code PRICE} → derive yield.
     * Also returns accrued interest and the dirty (settlement) price.
     */
    public BondQuote quote(Security s, String basis, BigDecimal value) {
        LocalDate settle = LocalDate.now();
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
                mat == null ? null : mat.toString(), yld, clean, accrued, dirty);
    }
}
