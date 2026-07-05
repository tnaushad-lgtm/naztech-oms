package com.naztech.oms.exchange.fix;

import com.naztech.oms.entity.OmsOrder;
import com.naztech.oms.entity.Security;
import org.junit.jupiter.api.Test;
import quickfix.field.Account;
import quickfix.field.ClOrdID;
import quickfix.field.OrdType;
import quickfix.field.OrderQty;
import quickfix.field.OrigClOrdID;
import quickfix.field.Price;
import quickfix.field.Side;
import quickfix.field.Symbol;
import quickfix.field.TimeInForce;
import quickfix.fix50sp1.NewOrderSingle;
import quickfix.fix50sp1.OrderCancelRequest;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies outbound FIX 5.0 SP1 order messages carry the right tags — proving orders will translate
 * correctly onto the wire to FIXSIM / DSE (no live session needed).
 */
class FixMessageFactoryTest {

    private final FixMessageFactory f = new FixMessageFactory();

    private Security sec() {
        Security s = new Security();
        s.setId(5L);
        s.setSymbol("GP");
        return s;
    }

    private OmsOrder order(String side, String type, double price, long qty) {
        OmsOrder o = new OmsOrder();
        o.setOrderRef("ORD-1");
        o.setSide(side);
        o.setOrderType(type);
        o.setPrice(BigDecimal.valueOf(price));
        o.setQuantity(qty);
        o.setValidity("DAY");
        o.setAccountId(7L);
        return o;
    }

    @Test
    void builds_limit_new_order_single() throws Exception {
        NewOrderSingle m = f.newOrderSingle(order("BUY", "LIMIT", 300.5, 1000), sec());
        assertThat(m.getString(ClOrdID.FIELD)).isEqualTo("ORD-1");
        assertThat(m.getChar(Side.FIELD)).isEqualTo(Side.BUY);
        assertThat(m.getChar(OrdType.FIELD)).isEqualTo(OrdType.LIMIT);
        assertThat(m.getString(Symbol.FIELD)).isEqualTo("GP");
        assertThat(m.getDouble(OrderQty.FIELD)).isEqualTo(1000.0);
        assertThat(m.getDouble(Price.FIELD)).isEqualTo(300.5);
        assertThat(m.getChar(TimeInForce.FIELD)).isEqualTo(TimeInForce.DAY);
        assertThat(m.getString(Account.FIELD)).isEqualTo("7");
    }

    @Test
    void market_order_carries_no_price() throws Exception {
        NewOrderSingle m = f.newOrderSingle(order("SELL", "MARKET", 0, 500), sec());
        assertThat(m.getChar(Side.FIELD)).isEqualTo(Side.SELL);
        assertThat(m.getChar(OrdType.FIELD)).isEqualTo(OrdType.MARKET);
        assertThat(m.isSetField(Price.FIELD)).isFalse();
    }

    @Test
    void cancel_references_the_original_clordid() throws Exception {
        OrderCancelRequest m = f.cancel(order("BUY", "LIMIT", 300, 1000), sec(), "CXL-1");
        assertThat(m.getString(OrigClOrdID.FIELD)).isEqualTo("ORD-1");
        assertThat(m.getString(ClOrdID.FIELD)).isEqualTo("CXL-1");
        assertThat(m.getChar(Side.FIELD)).isEqualTo(Side.BUY);
    }
}
