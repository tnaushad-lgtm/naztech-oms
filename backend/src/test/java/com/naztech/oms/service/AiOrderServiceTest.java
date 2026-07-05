package com.naztech.oms.service;

import com.naztech.oms.api.Dtos.ParsedOrder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The AI Order Bot parser: natural-language → structured orders (exact ticker + MiniLM fuzzy resolution). */
@SpringBootTest
class AiOrderServiceTest {

    @Autowired
    AiOrderService svc;

    @Test
    void parses_buy_with_quantity_and_price() {
        List<ParsedOrder> os = svc.parse("buy GP 100 quantity of 120 taka price");
        assertThat(os).hasSize(1);
        ParsedOrder o = os.get(0);
        assertThat(o.ok()).isTrue();
        assertThat(o.side()).isEqualTo("BUY");
        assertThat(o.symbol()).isEqualTo("GP");
        assertThat(o.quantity()).isEqualTo(100L);
        assertThat(o.price()).isEqualByComparingTo("120");
    }

    @Test
    void parses_two_orders_in_one_sentence() {
        List<ParsedOrder> os = svc.parse("buy GP 100 at 120 and sell 50 BRACBANK at 64");
        assertThat(os).hasSize(2);
        assertThat(os.get(0).side()).isEqualTo("BUY");
        assertThat(os.get(0).symbol()).isEqualTo("GP");
        assertThat(os.get(1).side()).isEqualTo("SELL");
        assertThat(os.get(1).symbol()).isEqualTo("BRACBANK");
        assertThat(os.get(1).quantity()).isEqualTo(50L);
        assertThat(os.get(1).price()).isEqualByComparingTo("64");
    }

    @Test
    void parses_bond_order_by_yield() {
        List<ParsedOrder> os = svc.parse("buy PBLPBOND 10 at 9% yield");
        assertThat(os).hasSize(1);
        ParsedOrder o = os.get(0);
        assertThat(o.symbol()).isEqualTo("PBLPBOND");
        assertThat(o.quantity()).isEqualTo(10L);
        assertThat(o.priceBasis()).isEqualTo("YIELD");
        assertThat(o.orderYield()).isEqualByComparingTo("9");
    }
}
