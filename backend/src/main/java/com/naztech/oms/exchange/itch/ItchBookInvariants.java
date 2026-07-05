package com.naztech.oms.exchange.itch;

import com.naztech.oms.api.Dtos.Depth;
import com.naztech.oms.api.Dtos.DepthLevel;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Consistency validation for a reconstructed {@link ItchOrderBook}. Two levels:
 * <ul>
 *   <li><b>Structural</b> ({@link #checkStructural}) — invariants that must hold for <i>any</i> correctly
 *       aggregated book at all times: every displayed level has positive quantity and ≥1 order, and price
 *       levels are strictly monotonic and distinct (bids high→low, asks low→high). A violation here means a
 *       real bug in book reconstruction / depth aggregation.</li>
 *   <li><b>Full</b> ({@link #check}) — structural plus a <b>crossed-market</b> flag (best bid &gt; best ask).
 *       A crossed book is a data-quality signal worth surfacing from a feed; our non-matching simulator can
 *       momentarily produce one, so it is reported, not treated as a structural bug.</li>
 * </ul>
 * Used by tests as a regression guard and, opt-in ({@code itch.validate=true}), by {@link ItchGateway} to
 * log anomalies from a running feed.
 */
public final class ItchBookInvariants {

    private ItchBookInvariants() {}

    public record Report(List<String> violations) {
        public boolean ok() { return violations.isEmpty(); }
    }

    /** Structural invariants + a crossed-market (bestBid &gt; bestAsk) data-quality flag. */
    public static Report check(ItchOrderBook book, int priceDecimals, int levels) {
        List<String> v = structural(book, priceDecimals, levels);
        long bb = book.bestBidRaw(), ba = book.bestAskRaw();
        if (bb >= 0 && ba >= 0 && bb > ba) v.add("crossed book: bestBid " + bb + " > bestAsk " + ba);
        return new Report(v);
    }

    /** Structural invariants only — must always hold for a correctly reconstructed book. */
    public static Report checkStructural(ItchOrderBook book, int priceDecimals, int levels) {
        return new Report(structural(book, priceDecimals, levels));
    }

    private static List<String> structural(ItchOrderBook book, int priceDecimals, int levels) {
        List<String> v = new ArrayList<>();
        Depth d = book.depth("?", BigDecimal.ZERO, priceDecimals, levels);
        checkSide(d.bids(), true, "bid", v);
        checkSide(d.asks(), false, "ask", v);
        return v;
    }

    private static void checkSide(List<DepthLevel> side, boolean descending, String tag, List<String> v) {
        BigDecimal prev = null;
        for (DepthLevel l : side) {
            if (l.quantity() == null || l.quantity() <= 0) v.add(tag + " qty<=0 @ " + l.price());
            if (l.orders() == null || l.orders() < 1)       v.add(tag + " orders<1 @ " + l.price());
            if (prev != null) {
                int cmp = l.price().compareTo(prev);
                if (descending && cmp >= 0)  v.add(tag + " levels not strictly descending @ " + l.price());
                if (!descending && cmp <= 0) v.add(tag + " levels not strictly ascending @ " + l.price());
            }
            prev = l.price();
        }
    }
}
