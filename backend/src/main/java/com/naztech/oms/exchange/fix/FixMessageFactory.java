package com.naztech.oms.exchange.fix;

import com.naztech.oms.entity.OmsOrder;
import com.naztech.oms.entity.Security;
import org.springframework.stereotype.Component;
import quickfix.field.Account;
import quickfix.field.ClOrdID;
import quickfix.field.OrdType;
import quickfix.field.OrderQty;
import quickfix.field.OrigClOrdID;
import quickfix.field.Price;
import quickfix.field.Side;
import quickfix.field.Symbol;
import quickfix.field.TimeInForce;
import quickfix.field.TransactTime;
import quickfix.fix50sp1.NewOrderSingle;
import quickfix.fix50sp1.OrderCancelReplaceRequest;
import quickfix.fix50sp1.OrderCancelRequest;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Builds outbound FIX 5.0 SP1 order messages from OMS orders. Core FIX fields only (works against
 * FIXSIM and a standard exchange); DSE-specific enrichment (Parties/PartyRole=5, OrderRestrictions,
 * bond Yield) is layered on when we have real DSE certification access.
 *
 * <h2>What goes in Symbol(55)</h2>
 * A venue identifies an instrument by its own key, and the two venues we talk to disagree. Our local
 * test exchange matches on the <b>ticker</b> ("GP"); the DSE X-stream simulator (nFIX) and real DSE
 * match on the integer <b>order-book id</b> ("1004"). Sending the wrong one means every order comes
 * back "unknown symbol". {@code fix.symbol} selects which — {@code ticker} (default, for the local
 * exchange) or {@code orderbookid} (for nFIX / real DSE, where our {@code security.id} <em>is</em> the
 * order-book id because the master is built from the feed).
 */
@Component
public class FixMessageFactory {

    @org.springframework.beans.factory.annotation.Value("${fix.symbol:ticker}")
    private String symbolMode;

    /** The instrument key this venue expects in Symbol(55). */
    private String venueSymbol(Security sec) {
        return "orderbookid".equalsIgnoreCase(symbolMode)
                ? String.valueOf(sec.getId())        // = the DSE order-book id
                : sec.getSymbol();
    }

    public NewOrderSingle newOrderSingle(OmsOrder o, Security sec) {
        NewOrderSingle m = new NewOrderSingle();
        m.set(new ClOrdID(o.getOrderRef()));
        m.set(new Side(side(o.getSide())));
        m.set(new TransactTime(LocalDateTime.now(ZoneOffset.UTC)));
        m.set(new OrdType(ordType(o.getOrderType())));
        m.set(new Symbol(venueSymbol(sec)));
        m.set(new OrderQty(o.getQuantity() == null ? 0 : o.getQuantity().doubleValue()));
        if (isLimit(o)) {
            if ("YIELD".equalsIgnoreCase(o.getPriceBasis()) && o.getOrderYield() != null) {
                m.setDouble(236, o.getOrderYield().doubleValue());     // 236=Yield — DSE bond, price-by-yield (44=Price omitted)
            } else if (o.getPrice() != null) {
                m.set(new Price(o.getPrice().doubleValue()));
            }
        }
        m.set(new TimeInForce(tif(o.getValidity())));
        if (o.getAccountId() != null) m.set(new Account(String.valueOf(o.getAccountId())));
        return m;
    }

    public OrderCancelRequest cancel(OmsOrder o, Security sec, String newClOrdId) {
        OrderCancelRequest m = new OrderCancelRequest();
        m.set(new OrigClOrdID(o.getOrderRef()));
        m.set(new ClOrdID(newClOrdId));
        m.set(new Side(side(o.getSide())));
        m.set(new TransactTime(LocalDateTime.now(ZoneOffset.UTC)));
        m.set(new Symbol(venueSymbol(sec)));
        m.set(new OrderQty(o.getQuantity() == null ? 0 : o.getQuantity().doubleValue()));
        return m;
    }

    public OrderCancelReplaceRequest replace(OmsOrder o, Security sec, String newClOrdId, BigDecimal newPrice, long newQty) {
        OrderCancelReplaceRequest m = new OrderCancelReplaceRequest();
        m.set(new OrigClOrdID(o.getOrderRef()));
        m.set(new ClOrdID(newClOrdId));
        m.set(new Side(side(o.getSide())));
        m.set(new TransactTime(LocalDateTime.now(ZoneOffset.UTC)));
        m.set(new OrdType(ordType(o.getOrderType())));
        m.set(new Symbol(venueSymbol(sec)));
        m.set(new OrderQty(newQty));
        if (newPrice != null) m.set(new Price(newPrice.doubleValue()));
        return m;
    }

    private char side(String s) { return "SELL".equalsIgnoreCase(s) ? Side.SELL : Side.BUY; }
    private char ordType(String t) { return "MARKET".equalsIgnoreCase(t) ? OrdType.MARKET : OrdType.LIMIT; }
    private boolean isLimit(OmsOrder o) { return !"MARKET".equalsIgnoreCase(o.getOrderType()); }

    private char tif(String v) {
        if (v == null) return TimeInForce.DAY;
        return switch (v.toUpperCase()) {
            case "GTC" -> TimeInForce.GOOD_TILL_CANCEL;
            case "GTD" -> TimeInForce.GOOD_TILL_DATE;
            case "IOC" -> TimeInForce.IMMEDIATE_OR_CANCEL;
            case "FOK" -> TimeInForce.FILL_OR_KILL;
            default -> TimeInForce.DAY;
        };
    }
}
