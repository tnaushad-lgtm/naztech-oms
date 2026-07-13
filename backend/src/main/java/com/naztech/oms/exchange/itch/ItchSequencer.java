package com.naztech.oms.exchange.itch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Sequence tracking and gap recovery for a live ITCH feed.
 *
 * <p>A market-data feed is a numbered stream, and the numbers are the whole contract. Over UDP
 * multicast — which is how every real exchange ships ITCH, DSE included — packets arrive out of
 * order, arrive twice, or do not arrive. An order book rebuilt from a stream with a hole in it is not
 * a slightly-wrong book: an <em>Add</em> whose matching <em>Delete</em> was dropped leaves phantom
 * liquidity on the ladder for the rest of the day, and a dealer trading against it is trading against
 * something that is not there.
 *
 * <p>So this class refuses to guess. Given each packet's sequence number it will:
 * <ul>
 *   <li>deliver messages <b>in order</b>, and only in order;</li>
 *   <li><b>buffer</b> anything that arrives early, rather than applying it out of sequence;</li>
 *   <li><b>drop</b> anything already seen — a retransmission that raced the original must not book
 *       the same trade twice;</li>
 *   <li>report the <b>gap</b> so the transport can ask for it again (MoldUDP64 rewind, or a SoupBinTCP
 *       reconnect at the sequence we still want).</li>
 * </ul>
 *
 * <p>The one thing it will not do is fail silently. If the buffer fills — the retransmit never came,
 * the feed has moved on and we are hopelessly behind — it says so, declares the loss, and resynchronises
 * to the stream rather than pretending the missing messages were unimportant. A visible resync is
 * recoverable; a quietly corrupt book is not.
 */
public final class ItchSequencer {

    private static final Logger log = LoggerFactory.getLogger(ItchSequencer.class);

    /** A run of messages we are missing, and want back. */
    public record Gap(long from, long count) {
        public long to() {
            return from + count - 1;
        }
    }

    /** What {@link #accept} decided: what may be applied now, and what still has to be asked for. */
    public record Delivery(List<byte[]> ready, Gap missing) {
        static final Delivery NOTHING = new Delivery(List.of(), null);

        public boolean hasGap() {
            return missing != null;
        }
    }

    private final int maxBuffered;
    private final NavigableMap<Long, byte[]> early = new TreeMap<>();

    private long expected;
    private long delivered;
    private long duplicates;
    private long gapsDetected;
    private long gapsRecovered;
    private long lost;
    private Gap outstanding;

    /**
     * @param firstExpected the sequence of the first message we expect — from the SoupBinTCP login
     *                      response, or the first MoldUDP64 packet seen. 0 means "whatever comes first".
     * @param maxBuffered   how far ahead of a gap we will hold messages before giving up on it
     */
    public ItchSequencer(long firstExpected, int maxBuffered) {
        this.expected = firstExpected;
        this.maxBuffered = Math.max(16, maxBuffered);
    }

    /**
     * Take a packet: {@code messages} are consecutive, the first of them numbered {@code seq}.
     *
     * @return the messages that may now be applied, in order, and the gap still outstanding (if any)
     */
    public synchronized Delivery accept(long seq, List<byte[]> messages) {
        if (messages == null || messages.isEmpty()) {
            return Delivery.NOTHING;
        }
        if (expected == 0) {
            expected = seq;                         // the stream starts wherever we joined it
        }

        long last = seq + messages.size() - 1;
        if (last < expected) {
            duplicates += messages.size();          // entirely behind us: a late retransmit, already applied
            return Delivery.NOTHING;
        }

        List<byte[]> ready = new ArrayList<>();

        if (seq <= expected) {
            // Overlaps the sequence we want. Skip the part we already have; take the rest.
            int skip = (int) (expected - seq);
            duplicates += skip;
            for (int i = skip; i < messages.size(); i++) {
                ready.add(messages.get(i));
                expected++;
            }
            drainEarly(ready);
        } else {
            // It is ahead of us. Hold it — applying an Add before the Delete that precedes it is how a
            // book acquires liquidity that was never there.
            for (int i = 0; i < messages.size(); i++) {
                early.put(seq + i, messages.get(i));
            }
            if (outstanding == null) {
                gapsDetected++;
                log.warn("ITCH gap: expected {}, saw {} — {} message(s) missing, asking for them back",
                        expected, seq, seq - expected);
            }
        }

        if (!early.isEmpty() && early.size() > maxBuffered) {
            declareLoss();
            drainEarly(ready);
        }

        delivered += ready.size();
        Gap gap = gap();
        if (outstanding != null && gap == null) {
            gapsRecovered++;
            log.info("ITCH gap recovered — back in sequence at {}", expected);
        }
        outstanding = gap;
        return new Delivery(ready, gap);
    }

    /** Everything now contiguous with {@code expected} may be applied. */
    private void drainEarly(List<byte[]> ready) {
        byte[] next;
        while ((next = early.remove(expected)) != null) {
            ready.add(next);
            expected++;
        }
    }

    /**
     * The retransmit never came and the buffer is full: the feed has run away from us. Skip to the
     * oldest message we are holding and say out loud what was lost — this is the point at which a
     * downstream consumer should take a fresh snapshot rather than trust the book.
     */
    private void declareLoss() {
        long resumeAt = early.firstKey();
        long missing = resumeAt - expected;
        lost += missing;
        log.error("ITCH gap NOT recovered: giving up on {} message(s) from {} — resynchronising at {}. "
                + "The book is no longer trustworthy; take a snapshot.", missing, expected, resumeAt);
        expected = resumeAt;
        // Forget the gap we just abandoned. Without this the drain that follows would find the stream
        // contiguous again and score it as a recovery — so a feed that had lost messages would report
        // "1 gap, 1 recovered, all healthy", which is precisely the lie this class exists to prevent.
        outstanding = null;
    }

    /** What we are still waiting for, or null if the stream is contiguous. */
    private Gap gap() {
        if (early.isEmpty()) {
            return null;
        }
        return new Gap(expected, early.firstKey() - expected);
    }

    /** True when the book built from this feed can be trusted — nothing is missing. */
    public synchronized boolean inSequence() {
        return early.isEmpty();
    }

    public synchronized long expected() {
        return expected;
    }

    public synchronized Health health() {
        return new Health(expected, delivered, duplicates, gapsDetected, gapsRecovered, lost, early.size());
    }

    /**
     * What the feed has actually done. {@code lost} is the number that matters: it is not "we had a
     * gap" (gaps happen, and are recovered), it is "we gave up on one", and any book built after that
     * needs a snapshot before it can be believed.
     */
    public record Health(long expected, long delivered, long duplicates,
                         long gapsDetected, long gapsRecovered, long lost, int buffered) {
        public boolean healthy() {
            return lost == 0 && buffered == 0;
        }
    }
}
