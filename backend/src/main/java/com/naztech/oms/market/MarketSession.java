package com.naztech.oms.market;

/**
 * The exchange-wide trading phase. This is the outermost of the three trading gates the OMS has, and
 * it is checked first — a closed market outranks a halted broker ({@code broker.status}) and a
 * suspended instrument ({@code security.status}).
 *
 * <p>Not to be confused with {@code oms_order.trade_window} (NORMAL / SPOT / BLOCK / ODD_LOT), which
 * is the <em>board</em> an order trades on — a different axis entirely.
 */
public enum MarketSession {

    /** Outside trading hours. No orders, no matching, no market data. DSE trades 10:00–14:30. */
    CLOSED,

    /**
     * Orders are accepted and rest on the book, but nothing matches. This is how a real open works:
     * the book builds up first, then the exchange uncrosses it at the opening bell.
     */
    PRE_OPEN,

    /** Continuous trading. Orders match, the tape runs, the ITCH feed is live. */
    OPEN,

    /**
     * Trading suspended mid-session (a circuit breaker, a market-wide event). New orders are refused;
     * existing orders stay on the book, and cancels remain legal — a halt must never trap a client in
     * a position they are trying to get out of.
     */
    HALTED;

    /** Can a new order be accepted at all? */
    public boolean acceptsOrders() {
        return this == PRE_OPEN || this == OPEN;
    }

    /** Can orders actually trade against each other? False in PRE_OPEN: the book builds, it does not cross. */
    public boolean allowsMatching() {
        return this == OPEN;
    }

    /** Is the market-data feed running? */
    public boolean feedLive() {
        return this == PRE_OPEN || this == OPEN;
    }
}
