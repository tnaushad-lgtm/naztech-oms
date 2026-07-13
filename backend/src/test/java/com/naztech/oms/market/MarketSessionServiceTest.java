package com.naztech.oms.market;

import com.naztech.oms.entity.Exchange;
import com.naztech.oms.entity.OmsOrder;
import com.naztech.oms.exchange.itch.ItchGateway;
import com.naztech.oms.repo.ExchangeRepo;
import com.naztech.oms.repo.OmsOrderRepo;
import com.naztech.oms.service.AuditService;
import com.naztech.oms.service.StreamService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The session is the outermost trading gate, so these tests are about what each phase permits — and
 * in particular about the two rules that are easy to get subtly wrong: PRE_OPEN accepts orders but
 * must not let them cross, and a close must not leave day orders working.
 */
class MarketSessionServiceTest {

    private ExchangeRepo exchangeRepo;
    private OmsOrderRepo orderRepo;
    private ItchGateway itch;
    private MarketSessionService session;
    private Exchange dse;

    @BeforeEach
    void setUp() {
        dse = new Exchange();
        dse.setId(1L);
        dse.setCode("DSE");
        dse.setStatus("CLOSED");
        dse.setOpenTime(LocalTime.of(10, 0));
        dse.setCloseTime(LocalTime.of(14, 30));

        exchangeRepo = mock(ExchangeRepo.class);
        when(exchangeRepo.findAll()).thenReturn(List.of(dse));
        when(exchangeRepo.save(any(Exchange.class))).thenAnswer(i -> i.getArgument(0));

        orderRepo = mock(OmsOrderRepo.class);
        when(orderRepo.findByExchangeIdAndStatusIn(any(), anyList())).thenReturn(new ArrayList<>());
        when(orderRepo.save(any(OmsOrder.class))).thenAnswer(i -> i.getArgument(0));

        itch = mock(ItchGateway.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ItchGateway> provider = mock(ObjectProvider.class);
        doAnswer(inv -> {
            java.util.function.Consumer<ItchGateway> c = inv.getArgument(0);
            c.accept(itch);
            return null;
        }).when(provider).ifAvailable(any());

        session = new MarketSessionService(exchangeRepo, orderRepo, provider,
                mock(StreamService.class), mock(AuditService.class));
        session.restore();
    }

    @Test
    @DisplayName("a market that has not been opened rejects orders — and says why")
    void closedMarketRejectsOrders() {
        assertThat(session.phase(1L)).isEqualTo(MarketSession.CLOSED);
        assertThat(session.blockReason(1L)).contains("closed").contains("10:00").contains("14:30");
        assertThat(session.allowsMatching(1L)).isFalse();
        assertThat(session.anyOpen()).isFalse();
    }

    @Test
    @DisplayName("PRE_OPEN accepts orders but does not let them cross — the book builds, it does not trade")
    void preOpenAcceptsOrdersButDoesNotMatch() {
        session.start("DSE", false, "admin");

        assertThat(session.phase(1L)).isEqualTo(MarketSession.PRE_OPEN);
        assertThat(session.blockReason(1L)).as("orders are accepted in pre-open").isNull();
        assertThat(session.allowsMatching(1L)).as("nothing crosses until the opening bell").isFalse();
        verify(itch).openMarket();                    // the day-start broadcast went out
    }

    @Test
    @DisplayName("the opening bell lets resting orders trade")
    void openingBellStartsMatching() {
        session.start("DSE", false, "admin");
        session.open("DSE", "admin");

        assertThat(session.phase(1L)).isEqualTo(MarketSession.OPEN);
        assertThat(session.allowsMatching(1L)).isTrue();
        assertThat(session.anyOpen()).isTrue();
    }

    @Test
    @DisplayName("a halt refuses new orders and freezes the feed, but the book stands")
    void haltRefusesOrdersAndFreezesTheFeed() {
        session.start("DSE", true, "admin");
        session.halt("DSE", "Circuit breaker", "rms");

        assertThat(session.phase(1L)).isEqualTo(MarketSession.HALTED);
        assertThat(session.blockReason(1L)).contains("halted").contains("cancels are still allowed");
        assertThat(session.allowsMatching(1L)).isFalse();
        verify(itch).haltFeed(true);
        verify(itch, never()).closeMarket();          // the books are NOT torn down by a halt

        session.resume("DSE", "rms");
        assertThat(session.phase(1L)).isEqualTo(MarketSession.OPEN);
        verify(itch).haltFeed(false);
    }

    @Test
    @DisplayName("the close expires unfilled DAY orders but leaves GTC orders working")
    void closeExpiresDayOrdersOnly() {
        OmsOrder day = order(1L, "DAY", "OPEN");
        OmsOrder partialDay = order(2L, "DAY", "PARTIAL");
        OmsOrder gtc = order(3L, "GTC", "OPEN");
        when(orderRepo.findByExchangeIdAndStatusIn(any(), anyList()))
                .thenReturn(new ArrayList<>(List.of(day, partialDay, gtc)));

        session.start("DSE", true, "admin");
        session.close("DSE", "admin");

        assertThat(session.phase(1L)).isEqualTo(MarketSession.CLOSED);
        assertThat(day.getStatus()).isEqualTo("EXPIRED");
        assertThat(partialDay.getStatus()).isEqualTo("EXPIRED");
        assertThat(gtc.getStatus())
                .as("a good-till-cancelled order is not cancelled by the closing bell")
                .isEqualTo("OPEN");
        verify(itch).closeMarket();
    }

    @Test
    @DisplayName("the phase survives a restart — it is read back from the exchange row")
    void restoresThePersistedPhase() {
        session.start("DSE", true, "admin");
        assertThat(dse.getStatus()).isEqualTo("OPEN");     // written through to the DB

        MarketSessionService restarted = new MarketSessionService(exchangeRepo, orderRepo,
                providerFor(itch), mock(StreamService.class), mock(AuditService.class));
        restarted.restore();

        assertThat(restarted.phase(1L)).isEqualTo(MarketSession.OPEN);
    }

    @Test
    @DisplayName("an unrecognised status means CLOSED — it must never mean 'trade freely'")
    void unknownStatusFailsSafe() {
        dse.setStatus("BANANA");
        MarketSessionService s = new MarketSessionService(exchangeRepo, orderRepo, providerFor(itch),
                mock(StreamService.class), mock(AuditService.class));
        s.restore();

        assertThat(s.phase(1L)).isEqualTo(MarketSession.CLOSED);
        assertThat(s.blockReason(1L)).isNotNull();
        assertThat(s.phase(999L)).as("an unknown exchange is closed, not open").isEqualTo(MarketSession.CLOSED);
    }

    private static OmsOrder order(Long id, String validity, String status) {
        OmsOrder o = new OmsOrder();
        o.setId(id);
        o.setValidity(validity);
        o.setStatus(status);
        return o;
    }

    private ObjectProvider<ItchGateway> providerFor(ItchGateway gateway) {
        @SuppressWarnings("unchecked")
        ObjectProvider<ItchGateway> p = mock(ObjectProvider.class);
        doAnswer(inv -> {
            java.util.function.Consumer<ItchGateway> c = inv.getArgument(0);
            c.accept(gateway);
            return null;
        }).when(p).ifAvailable(any());
        return p;
    }
}
