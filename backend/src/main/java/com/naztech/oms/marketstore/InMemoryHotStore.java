package com.naztech.oms.marketstore;

import com.naztech.oms.api.Dtos.Depth;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * No hot store configured. Quotes come from MySQL and depth from the in-JVM book, exactly as they did
 * before — this keeps the last values in a map so the seam behaves the same, without pretending to be
 * shared across instances.
 */
public class InMemoryHotStore implements HotStore {

    private final Map<Long, Quote> quotes = new ConcurrentHashMap<>();
    private final Map<Long, Depth> depths = new ConcurrentHashMap<>();

    @Override
    public void putQuote(Long securityId, Quote quote) {
        if (securityId != null && quote != null) {
            quotes.put(securityId, quote);
        }
    }

    @Override
    public Optional<Quote> quote(Long securityId) {
        return Optional.ofNullable(securityId == null ? null : quotes.get(securityId));
    }

    @Override
    public void putDepth(Long securityId, Depth depth) {
        if (securityId != null && depth != null) {
            depths.put(securityId, depth);
        }
    }

    @Override
    public Optional<Depth> depth(Long securityId) {
        return Optional.ofNullable(securityId == null ? null : depths.get(securityId));
    }

    @Override
    public void publish(String channel, String payload) {
        // A single instance has nobody to fan out to.
    }

    @Override
    public boolean isLive() {
        return false;
    }

    @Override
    public String describe() {
        return "in-memory (single instance; quotes from MySQL, depth from the in-JVM book)";
    }
}
