package com.naztech.oms.perf;

import com.naztech.oms.api.Dtos.OrderRequest;
import com.naztech.oms.api.Dtos.OrderView;
import com.naztech.oms.api.Dtos.RiskResult;
import com.naztech.oms.entity.ClientAccount;
import com.naztech.oms.entity.MarketData;
import com.naztech.oms.entity.Security;
import com.naztech.oms.repo.ClientAccountRepo;
import com.naztech.oms.repo.MarketDataRepo;
import com.naztech.oms.repo.SecurityRepo;
import com.naztech.oms.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The harness's whole value is that its numbers are trustworthy, so these tests check the parts
 * that could quietly lie: the pacing (does it really place N per second?), the accepted/rejected
 * split, and the strategy's effect on price (a "resting" order that crosses would fill, drain
 * buying power, and turn the run into a rejection storm).
 */
class LoadTestServiceTest {

    private OrderService orders;
    private LoadTestService loadTest;
    private final List<OrderRequest> placed = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        orders = mock(OrderService.class);

        Security sec = new Security();
        sec.setId(7L);
        sec.setSymbol("BRACBANK");
        SecurityRepo securityRepo = mock(SecurityRepo.class);
        when(securityRepo.findById(7L)).thenReturn(Optional.of(sec));

        ClientAccount acc = new ClientAccount();
        acc.setId(3L);
        acc.setBuyingPower(new BigDecimal("50000000.00"));
        ClientAccountRepo accountRepo = mock(ClientAccountRepo.class);
        when(accountRepo.findById(3L)).thenReturn(Optional.of(acc));
        when(accountRepo.save(any(ClientAccount.class))).thenAnswer(i -> i.getArgument(0));

        MarketData md = new MarketData();
        md.setSecurityId(7L);
        md.setLtp(new BigDecimal("47.80"));
        MarketDataRepo marketRepo = mock(MarketDataRepo.class);
        when(marketRepo.findById(7L)).thenReturn(Optional.of(md));

        loadTest = new LoadTestService(orders, securityRepo, accountRepo, marketRepo);
        ReflectionTestUtils.setField(loadTest, "exchangeMode", "simulator");
        ReflectionTestUtils.setField(loadTest, "dbPoolSize", 10);
    }

    /** Accepts every order and remembers what it was asked to place. */
    private void acceptEverything() {
        when(orders.place(any(OrderRequest.class), anyString())).thenAnswer(inv -> {
            OrderRequest req = inv.getArgument(0);
            placed.add(req);
            return new OrderService.PlaceResult(view(req), new RiskResult(true, "OK", BigDecimal.ZERO, List.of()));
        });
    }

    @Test
    @DisplayName("paces to the requested rate and reports what it achieved")
    void pacesToTheRequestedRate() {
        acceptEverything();

        loadTest.start(req(50, 2, "RESTING"));
        await().atMost(15, TimeUnit.SECONDS).until(() -> !loadTest.status().running());

        LoadTest.Status st = loadTest.status();
        assertThat(st.submitted()).isEqualTo(100);              // 50/sec x 2s
        assertThat(st.accepted()).isEqualTo(100);
        assertThat(st.rejected()).isZero();
        assertThat(st.errors()).isZero();
        // Paced, not sprinted: 100 orders at 50/sec takes ~2s, so the rate lands near target.
        assertThat(st.achievedPerSec()).isBetween(35.0, 65.0);
        assertThat(st.elapsedSec()).isBetween(1.5, 3.5);
        assertThat(st.phase()).isEqualTo("DONE");
    }

    @Test
    @DisplayName("counts risk rejections separately and breaks them down by reason")
    void separatesRiskRejectionsFromAcceptances() {
        AtomicInteger n = new AtomicInteger();
        when(orders.place(any(OrderRequest.class), anyString())).thenAnswer(inv -> {
            OrderRequest req = inv.getArgument(0);
            boolean pass = n.getAndIncrement() % 2 == 0;        // every other order is rejected
            return new OrderService.PlaceResult(view(req),
                    new RiskResult(pass, pass ? "OK" : "Insufficient buying power", BigDecimal.TEN, List.of()));
        });

        loadTest.start(req(40, 1, "CROSSING"));
        await().atMost(15, TimeUnit.SECONDS).until(() -> !loadTest.status().running());

        LoadTest.Status st = loadTest.status();
        assertThat(st.submitted()).isEqualTo(40);
        assertThat(st.accepted()).isEqualTo(20);
        assertThat(st.rejected()).isEqualTo(20);
        assertThat(st.rejectReasons()).containsEntry("Insufficient buying power", 20L);
    }

    @Test
    @DisplayName("an exception is an error, not a risk rejection — and never stops the run")
    void countsExceptionsAsErrors() {
        when(orders.place(any(OrderRequest.class), anyString()))
                .thenThrow(new IllegalStateException("FIX session unavailable"));

        loadTest.start(req(20, 1, "RESTING"));
        await().atMost(15, TimeUnit.SECONDS).until(() -> !loadTest.status().running());

        LoadTest.Status st = loadTest.status();
        assertThat(st.submitted()).isEqualTo(20);
        assertThat(st.errors()).isEqualTo(20);
        assertThat(st.rejected()).isZero();
        assertThat(st.errorSamples()).first().asString().contains("FIX session unavailable");
    }

    @Test
    @DisplayName("RESTING prices away from the market so orders never fill; CROSSING prices through it")
    void pricesByStrategy() {
        acceptEverything();

        loadTest.start(req(10, 1, "RESTING"));
        await().atMost(15, TimeUnit.SECONDS).until(() -> !loadTest.status().running());
        // BUY well below the 47.80 LTP: rests on the book, never fills, buying power untouched.
        assertThat(placed).isNotEmpty();
        assertThat(placed.get(0).price()).isEqualByComparingTo("23.90");
        assertThat(placed.get(0).orderType()).isEqualTo("LIMIT");

        placed.clear();
        loadTest.start(req(10, 1, "CROSSING"));
        await().atMost(15, TimeUnit.SECONDS).until(() -> !loadTest.status().running());
        // BUY above the LTP: marketable, so it fills and exercises the trade/portfolio path.
        assertThat(placed.get(0).price()).isEqualByComparingTo("50.19");

        placed.clear();
        loadTest.start(req(10, 1, "MARKET"));
        await().atMost(15, TimeUnit.SECONDS).until(() -> !loadTest.status().running());
        assertThat(placed.get(0).orderType()).isEqualTo("MARKET");
        assertThat(placed.get(0).price()).isNull();             // market orders carry no price
    }

    @Test
    @DisplayName("refuses to start a second run on top of a live one")
    void refusesConcurrentRuns() {
        acceptEverything();
        loadTest.start(req(10, 3, "RESTING"));

        assertThat(loadTest.status().running()).isTrue();
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> loadTest.start(req(10, 1, "RESTING")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already running");

        loadTest.stop();
    }

    private static LoadTest.Request req(int perSec, int seconds, String strategy) {
        return new LoadTest.Request(perSec, seconds, 3L, 7L, 4L, 100, "BUY", strategy, true, 4);
    }

    private static OrderView view(OrderRequest r) {
        return new OrderView(1L, "ORD-1", "BRACBANK", "BRAC Bank PLC", r.side(),
                r.orderType(), "NORMAL", "DAY", r.price(), r.quantity(), 0L, null, "OPEN",
                null, BigDecimal.ZERO, null, null, "PRICE");
    }
}
