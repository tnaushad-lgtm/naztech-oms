package com.naztech.oms.exchange.itch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gap recovery is the part of a market-data feed that is invisible when it works and catastrophic
 * when it does not: a book missing one Delete carries phantom liquidity for the rest of the day, and
 * nothing about it looks wrong. These tests hold the sequencer to the only rule that matters — it
 * never applies a message out of order, and it never applies one twice.
 */
class ItchSequencerTest {

    @Test
    @DisplayName("an in-order stream is delivered straight through")
    void deliversAnInOrderStream() {
        ItchSequencer s = new ItchSequencer(1, 100);

        assertThat(text(s.accept(1, msgs("a", "b")).ready())).containsExactly("a", "b");
        assertThat(text(s.accept(3, msgs("c")).ready())).containsExactly("c");
        assertThat(s.inSequence()).isTrue();
        assertThat(s.expected()).isEqualTo(4);
        assertThat(s.health().healthy()).isTrue();
    }

    @Test
    @DisplayName("a packet that arrives early is held back, not applied out of order")
    void buffersOutOfOrderPackets() {
        ItchSequencer s = new ItchSequencer(1, 100);

        s.accept(1, msgs("a"));
        // 3 arrives before 2. Applying it now would put an Add on the book before the Delete that
        // should have come first — the book would carry liquidity that is not there.
        ItchSequencer.Delivery early = s.accept(3, msgs("c"));

        assertThat(early.ready()).isEmpty();
        assertThat(early.hasGap()).isTrue();
        assertThat(early.missing()).isEqualTo(new ItchSequencer.Gap(2, 1));
        assertThat(s.inSequence()).isFalse();
    }

    @Test
    @DisplayName("when the missing message arrives, it and everything behind it are released in order")
    void recoversTheGapAndDrainsTheBuffer() {
        ItchSequencer s = new ItchSequencer(1, 100);

        s.accept(1, msgs("a"));
        s.accept(3, msgs("c"));
        s.accept(4, msgs("d"));

        // The retransmission lands. Now 2, 3 and 4 can all be applied — in that order, together.
        ItchSequencer.Delivery filled = s.accept(2, msgs("b"));

        assertThat(text(filled.ready())).containsExactly("b", "c", "d");
        assertThat(filled.hasGap()).isFalse();
        assertThat(s.inSequence()).isTrue();
        assertThat(s.expected()).isEqualTo(5);

        ItchSequencer.Health h = s.health();
        assertThat(h.gapsDetected()).isEqualTo(1);
        assertThat(h.gapsRecovered()).isEqualTo(1);
        assertThat(h.lost()).isZero();
        assertThat(h.healthy()).isTrue();
    }

    @Test
    @DisplayName("a retransmission that races the original is dropped — a trade must not be booked twice")
    void dropsDuplicates() {
        ItchSequencer s = new ItchSequencer(1, 100);

        s.accept(1, msgs("a", "b", "c"));
        assertThat(s.expected()).isEqualTo(4);

        // The rewind server answers a request we no longer need. Every one of these is already applied.
        ItchSequencer.Delivery late = s.accept(1, msgs("a", "b", "c"));
        assertThat(late.ready()).isEmpty();

        // A packet that half overlaps: 3 is old, 4 and 5 are new. Take only what we have not seen.
        ItchSequencer.Delivery overlap = s.accept(3, msgs("c", "d", "e"));
        assertThat(text(overlap.ready())).containsExactly("d", "e");

        assertThat(s.health().duplicates()).isEqualTo(4);      // three replays plus the overlapping one
        assertThat(s.expected()).isEqualTo(6);
    }

    @Test
    @DisplayName("the stream starts wherever we joined it — a mid-day connect is not a gap")
    void adoptsTheFirstSequenceItSees() {
        ItchSequencer s = new ItchSequencer(0, 100);        // 0 = "whatever comes first"

        ItchSequencer.Delivery d = s.accept(4_001, msgs("a"));
        assertThat(text(d.ready())).containsExactly("a");
        assertThat(d.hasGap()).isFalse();                   // 4,000 messages we never wanted are not missing
        assertThat(s.expected()).isEqualTo(4_002);
    }

    @Test
    @DisplayName("a gap that is never filled is given up on loudly — and the feed resynchronises")
    void declaresLossWhenTheBufferFills() {
        ItchSequencer s = new ItchSequencer(1, 16);         // a small buffer, so this is quick

        s.accept(1, msgs("a"));
        // 2 never comes. The feed keeps running and we hold everything after it… until we cannot.
        for (int i = 0; i < 20; i++) {
            s.accept(3 + i, msgs("m" + i));
        }

        ItchSequencer.Health h = s.health();
        assertThat(h.lost()).as("message 2 was given up on").isEqualTo(1);
        assertThat(h.gapsDetected()).isEqualTo(1);
        assertThat(h.gapsRecovered()).isZero();
        assertThat(h.healthy()).as("a feed that lost a message is not healthy, and must say so").isFalse();

        // …and it resynchronises rather than stalling for ever: the stream continues from where it is.
        assertThat(s.expected()).isGreaterThan(3);
        ItchSequencer.Delivery next = s.accept(s.expected(), msgs("z"));
        assertThat(text(next.ready())).contains("z");
    }

    @Test
    @DisplayName("an empty packet changes nothing")
    void anEmptyPacketIsANoOp() {
        ItchSequencer s = new ItchSequencer(1, 100);
        assertThat(s.accept(1, List.of()).ready()).isEmpty();
        assertThat(s.expected()).isEqualTo(1);
    }

    private static List<byte[]> msgs(String... payloads) {
        List<byte[]> out = new ArrayList<>();
        for (String p : payloads) {
            out.add(p.getBytes(StandardCharsets.US_ASCII));
        }
        return out;
    }

    private static List<String> text(List<byte[]> frames) {
        return frames.stream().map(f -> new String(f, StandardCharsets.US_ASCII)).toList();
    }
}
