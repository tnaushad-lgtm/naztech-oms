package com.naztech.oms.service;

import com.naztech.oms.api.Dtos.Depth;
import com.naztech.oms.api.Dtos.DepthLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Pushes the order book to the terminal instead of letting it ask.
 *
 * <p>The depth ladder used to poll {@code /depth} every two seconds. Two seconds is an eternity on a
 * book — levels appear and are gone again between requests, so the dealer was shown a book that had
 * already stopped being true, and the OMS was answering the same question sixty times a minute per
 * open terminal whether or not anything had changed. Both halves of that are wrong.
 *
 * <p>So: the backend owns the book (it already did — {@link MarketDataGateway}), and publishes it on
 * the SSE stream <b>when it changes</b>. A terminal that is looking at an instrument registers that
 * interest with {@link #watch}; nothing else is computed or sent. The registration expires, so a
 * closed tab stops costing anything without having to tell us it closed.
 *
 * <h2>Sequence numbers and gaps</h2>
 * Every push for an instrument carries a monotonically increasing {@code seq}. A client that sees
 * {@code seq} jump has missed an update — a dropped SSE frame, a reconnect, a tab that was asleep —
 * and re-syncs from {@code GET /api/market/{id}/depth/snapshot}, which returns the book <em>and</em>
 * the sequence it is current as of. That is the same snapshot-plus-incremental contract the ITCH feed
 * itself uses, for the same reason: a book you cannot resynchronise is a book you cannot trust.
 */
@Service
public class DepthBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(DepthBroadcaster.class);

    /** Beyond this many instruments watched at once, the cost stops being worth the freshness. */
    private static final int MAX_WATCHED = 64;

    private final MarketDataGateway gateway;
    private final StreamService stream;

    /** securityId → when this interest lapses (epoch ms). Refreshed on every watch call. */
    private final Map<Long, Long> watched = new ConcurrentHashMap<>();
    private final Map<Long, Integer> levels = new ConcurrentHashMap<>();
    /** securityId → the last book we sent, so an unchanged book is not sent twice. */
    private final Map<Long, String> lastSent = new ConcurrentHashMap<>();
    private final Map<Long, AtomicLong> seqs = new ConcurrentHashMap<>();

    @Value("${app.depth.watch-ttl-ms:45000}")
    private long watchTtlMs;

    public DepthBroadcaster(MarketDataGateway gateway, StreamService stream) {
        this.gateway = gateway;
        this.stream = stream;
    }

    /**
     * "I am looking at this instrument." Called when a terminal selects one, and refreshed while it
     * stays selected. Returns the book <em>and</em> its current sequence, so the caller has something
     * to draw immediately and knows where in the stream it is starting from — a client that began
     * listening without a sequence would have no way to tell a gap from a first message.
     */
    public Map<String, Object> watch(Long securityId, int levelCount) {
        int n = Math.max(1, Math.min(levelCount, 50));
        if (!watched.containsKey(securityId) && watched.size() >= MAX_WATCHED) {
            log.debug("Depth watch list is full ({}) — {} will be polled instead", MAX_WATCHED, securityId);
        } else {
            watched.put(securityId, System.currentTimeMillis() + watchTtlMs);
            levels.merge(securityId, n, Math::max);       // two terminals, different depths: serve the deeper
        }
        return payload(securityId, gateway.depth(securityId, n), seq(securityId).get());
    }

    /** The book plus the sequence it is current as of — what a client re-syncs from after a gap. */
    public Map<String, Object> snapshot(Long securityId, int levelCount) {
        Depth d = gateway.depth(securityId, Math.max(1, Math.min(levelCount, 50)));
        Map<String, Object> m = payload(securityId, d, seq(securityId).get());
        m.put("snapshot", true);
        return m;
    }

    /**
     * Compute each watched book and push the ones that moved.
     *
     * <p>Comparing against the last payload is what keeps this cheap: a quiet instrument costs one
     * book build and a string compare, and no bytes on the wire at all. Under the ITCH feed most
     * instruments are quiet most of the time, and the ones that are not are the ones being watched.
     */
    @Scheduled(fixedDelayString = "${app.depth.push-ms:400}")
    public void push() {
        if (watched.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Map.Entry<Long, Long> e : watched.entrySet()) {
            Long securityId = e.getKey();
            if (e.getValue() < now) {                    // nobody has looked at it in a while
                watched.remove(securityId);
                levels.remove(securityId);
                lastSent.remove(securityId);
                continue;
            }
            try {
                Depth d = gateway.depth(securityId, levels.getOrDefault(securityId, 10));
                String fingerprint = fingerprint(d);
                if (fingerprint.equals(lastSent.get(securityId))) {
                    continue;                            // the book has not moved; say nothing
                }
                lastSent.put(securityId, fingerprint);
                stream.publish("depth", payload(securityId, d, seq(securityId).incrementAndGet()));
            } catch (Exception ex) {
                log.debug("Depth push failed for {}: {}", securityId, ex.toString());
            }
        }
    }

    /** Everything that would make the ladder look different. Prices and sizes; nothing else. */
    private static String fingerprint(Depth d) {
        StringBuilder sb = new StringBuilder(64);
        sb.append(d.ltp());
        for (List<DepthLevel> side : List.of(d.bids(), d.asks())) {
            sb.append('|');
            for (DepthLevel l : side) {
                sb.append(l.price()).append(':').append(l.quantity()).append(':').append(l.orders()).append(',');
            }
        }
        return sb.toString();
    }

    private static Map<String, Object> payload(Long securityId, Depth d, long seq) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("securityId", securityId);
        m.put("symbol", d.symbol());
        m.put("ltp", d.ltp());
        m.put("seq", seq);
        m.put("bids", new ArrayList<>(d.bids()));
        m.put("asks", new ArrayList<>(d.asks()));
        return m;
    }

    private AtomicLong seq(Long securityId) {
        return seqs.computeIfAbsent(securityId, k -> new AtomicLong());
    }

    // test seams
    int watchedCount() { return watched.size(); }
    long sequenceOf(Long securityId) { return seq(securityId).get(); }
}
