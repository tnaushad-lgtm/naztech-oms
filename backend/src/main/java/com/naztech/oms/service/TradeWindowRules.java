package com.naztech.oms.service;

import com.naztech.oms.entity.Security;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Set;

/**
 * The board / trade-window rules: <em>may this order be placed in this market at all?</em>
 *
 * <p>The OMS has stored {@code oms_order.trade_window} since the first schema and shown it in the
 * blotter, but nothing ever checked it. You could send a 100-share order to the Block Market, or an
 * odd-lot order for a whole lot, and the OMS would take it, route it, and print it — because the
 * column was a label, not a rule. A real exchange would have rejected all of those, and the mismatch
 * only surfaces at the venue, where it is somebody's money and somebody's fine.
 *
 * <p>The rules below are DSE's:
 * <ul>
 *   <li><b>Normal (Public) Market</b> — T+2, quantities in whole market lots.</li>
 *   <li><b>Spot Market</b> — same lot discipline, shorter settlement; used around record dates.</li>
 *   <li><b>Block Market</b> — negotiated size. DSE sets a floor (Tk 5 lakh by default here, see
 *       {@code rms.block-min-value}); below it the trade belongs in the public market, and sending it
 *       to the block market is how a broker gets a letter from the exchange.</li>
 *   <li><b>Odd-lot Market</b> — exists precisely for holdings <em>smaller</em> than one market lot.
 *       An odd-lot order for a whole lot is a contradiction; and where the market lot is one share,
 *       as it is for every DSE equity today, there is no such thing as an odd lot at all.</li>
 * </ul>
 */
@Component
public class TradeWindowRules {

    public static final Set<String> WINDOWS = Set.of("NORMAL", "SPOT", "BLOCK", "ODD_LOT");

    /** DSE's block-market floor: below this the trade belongs in the public market. */
    @Value("${rms.block-min-value:500000}")
    private BigDecimal blockMinValue = new BigDecimal("500000");

    /**
     * @return the reason this order may not trade in this window, or {@code null} if it may.
     */
    public String violation(Security sec, String window, long qty, BigDecimal notional) {
        String w = window == null || window.isBlank() ? "NORMAL" : window.toUpperCase();
        if (!WINDOWS.contains(w)) {
            return "Unknown trade window '" + window + "' — must be NORMAL, SPOT, BLOCK or ODD_LOT";
        }

        int lot = marketLot(sec);

        if ("ODD_LOT".equals(w)) {
            if (lot <= 1) {
                return "Odd-lot trading needs a market lot above 1 share; " + sym(sec)
                        + " trades in single shares, so use the normal market";
            }
            if (qty >= lot) {
                return "An odd lot must be smaller than the market lot (" + qty + " ≥ " + lot
                        + ") — send this to the normal market";
            }
            return null;                       // an odd lot, correctly, is NOT a multiple of the lot
        }

        // Every other window trades in whole lots.
        if (lot > 1 && qty % lot != 0) {
            return "Quantity must be a multiple of market lot " + lot + " (" + w + " market)";
        }

        if ("BLOCK".equals(w) && notional != null && notional.compareTo(blockMinValue) < 0) {
            return "Block-market orders must be at least " + blockMinValue.toPlainString()
                    + " in value (this one is " + notional.toPlainString() + ") — use the normal market";
        }

        return null;
    }

    /** {@code market_lot} is the tradable unit; {@code lot_size} is the fallback where it is unset. */
    private static int marketLot(Security sec) {
        if (sec == null) {
            return 1;
        }
        Integer lot = sec.getMarketLot() != null && sec.getMarketLot() > 0 ? sec.getMarketLot() : sec.getLotSize();
        return lot == null || lot <= 0 ? 1 : lot;
    }

    private static String sym(Security sec) {
        return sec == null || sec.getSymbol() == null ? "this instrument" : sec.getSymbol();
    }
}
