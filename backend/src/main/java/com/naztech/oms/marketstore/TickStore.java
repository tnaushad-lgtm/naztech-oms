package com.naztech.oms.marketstore;

import com.naztech.oms.api.Dtos.Candle;

import java.math.BigDecimal;
import java.util.List;

/**
 * The tick time-series: every trade print the OMS sees, kept forever and queryable by time.
 *
 * <p>MySQL is the system of record for orders, trades and positions, and it stays that way. But it
 * is the wrong shape for a tick firehose — a real DSE session prints thousands of trades a second,
 * and {@code market_data} holds one <em>overwritten</em> row per security, so today the tick itself
 * is published to the browser and then thrown away. Nothing keeps it. That is why the candlestick
 * chart falls back to a random walk for most symbols: there is no history to draw.
 *
 * <p>Implementations: {@link QuestDbTickStore} (append-only column store, candles by SAMPLE BY) and
 * {@link NoopTickStore} (when no tick store is configured — the OMS runs exactly as it does today).
 * The seam is the point: the OMS neither knows nor cares which one it has.
 */
public interface TickStore {

    /** Append one trade print. Must never block the caller — this sits on the fill path. */
    void tick(Long securityId, String symbol, BigDecimal price, long qty, long epochMillis);

    /**
     * OHLC candles built from the ticks themselves.
     *
     * @param bucketSeconds 60 = 1m, 300 = 5m, 3600 = 1h, 86400 = 1d
     * @return oldest first, or empty if this store has nothing (the caller then falls back)
     */
    List<Candle> candles(Long securityId, int bucketSeconds, int limit);

    /** Is the store actually reachable? False → the OMS carries on with what it did before. */
    boolean isLive();

    /** For the admin screen. */
    String describe();
}
