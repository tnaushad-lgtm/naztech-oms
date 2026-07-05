package com.naztech.oms.exchange;

import com.naztech.oms.entity.OmsOrder;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the extracted {@link OrderFillApplier} reproduces the simulator's fill math exactly
 * (VWAP at scale 4, HALF_UP) and the FILLED/PARTIAL/OPEN status derivation — so demo and
 * real-exchange (FIX) fills stay identical.
 */
class OrderFillApplierTest {

    private final OrderFillApplier fills = new OrderFillApplier();

    private OmsOrder order(long qty) {
        OmsOrder o = new OmsOrder();
        o.setQuantity(qty);
        o.setFilledQty(0L);
        o.setAvgFillPrice(BigDecimal.ZERO);
        return o;
    }

    @Test
    void single_partial_fill_sets_avg_and_status() {
        OmsOrder o = order(1000);
        fills.applyFill(o, 400, new BigDecimal("100"));
        assertThat(o.getFilledQty()).isEqualTo(400L);
        assertThat(o.getAvgFillPrice()).isEqualByComparingTo("100.0000");
        assertThat(fills.deriveStatus(o)).isEqualTo("PARTIAL");
    }

    @Test
    void vwap_across_two_fills_then_filled() {
        OmsOrder o = order(1000);
        fills.applyFill(o, 400, new BigDecimal("100"));
        fills.applyFill(o, 600, new BigDecimal("110"));
        assertThat(o.getFilledQty()).isEqualTo(1000L);
        assertThat(o.getAvgFillPrice()).isEqualByComparingTo("106.0000"); // (400*100 + 600*110)/1000
        assertThat(fills.deriveStatus(o)).isEqualTo("FILLED");
    }

    @Test
    void rounding_is_half_up_scale_4() {
        OmsOrder o = order(10);
        fills.applyFill(o, 1, new BigDecimal("1"));
        fills.applyFill(o, 2, new BigDecimal("2"));
        assertThat(o.getAvgFillPrice()).isEqualByComparingTo("1.6667"); // 5/3 = 1.66666..., HALF_UP @ 4dp
    }

    @Test
    void unfilled_order_is_open() {
        assertThat(fills.deriveStatus(order(100))).isEqualTo("OPEN");
    }

    @Test
    void null_initial_fields_are_handled() {
        OmsOrder o = new OmsOrder();
        o.setQuantity(500L); // filledQty & avgFillPrice left null, as on a fresh order
        fills.applyFill(o, 200, new BigDecimal("50"));
        assertThat(o.getFilledQty()).isEqualTo(200L);
        assertThat(o.getAvgFillPrice()).isEqualByComparingTo("50.0000");
        assertThat(fills.deriveStatus(o)).isEqualTo("PARTIAL");
    }
}
