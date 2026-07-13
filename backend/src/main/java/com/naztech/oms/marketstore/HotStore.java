package com.naztech.oms.marketstore;

import com.naztech.oms.api.Dtos.Depth;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * The live market picture: last price, best bid/offer, and the depth ladder — the values that change
 * thousands of times a second and only ever matter in their newest form.
 *
 * <p>Today these live in MySQL's {@code market_data} table: one row per security, PK = security_id,
 * read-modify-written on every single execution. That is a cache implemented as a database table, and
 * it behaves like one — the top few tickers carry most of DSE's volume, so their rows become
 * lock-contended hot spots, and each update is two round-trips (SELECT, then UPDATE) with an undo
 * record behind it. The order book is worse: it lives in a {@code HashMap} inside one JVM, is lost on
 * restart, and is invisible to a second instance.
 *
 * <p>A key-value store is the right shape for both. MySQL stays the system of record for orders,
 * trades, positions and limits; this holds only what is derivable and current.
 *
 * <p>Implementations: {@link ValkeyHotStore} (Valkey/Redis — also Memurai on Windows) and
 * {@link InMemoryHotStore} (no server configured; behaves as the OMS does today).
 */
public interface HotStore {

    /** Last-traded price and size, as of the most recent print. */
    record Quote(BigDecimal ltp, BigDecimal bid, BigDecimal ask, long volume, long trades) {}

    /** Write the current quote for a security. Called on every trade print — must be cheap. */
    void putQuote(Long securityId, Quote quote);

    Optional<Quote> quote(Long securityId);

    /** Snapshot the depth ladder so any instance — or any process — can serve it without the book. */
    void putDepth(Long securityId, Depth depth);

    Optional<Depth> depth(Long securityId);

    /**
     * Fan a message out to every OMS instance (pub/sub). Today SSE fan-out is a list of emitters
     * inside one JVM, so a second instance's clients would never see the first's ticks.
     */
    void publish(String channel, String payload);

    boolean isLive();

    String describe();
}
