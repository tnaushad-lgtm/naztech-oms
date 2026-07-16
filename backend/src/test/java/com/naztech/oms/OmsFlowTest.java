package com.naztech.oms;

import com.naztech.oms.api.Dtos.AiHit;
import com.naztech.oms.api.Dtos.LoginResponse;
import com.naztech.oms.api.Dtos.OrderRequest;
import com.naztech.oms.entity.*;
import com.naztech.oms.repo.*;
import com.naztech.oms.api.Dtos.PortfolioView;
import com.naztech.oms.api.Dtos.EquityPoint;
import com.naztech.oms.service.AuthService;
import com.naztech.oms.service.EquityService;
import com.naztech.oms.service.OrderService;
import com.naztech.oms.service.PortfolioService;
import com.naztech.oms.service.SecuritySearchService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * End-to-end backend verification against the real dse_oms database:
 * auth, market data, order → risk → matching → portfolio, risk reject, and AI search.
 * (Per env constraints we assert via MockMvc / services, never a live HTTP port.)
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "fix.enabled=false")   // tests never dial a real FIX endpoint
class OmsFlowTest {

    @Autowired MockMvc mvc;
    @Autowired AuthService authService;
    @Autowired OrderService orderService;
    @Autowired SecuritySearchService search;
    @Autowired ExchangeRepo exchangeRepo;
    @Autowired SecurityRepo securityRepo;
    @Autowired ClientAccountRepo accountRepo;
    @Autowired AppUserRepo userRepo;
    @Autowired HoldingRepo holdingRepo;
    @Autowired TradeRepo tradeRepo;
    @Autowired BrokerRepo brokerRepo;
    @Autowired PortfolioService portfolioService;
    @Autowired EquityService equityService;
    @Autowired com.naztech.oms.service.AiAdvisorService advisorService;
    @Autowired com.naztech.oms.market.MarketSessionService marketSession;

    /**
     * Open the market before every test. Orders are only accepted while the session is open, and the
     * session is persisted in the exchange row — so without this the suite would pass or fail
     * depending on whether someone had left the market open in the database, which is no way to run
     * a test.
     */
    @org.junit.jupiter.api.BeforeEach
    void openTheMarket() {
        marketSession.start("DSE", true, "test");
    }

    private ClientAccount bracBankSeller() { return client1(); } // holds BRACBANK 10000 @45 (seed)

    private Long dseId() { return exchangeRepo.findByCode("DSE").orElseThrow().getId(); }
    private Security gp() { return securityRepo.findBySymbolAndExchangeId("GP", dseId()).orElseThrow(); }
    private ClientAccount client1() { return accountRepo.findByBoId("1201010000001").orElseThrow(); }
    private Long dealerId() { return userRepo.findByUsername("dealer1").orElseThrow().getId(); }

    @Test
    void login_succeeds_with_seeded_credentials() {
        LoginResponse r = authService.login("dealer1", "demo123");
        assertThat(r.token()).isNotBlank();
        assertThat(r.role()).isEqualTo("DEALER");
        // The dealer trades for Dragon Security, the DSE-enlisted brokerage. Naztech is the vendor
        // that builds this OMS — it holds no TREC and appears on no broker row.
        assertThat(r.brokerName()).contains("Dragon Security");
    }

    @Test
    void market_watch_endpoint_returns_dse_rows() throws Exception {
        mvc.perform(get("/api/market/watch").param("exchange", "DSE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.symbol=='GP')]").exists());
    }

    @Test
    void buy_market_order_fills_and_updates_portfolio() {
        Security s = gp();
        ClientAccount acc = client1();
        long held0 = holdingRepo.findByAccountIdAndSecurityId(acc.getId(), s.getId())
                .map(Holding::getQuantity).orElse(0L);
        long trades0 = tradeRepo.count();

        OrderRequest req = new OrderRequest(acc.getId(), s.getId(), "BUY", "MARKET",
                "NORMAL", "DAY", null, null, null, 100L, dealerId());
        OrderService.PlaceResult res = orderService.place(req, "dealer1");

        assertThat(res.risk().pass()).isTrue();
        assertThat(res.order().status()).isEqualTo("FILLED");
        assertThat(tradeRepo.count()).isGreaterThan(trades0);

        long held1 = holdingRepo.findByAccountIdAndSecurityId(acc.getId(), s.getId())
                .map(Holding::getQuantity).orElse(0L);
        assertThat(held1).isEqualTo(held0 + 100);
    }

    @Test
    void oversized_order_is_rejected_by_risk() {
        Security s = gp();
        ClientAccount acc = client1();
        // 10,000,000 shares grossly exceeds the client order-qty / value limits
        OrderRequest req = new OrderRequest(acc.getId(), s.getId(), "BUY", "LIMIT",
                "NORMAL", "DAY", null, java.math.BigDecimal.valueOf(305), null, 10_000_000L, dealerId());
        OrderService.PlaceResult res = orderService.place(req, "dealer1");

        assertThat(res.risk().pass()).isFalse();
        assertThat(res.order().status()).isEqualTo("REJECTED");
        assertThat(res.order().rejectReason()).containsIgnoringCase("limit");
    }

    @Test
    void semantic_search_is_typo_tolerant() {
        search.buildIndex(); // deterministic (don't rely on async warmup timing)
        List<AiHit> hits = search.search("squre pharmaceutical company", 25, 5);
        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).symbol()).isEqualTo("SQURPHARMA");
    }

    @Test
    void portfolio_view_has_pnl_and_allocations() {
        PortfolioView p = portfolioService.portfolio(client1().getId());
        assertThat(p).isNotNull();
        assertThat(p.positions()).isNotEmpty();
        assertThat(p.totalValue().doubleValue()).isGreaterThan(0);
        assertThat(p.bySector()).isNotEmpty();      // allocation donut data
        assertThat(p.byAsset()).isNotEmpty();
        // weights of held positions should sum to ~100%
        double w = p.positions().stream().mapToDouble(x -> x.weightPct().doubleValue()).sum();
        assertThat(w).isBetween(95.0, 105.0);
    }

    @Test
    void amend_updates_working_order() {
        Security s = gp();
        ClientAccount acc = client1();
        // a buy far below market rests on the book (does not cross)
        OrderRequest req = new OrderRequest(acc.getId(), s.getId(), "BUY", "LIMIT",
                "NORMAL", "DAY", null, java.math.BigDecimal.valueOf(50), null, 100L, dealerId());
        OrderService.PlaceResult placed = orderService.place(req, "dealer1");
        assertThat(placed.order().status()).isIn("OPEN", "PARTIAL");

        OrderService.PlaceResult amended = orderService.modify(
                placed.order().id(), java.math.BigDecimal.valueOf(60), 200L, "dealer1");
        assertThat(amended.order().quantity()).isEqualTo(200L);
        assertThat(amended.order().price()).isEqualByComparingTo(java.math.BigDecimal.valueOf(60));
        assertThat(amended.order().status()).isIn("OPEN", "PARTIAL");
        orderService.cancel(amended.order().id(), "dealer1"); // tidy up
    }

    @Test
    void killswitch_blocks_orders() {
        Broker b = brokerRepo.findById(client1().getBrokerId()).orElseThrow();
        b.setStatus("SUSPENDED");
        brokerRepo.save(b);
        try {
            OrderRequest req = new OrderRequest(client1().getId(), gp().getId(), "BUY", "MARKET",
                    "NORMAL", "DAY", null, null, null, 100L, dealerId());
            OrderService.PlaceResult res = orderService.place(req, "dealer1");
            assertThat(res.risk().pass()).isFalse();
            assertThat(res.order().rejectReason()).containsIgnoringCase("halt");
        } finally {
            b.setStatus("ACTIVE");
            brokerRepo.save(b);
        }
    }

    @Test
    void suspended_client_account_cannot_trade() {
        ClientAccount acc = client1();
        acc.setStatus("SUSPENDED");
        accountRepo.save(acc);
        try {
            OrderRequest req = new OrderRequest(acc.getId(), gp().getId(), "BUY", "MARKET",
                    "NORMAL", "DAY", null, null, null, 100L, dealerId());
            OrderService.PlaceResult res = orderService.place(req, "dealer1");

            // Until this check existed, the status column was decoration: a suspended client's orders
            // were validated, routed and filled exactly like an active one's.
            assertThat(res.risk().pass()).isFalse();
            assertThat(res.order().status()).isEqualTo("REJECTED");
            assertThat(res.order().rejectReason()).containsIgnoringCase("SUSPENDED");
        } finally {
            acc.setStatus("ACTIVE");
            accountRepo.save(acc);
        }
    }

    @Test
    void block_market_order_below_the_floor_is_rejected() {
        // 100 shares of GP is not a block trade in any market on earth; DSE's floor is Tk 5 lakh.
        OrderRequest small = new OrderRequest(client1().getId(), gp().getId(), "BUY", "LIMIT",
                "BLOCK", "DAY", null, java.math.BigDecimal.valueOf(300), null, 100L, dealerId());
        OrderService.PlaceResult res = orderService.place(small, "dealer1");
        assertThat(res.risk().pass()).isFalse();
        assertThat(res.order().rejectReason()).containsIgnoringCase("block-market");

        // 5,000 at 300 = 1,500,000: over the floor, so the block market is the right place for it.
        OrderRequest real = new OrderRequest(client1().getId(), gp().getId(), "BUY", "LIMIT",
                "BLOCK", "DAY", null, java.math.BigDecimal.valueOf(300), null, 5_000L, dealerId());
        OrderService.PlaceResult ok = orderService.place(real, "dealer1");
        assertThat(ok.risk().pass()).isTrue();
        orderService.cancel(ok.order().id(), "dealer1");
    }

    @Test
    void an_unknown_trade_window_is_rejected() {
        OrderRequest req = new OrderRequest(client1().getId(), gp().getId(), "BUY", "LIMIT",
                "AFTER_HOURS", "DAY", null, java.math.BigDecimal.valueOf(300), null, 100L, dealerId());
        OrderService.PlaceResult res = orderService.place(req, "dealer1");
        assertThat(res.risk().pass()).isFalse();
        assertThat(res.order().rejectReason()).containsIgnoringCase("Unknown trade window");
    }

    @Test
    void bond_order_by_yield_fills_and_stamps_settlement_economics() {
        // TB10Y2034: 8.5% semi-annual, reference ~99.82. An order entered BY YIELD near the coupon
        // rate converts to a clean price near par (DSE BRS §1.1: submitted with a price, not a yield),
        // and once it trades, the TRADE carries accrued interest and the implied yield — because
        // settlement price = clean + accrued is the number that actually changes hands (§1.1.2).
        Security bond = securityRepo.findBySymbolAndExchangeId("TB10Y2034", dseId()).orElseThrow();

        // A yield-basis BUY: converted to clean price at entry, rests on the book.
        OrderRequest buy = new OrderRequest(client1().getId(), bond.getId(), "BUY", "LIMIT",
                "NORMAL", "DAY", null, null, null, 100L, dealerId(),
                "YIELD", java.math.BigDecimal.valueOf(8.55));
        OrderService.PlaceResult placed = orderService.place(buy, "dealer1");

        assertThat(placed.risk().pass()).isTrue();
        assertThat(placed.order().priceBasis()).isEqualTo("YIELD");
        assertThat(placed.order().orderYield()).isNotNull();
        // yield ≈ coupon ⇒ clean ≈ par — and inside the ±10% band of the 99.82 reference
        assertThat(placed.order().price().doubleValue()).isBetween(90.0, 110.0);

        // Give a second client bonds to sell, and cross the book deterministically: a SELL priced
        // through the resting bid must trade at the passive (bid) price.
        ClientAccount seller = accountRepo.findByBoId("1201010000002").orElseThrow();
        portfolioService.applyFill(seller.getId(), bond.getId(), "BUY", 200L, java.math.BigDecimal.valueOf(99.50));
        long trades0 = tradeRepo.count();
        OrderRequest sell = new OrderRequest(seller.getId(), bond.getId(), "SELL", "LIMIT",
                "NORMAL", "DAY", null, java.math.BigDecimal.valueOf(95.00), null, 100L, dealerId());
        OrderService.PlaceResult crossed = orderService.place(sell, "dealer1");

        assertThat(crossed.risk().pass()).isTrue();
        assertThat(tradeRepo.count()).isGreaterThan(trades0);
        var trade = tradeRepo.findAll().stream()
                .filter(t -> bond.getId().equals(t.getSecurityId()))
                .reduce((a, b) -> b).orElseThrow();
        assertThat(trade.getAccruedInterest()).as("accrued interest persisted on the bond trade").isNotNull();
        assertThat(trade.getAccruedInterest().signum()).isGreaterThanOrEqualTo(0);
        assertThat(trade.getTradeYield()).as("implied yield persisted on the bond trade").isNotNull();
    }

    @Test
    void bond_price_outside_the_band_is_rejected() {
        // BRS §1.1.6 — Reference Price Limits on the clean price. 60.00 against a ~99.82 reference is
        // 40% away; the circuit breaker must stop it here, not let the venue discover it.
        Security bond = securityRepo.findBySymbolAndExchangeId("TB10Y2034", dseId()).orElseThrow();
        OrderRequest req = new OrderRequest(client1().getId(), bond.getId(), "BUY", "LIMIT",
                "NORMAL", "DAY", null, java.math.BigDecimal.valueOf(60.00), null, 100L, dealerId());
        OrderService.PlaceResult res = orderService.place(req, "dealer1");

        assertThat(res.risk().pass()).isFalse();
        assertThat(res.order().rejectReason()).containsIgnoringCase("price band");
    }

    @Test
    void closed_market_blocks_orders() {
        marketSession.close("DSE", "test");
        try {
            OrderRequest req = new OrderRequest(client1().getId(), gp().getId(), "BUY", "MARKET",
                    "NORMAL", "DAY", null, null, null, 100L, dealerId());
            OrderService.PlaceResult res = orderService.place(req, "dealer1");

            assertThat(res.risk().pass()).isFalse();
            assertThat(res.order().status()).isEqualTo("REJECTED");
            assertThat(res.order().rejectReason()).containsIgnoringCase("market is closed");
        } finally {
            marketSession.start("DSE", true, "test");
        }
    }

    @Test
    void pre_open_accepts_the_order_but_does_not_trade_it() {
        marketSession.close("DSE", "test");
        marketSession.start("DSE", false, "test");     // PRE_OPEN: the book builds, nothing crosses
        try {
            // A market order would cross instantly in continuous trading; in pre-open it must not.
            OrderRequest req = new OrderRequest(client1().getId(), gp().getId(), "BUY", "MARKET",
                    "NORMAL", "DAY", null, null, null, 100L, dealerId());
            OrderService.PlaceResult res = orderService.place(req, "dealer1");

            assertThat(res.risk().pass()).as("pre-open accepts orders").isTrue();
            assertThat(res.order().status())
                    .as("nothing trades before the opening bell")
                    .isNotEqualTo("FILLED");
            assertThat(res.order().filledQty()).isZero();
            orderService.cancel(res.order().id(), "test");   // tidy up
        } finally {
            marketSession.start("DSE", true, "test");
        }
    }

    @Test
    void equity_curve_backfills_and_returns_series() {
        List<EquityPoint> series = equityService.series(client1().getId(), 200);
        assertThat(series).isNotEmpty();
        assertThat(series.size()).isGreaterThanOrEqualTo(5);
        assertThat(series.get(series.size() - 1).totalValue()).isGreaterThan(0);
    }

    @Test
    void advisor_answers_questions_from_oms_data() {
        // Engine-agnostic: passes whether Gemini is configured or the offline fallback answers.
        Long acc = client1().getId();
        for (String q : new String[]{"Is GP in a good position today?", "am I in profit or loss?",
                "show me Z category companies", "which sector is doing well?"}) {
            var r = advisorService.advise(q, acc, null, null, null, null, null);
            assertThat(r.answer()).isNotBlank();
            assertThat(r.answer()).doesNotStartWith("Error");
        }
        assertThat(advisorService.advise("", acc, "DSE_STATUS", null, null, null, "bn").answer()).isNotBlank();
    }

    @Test
    void investor_login_scopes_to_own_account() {
        LoginResponse r = authService.login("investor3", "demo123");
        assertThat(r.role()).isEqualTo("CLIENT");
        assertThat(r.defaultAccountId()).isNotNull();
    }

    @Test
    void sell_books_realized_pnl() {
        Security brac = securityRepo.findBySymbolAndExchangeId("BRACBANK", dseId()).orElseThrow();
        ClientAccount acc = bracBankSeller();
        long held0 = holdingRepo.findByAccountIdAndSecurityId(acc.getId(), brac.getId())
                .map(Holding::getQuantity).orElse(0L);
        org.junit.jupiter.api.Assumptions.assumeTrue(held0 >= 100, "needs BRACBANK holding to sell");
        // A working (OPEN/PARTIAL) BRACBANK order on this account — e.g. left resting by a live demo —
        // would (correctly) trip the wash-trade guard on the opposite-side sell, so skip if one exists.
        boolean workingOpp = orderService.blotterByAccount(acc.getId()).stream()
                .anyMatch(v -> "BRACBANK".equals(v.symbol()) && ("OPEN".equals(v.status()) || "PARTIAL".equals(v.status())));
        org.junit.jupiter.api.Assumptions.assumeTrue(!workingOpp, "skip: a working BRACBANK order would trip the wash-trade guard");
        java.math.BigDecimal realized0 = accountRepo.findById(acc.getId()).orElseThrow().getRealizedPnl();

        OrderRequest sell = new OrderRequest(acc.getId(), brac.getId(), "SELL", "MARKET",
                "NORMAL", "DAY", null, null, null, 100L, dealerId());
        OrderService.PlaceResult res = orderService.place(sell, "dealer1");

        assertThat(res.risk().pass()).isTrue();
        assertThat(res.order().status()).isEqualTo("FILLED");
        java.math.BigDecimal realized1 = accountRepo.findById(acc.getId()).orElseThrow().getRealizedPnl();
        // BRACBANK trades above its 45.00 seed cost, so the sale books positive realized P&L
        assertThat(realized1).isNotEqualByComparingTo(realized0);
    }
}
