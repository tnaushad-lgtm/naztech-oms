package com.naztech.oms.perf;

import com.naztech.oms.api.Dtos.OrderRequest;
import com.naztech.oms.api.Dtos.OrderView;
import com.naztech.oms.api.Dtos.RiskResult;
import com.naztech.oms.entity.AppUser;
import com.naztech.oms.entity.ClientAccount;
import com.naztech.oms.entity.MarketData;
import com.naztech.oms.entity.Security;
import com.naztech.oms.repo.AppUserRepo;
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
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The harness's whole value is that its numbers are trustworthy, so these tests check the parts
 * that could quietly lie: the pacing (does it really place N per second?), the accepted/rejected
 * split, the strategy's effect on price (a "resting" order that crosses would fill, drain buying
 * power, and turn the run into a rejection storm), and — since the minutes ask for a randomised
 * generator — that "random" really does spread the flow instead of hammering one row.
 */
class LoadTestServiceTest {

    private OrderService orders;
    private LoadTestService loadTest;
    private ExecutionStats execStats;
    private final List<OrderRequest> placed = new CopyOnWriteArrayList<>();
    private final List<Boolean> bypassFlags = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        orders = mock(OrderService.class);
        execStats = new ExecutionStats();

        // Twelve instruments, so a randomised run has somewhere to spread to.
        List<Security> securities = IntStream.rangeClosed(1, 12).mapToObj(i -> {
            Security s = new Security();
            s.setId((long) i);
            s.setSymbol("SEC" + i);
            s.setStatus("ACTIVE");
            s.setAssetClass("EQUITY");
            s.setLotSize(10);
            return s;
        }).collect(Collectors.toList());
        Security seven = securities.get(6);            // id 7
        seven.setSymbol("BRACBANK");

        SecurityRepo securityRepo = mock(SecurityRepo.class);
        when(securityRepo.findById(7L)).thenReturn(Optional.of(seven));
        when(securityRepo.findAll()).thenReturn(securities);

        List<ClientAccount> accounts = IntStream.rangeClosed(1, 8).mapToObj(i -> {
            ClientAccount a = new ClientAccount();
            a.setId((long) i);
            a.setStatus("ACTIVE");
            a.setBuyingPower(new BigDecimal("50000000.00"));
            return a;
        }).collect(Collectors.toList());
        ClientAccountRepo accountRepo = mock(ClientAccountRepo.class);
        when(accountRepo.findById(3L)).thenReturn(Optional.of(accounts.get(2)));
        when(accountRepo.findAll()).thenReturn(accounts);
        when(accountRepo.findAllById(any())).thenReturn(accounts);
        when(accountRepo.save(any(ClientAccount.class))).thenAnswer(i -> i.getArgument(0));
        when(accountRepo.saveAll(any())).thenAnswer(i -> i.getArgument(0));

        List<MarketData> marks = securities.stream().map(s -> {
            MarketData md = new MarketData();
            md.setSecurityId(s.getId());
            md.setLtp(new BigDecimal("47.80"));
            return md;
        }).collect(Collectors.toList());
        MarketDataRepo marketRepo = mock(MarketDataRepo.class);
        when(marketRepo.findAll()).thenReturn(marks);

        List<AppUser> dealers = IntStream.rangeClosed(4, 9).mapToObj(i -> {
            AppUser u = new AppUser();
            u.setId((long) i);
            u.setRole("DEALER");
            u.setStatus("ACTIVE");
            return u;
        }).collect(Collectors.toList());
        AppUserRepo userRepo = mock(AppUserRepo.class);
        when(userRepo.findAll()).thenReturn(dealers);

        loadTest = new LoadTestService(orders, securityRepo, accountRepo, marketRepo, userRepo,
                new OrderPhaseTimings(), execStats);
        ReflectionTestUtils.setField(loadTest, "exchangeMode", "simulator");
        ReflectionTestUtils.setField(loadTest, "dbPoolSize", 10);
        ReflectionTestUtils.setField(loadTest, "venueControlUrl", "");   // no venue to configure in a unit test
    }

    /** Accepts every order and remembers what it was asked to place. */
    private void acceptEverything() {
        when(orders.place(any(OrderRequest.class), anyString(), anyBoolean())).thenAnswer(inv -> {
            OrderRequest req = inv.getArgument(0);
            placed.add(req);
            bypassFlags.add(inv.getArgument(2));
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
        assertThat(st.generated()).isEqualTo(100);
        assertThat(st.accepted()).isEqualTo(100);
        assertThat(st.rejected()).isZero();
        assertThat(st.errors()).isZero();
        assertThat(st.queueDepth()).isZero();                   // nothing left in flight once it is done
        // Paced, not sprinted: 100 orders at 50/sec takes ~2s, so the rate lands near target.
        assertThat(st.achievedPerSec()).isBetween(35.0, 65.0);
        assertThat(st.elapsedSec()).isBetween(1.5, 3.5);
        assertThat(st.phase()).isEqualTo("DONE");
    }

    @Test
    @DisplayName("counts risk rejections separately and breaks them down by reason")
    void separatesRiskRejectionsFromAcceptances() {
        AtomicInteger n = new AtomicInteger();
        when(orders.place(any(OrderRequest.class), anyString(), anyBoolean())).thenAnswer(inv -> {
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
        when(orders.place(any(OrderRequest.class), anyString(), anyBoolean()))
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
    @DisplayName("randomising spreads the flow across instruments, clients, dealers and sizes")
    void randomisedGeneratorSpreadsTheFlow() {
        acceptEverything();

        LoadTest.Request r = new LoadTest.Request(
                200, 1, null, null, null, 100, "BUY", "RESTING", true, 8,
                false, true, true, true, true, 100, 5000, false, true, 5, false,
                0, 0, 50);
        loadTest.start(r);
        await().atMost(20, TimeUnit.SECONDS).until(() -> !loadTest.status().running());

        assertThat(placed).hasSizeGreaterThan(100);
        // Not one stock, one client, one dealer, one size — which is what the old harness measured.
        assertThat(placed.stream().map(OrderRequest::securityId).distinct()).hasSizeGreaterThan(1);
        assertThat(placed.stream().map(OrderRequest::accountId).distinct()).hasSizeGreaterThan(1);
        assertThat(placed.stream().map(OrderRequest::dealerId).distinct()).hasSizeGreaterThan(1);
        assertThat(placed.stream().map(OrderRequest::quantity).distinct()).hasSizeGreaterThan(1);
        assertThat(placed.stream().map(OrderRequest::price).distinct()).hasSizeGreaterThan(1);

        // Every quantity is a whole lot (lot size 10) and inside the requested band — an off-lot
        // quantity would just be rejected by the lot check, and measure the reject path instead.
        assertThat(placed).allSatisfy(o -> {
            assertThat(o.quantity() % 10).isZero();
            assertThat(o.quantity()).isBetween(100L, 5000L);
        });
    }

    @Test
    @DisplayName("bypass mode asks OrderService to skip risk; a normal run does not")
    void bypassModePassesTheFlagThrough() {
        acceptEverything();

        loadTest.start(req(20, 1, "RESTING"));
        await().atMost(15, TimeUnit.SECONDS).until(() -> !loadTest.status().running());
        assertThat(bypassFlags).isNotEmpty().containsOnly(false);

        bypassFlags.clear();
        LoadTest.Request bypass = new LoadTest.Request(
                20, 1, 3L, 7L, 4L, 100, "BUY", "RESTING", true, 4,
                false, false, false, false, false, 0, 0, false, false, 0, true,
                0, 0, 50);
        loadTest.start(bypass);
        await().atMost(15, TimeUnit.SECONDS).until(() -> !loadTest.status().running());
        assertThat(bypassFlags).isNotEmpty().containsOnly(true);
    }

    @Test
    @DisplayName("max load drops the pacer: it beats the target rate rather than obeying it")
    void maxLoadIgnoresTheTarget() {
        acceptEverything();

        LoadTest.Request r = new LoadTest.Request(
                5, 1, 3L, 7L, 4L, 100, "BUY", "RESTING", true, 4,
                true, false, false, false, false, 0, 0, false, false, 0, false,
                0, 0, 50);
        loadTest.start(r);
        await().atMost(20, TimeUnit.SECONDS).until(() -> !loadTest.status().running());

        LoadTest.Status st = loadTest.status();
        // The target said 5/sec. Unpaced, a mocked OrderService goes far faster — which is the point:
        // this reports the ceiling, not the request.
        assertThat(st.submitted()).isGreaterThan(50);
        assertThat(st.achievedPerSec()).isGreaterThan(50);
        assertThat(st.elapsedSec()).isLessThan(4.0);           // still bounded by the clock
    }

    @Test
    @DisplayName("reports the executions that came back, not just the orders that went out")
    void reportsExecutionsAndResources() {
        acceptEverything();
        // Stand in for the venue: two partials and a fill land while the run is in flight.
        execStats.execution("PARTIAL");
        execStats.execution("PARTIAL");
        execStats.execution("FILLED");
        execStats.execution("REJECTED");

        loadTest.start(req(10, 1, "CROSSING"));
        await().atMost(15, TimeUnit.SECONDS).until(() -> !loadTest.status().running());

        LoadTest.Status st = loadTest.status();
        // start() resets the counters, so only what arrives during the run is counted. Nothing does
        // here (OrderService is a mock), and that is the honest answer.
        assertThat(st.executions()).isZero();

        execStats.execution("PARTIAL");
        execStats.execution("FILLED");
        LoadTest.Status after = loadTest.status();
        assertThat(after.executions()).isEqualTo(2);
        assertThat(after.partialFills()).isEqualTo(1);
        assertThat(after.fullFills()).isEqualTo(1);

        assertThat(after.resources()).containsKeys("cpuPct", "heapUsedMb", "heapMaxMb", "threads", "cores");
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
        return new LoadTest.Request(perSec, seconds, 3L, 7L, 4L, 100, "BUY", strategy, true, 4,
                false, false, false, false, false, 0, 0, false, false, 0, false,
                0, 0, 50);
    }

    private static OrderView view(OrderRequest r) {
        return new OrderView(1L, "ORD-1", "BRACBANK", "BRAC Bank PLC", r.side(),
                r.orderType(), "NORMAL", "DAY", r.price(), r.quantity(), 0L, null, "OPEN",
                null, BigDecimal.ZERO, null, null, "PRICE");
    }
}
