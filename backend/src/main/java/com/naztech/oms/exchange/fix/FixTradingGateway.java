package com.naztech.oms.exchange.fix;

import com.naztech.oms.api.Dtos.Depth;
import com.naztech.oms.entity.OmsOrder;
import com.naztech.oms.entity.Security;
import com.naztech.oms.exchange.config.FixModeCondition;
import com.naztech.oms.exchange.config.FixProperties;
import com.naztech.oms.repo.SecurityRepo;
import com.naztech.oms.service.MatchingGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;
import quickfix.Message;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.SessionNotFound;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Real-DSE {@link MatchingGateway}: routes OMS orders to the exchange over FIX (QuickFIX/J) instead
 * of the in-process simulator. Active only for {@code exchange.mode=dse-cert|dse-prod}, so it is a
 * pure configuration swap — the OMS order/risk/portfolio layers above are unchanged.
 *
 * <p>Outbound only here; inbound {@code ExecutionReport}s are handled by {@link ExecutionService}.
 * Market depth is served by the ITCH feed, so this gateway returns an empty book.
 */
@Component
@Conditional(FixModeCondition.class)
public class FixTradingGateway implements MatchingGateway {

    private static final Logger log = LoggerFactory.getLogger(FixTradingGateway.class);

    private final SecurityRepo securityRepo;
    private final FixMessageFactory factory;
    private final FixProperties props;
    private final AtomicLong cancelSeq = new AtomicLong(1);

    public FixTradingGateway(SecurityRepo securityRepo, FixMessageFactory factory, FixProperties props) {
        this.securityRepo = securityRepo;
        this.factory = factory;
        this.props = props;
        log.info("FixTradingGateway active — orders route to DSE over FIX.");
    }

    @Override
    public void submit(OmsOrder order) {
        Security sec = security(order);
        send(factory.newOrderSingle(order, sec), order.getOrderRef(), "NewOrderSingle");
    }

    @Override
    public void cancel(OmsOrder order) {
        Security sec = security(order);
        String cxlId = "CXL-" + cancelSeq.getAndIncrement() + "-" + order.getOrderRef();
        send(factory.cancel(order, sec, cxlId), order.getOrderRef(), "OrderCancelRequest");
    }

    @Override
    public void arm(OmsOrder stopOrder) {
        // Stop/trigger orders over FIX arrive with real DSE certification; not yet routed. Don't crash the flow.
        log.warn("Stop order {} armed but stop-over-FIX is not yet implemented; it will not trigger.", stopOrder.getOrderRef());
    }

    @Override
    public Depth depth(Long securityId, int levels) {
        return new Depth("?", BigDecimal.ZERO, List.of(), List.of());  // depth comes from the ITCH feed
    }

    private Security security(OmsOrder order) {
        return securityRepo.findById(order.getSecurityId())
                .orElseThrow(() -> new IllegalStateException("Unknown security " + order.getSecurityId()));
    }

    private void send(Message m, String ref, String what) {
        try {
            boolean sent = Session.sendToTarget(m, sessionId());
            if (sent) log.info("FIX {} sent (ref={})", what, ref);
            else log.warn("FIX {} NOT sent — session down? (ref={})", what, ref);
        } catch (SessionNotFound e) {
            throw new IllegalStateException("FIX session unavailable for " + what
                    + " (ref=" + ref + ") — logon may not be complete");
        }
    }

    private SessionID sessionId() {
        return new SessionID(props.getBeginString(), props.getSenderCompId(), props.getTargetCompId());
    }
}
