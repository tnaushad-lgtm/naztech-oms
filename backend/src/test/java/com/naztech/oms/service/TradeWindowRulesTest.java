package com.naztech.oms.service;

import com.naztech.oms.entity.Security;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The board rules the OMS stored but never enforced. Each of these was, until now, an order the OMS
 * would happily accept and route — and the exchange would refuse.
 */
class TradeWindowRulesTest {

    private TradeWindowRules rules;

    @BeforeEach
    void setUp() {
        rules = new TradeWindowRules();
        ReflectionTestUtils.setField(rules, "blockMinValue", new BigDecimal("500000"));
    }

    @Test
    @DisplayName("the normal market takes whole lots only")
    void normalMarketWantsWholeLots() {
        Security s = security(50);

        assertThat(rules.violation(s, "NORMAL", 500, money(500))).isNull();
        assertThat(rules.violation(s, "NORMAL", 507, money(507)))
                .contains("multiple of market lot 50");
    }

    @Test
    @DisplayName("a market lot of 1 — every DSE equity today — puts no lot constraint on anything")
    void aLotOfOneConstrainsNothing() {
        Security s = security(1);
        assertThat(rules.violation(s, "NORMAL", 1, money(1))).isNull();
        assertThat(rules.violation(s, "NORMAL", 137, money(137))).isNull();
    }

    @Test
    @DisplayName("an odd lot must actually be odd: smaller than one market lot")
    void oddLotMustBeSmallerThanALot() {
        Security s = security(50);

        assertThat(rules.violation(s, "ODD_LOT", 20, money(20))).isNull();          // a genuine odd lot
        assertThat(rules.violation(s, "ODD_LOT", 50, money(50)))
                .contains("must be smaller than the market lot");
        assertThat(rules.violation(s, "ODD_LOT", 100, money(100)))
                .contains("must be smaller than the market lot");
    }

    @Test
    @DisplayName("where the market lot is one share there is no such thing as an odd lot")
    void noOddLotWhenTheLotIsOneShare() {
        assertThat(rules.violation(security(1), "ODD_LOT", 1, money(1)))
                .contains("needs a market lot above 1 share");
    }

    @Test
    @DisplayName("the block market has a floor — a small order belongs in the public market")
    void blockMarketHasAFloor() {
        Security s = security(1);

        // 5,000 shares at 47.80 = 239,000: real money, but not block-market money.
        assertThat(rules.violation(s, "BLOCK", 5_000, money(5_000)))
                .contains("at least 500000");

        // 20,000 shares at 47.80 = 956,000: over the Tk 5 lakh floor.
        assertThat(rules.violation(s, "BLOCK", 20_000, money(20_000))).isNull();
    }

    @Test
    @DisplayName("an unknown window is rejected rather than quietly treated as normal")
    void unknownWindowIsRejected() {
        assertThat(rules.violation(security(1), "AFTER_HOURS", 100, money(100)))
                .contains("Unknown trade window");
    }

    @Test
    @DisplayName("a missing window means the normal market, which is what the blotter has always shown")
    void aMissingWindowMeansNormal() {
        assertThat(rules.violation(security(50), null, 500, money(500))).isNull();
        assertThat(rules.violation(security(50), "", 507, money(507)))
                .contains("multiple of market lot 50");
    }

    private static Security security(int marketLot) {
        Security s = new Security();
        s.setSymbol("BRACBANK");
        s.setMarketLot(marketLot);
        s.setLotSize(marketLot);
        return s;
    }

    /** Notional at BRACBANK's 47.80. */
    private static BigDecimal money(long qty) {
        return new BigDecimal("47.80").multiply(BigDecimal.valueOf(qty));
    }
}
