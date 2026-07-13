package com.naztech.oms.service;

import com.naztech.oms.api.Dtos.Depth;
import com.naztech.oms.api.Dtos.DepthLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The push has to be worth more than the poll it replaced, which means two things must hold: an
 * unchanged book costs nothing on the wire, and a client that misses a push can tell.
 */
class DepthBroadcasterTest {

    private MarketDataGateway gateway;
    private DepthBroadcaster broadcaster;
    private final List<Map<String, Object>> published = new ArrayList<>();
    private final AtomicReference<Depth> book = new AtomicReference<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        gateway = mock(MarketDataGateway.class);
        when(gateway.depth(anyLong(), anyInt())).thenAnswer(i -> book.get());

        StreamService stream = mock(StreamService.class);
        doAnswer(i -> {
            if ("depth".equals(i.getArgument(0))) {
                published.add((Map<String, Object>) i.getArgument(1));
            }
            return null;
        }).when(stream).publish(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());

        broadcaster = new DepthBroadcaster(gateway, stream);
        ReflectionTestUtils.setField(broadcaster, "watchTtlMs", 45_000L);
        book.set(depth("100.00", 500));
    }

    @Test
    @DisplayName("nothing is pushed for an instrument nobody is looking at")
    void doesNotPushUnwatchedBooks() {
        broadcaster.push();
        assertThat(published).isEmpty();
        assertThat(broadcaster.watchedCount()).isZero();
    }

    @Test
    @DisplayName("a book that has not moved is not sent again — the whole point of pushing")
    void doesNotResendAnUnchangedBook() {
        broadcaster.watch(7L, 10);

        broadcaster.push();
        assertThat(published).hasSize(1);          // first push: the client has never seen this book

        broadcaster.push();
        broadcaster.push();
        assertThat(published).as("an unmoved book must not be pushed twice").hasSize(1);

        book.set(depth("100.00", 900));            // the size at 100.00 changed
        broadcaster.push();
        assertThat(published).hasSize(2);
    }

    @Test
    @DisplayName("every push carries the next sequence number, so a client can see a gap")
    void sequenceNumbersIncreaseByOne() {
        broadcaster.watch(7L, 10);

        broadcaster.push();
        book.set(depth("100.00", 600));
        broadcaster.push();
        book.set(depth("100.50", 600));
        broadcaster.push();

        assertThat(published).hasSize(3);
        assertThat(published).extracting(m -> m.get("seq")).containsExactly(1L, 2L, 3L);
        assertThat(published).allSatisfy(m -> assertThat(m).containsKeys("securityId", "symbol", "ltp", "bids", "asks"));
    }

    @Test
    @DisplayName("watch hands back the book and the sequence it is current as of")
    void watchReturnsTheBookAndItsSequence() {
        Map<String, Object> first = broadcaster.watch(7L, 10);
        assertThat(first).containsEntry("seq", 0L);      // nothing pushed yet
        assertThat(first).containsEntry("symbol", "BRACBANK");

        broadcaster.push();
        book.set(depth("100.00", 700));
        broadcaster.push();

        // A client joining now starts from sequence 2 — not zero, which it would mistake for a gap.
        Map<String, Object> later = broadcaster.watch(7L, 10);
        assertThat(later).containsEntry("seq", 2L);
    }

    @Test
    @DisplayName("a snapshot is the book plus where it is in the stream — what a gap is recovered from")
    void snapshotCarriesTheSequence() {
        broadcaster.watch(7L, 10);
        broadcaster.push();

        Map<String, Object> snap = broadcaster.snapshot(7L, 10);
        assertThat(snap).containsEntry("snapshot", true);
        assertThat(snap).containsEntry("seq", broadcaster.sequenceOf(7L));
        assertThat(snap.get("bids")).isNotNull();
    }

    @Test
    @DisplayName("interest lapses: a tab nobody closed stops costing anything on its own")
    void watchExpires() {
        ReflectionTestUtils.setField(broadcaster, "watchTtlMs", -1L);   // already expired
        broadcaster.watch(7L, 10);
        assertThat(broadcaster.watchedCount()).isEqualTo(1);

        broadcaster.push();
        assertThat(broadcaster.watchedCount()).isZero();
        assertThat(published).isEmpty();
    }

    private static Depth depth(String bestBid, long qty) {
        return new Depth("BRACBANK", new BigDecimal("100.20"),
                List.of(new DepthLevel(new BigDecimal(bestBid), qty, 3)),
                List.of(new DepthLevel(new BigDecimal("100.50"), 400L, 2)));
    }
}
