package com.naztech.oms.service;

import com.naztech.oms.api.Dtos.*;
import com.naztech.oms.entity.ClientAccount;
import com.naztech.oms.entity.OmsOrder;
import com.naztech.oms.entity.Security;
import com.naztech.oms.repo.ClientAccountRepo;
import com.naztech.oms.repo.OmsOrderRepo;
import com.naztech.oms.repo.SecurityRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/** Full order lifecycle: validate → pre-trade risk → route to matching engine → track. */
@Service
public class OrderService {

    private static final Set<String> CANCELABLE = Set.of("NEW", "PENDING_RISK", "OPEN", "PARTIAL");

    private final SecurityRepo securityRepo;
    private final ClientAccountRepo accountRepo;
    private final OmsOrderRepo orderRepo;
    private final RiskService riskService;
    private final MatchingGateway matching;
    private final AuditService audit;
    private final StreamService stream;
    private final BondService bonds;

    public OrderService(SecurityRepo securityRepo, ClientAccountRepo accountRepo, OmsOrderRepo orderRepo,
                        RiskService riskService, MatchingGateway matching, AuditService audit, StreamService stream,
                        BondService bonds) {
        this.securityRepo = securityRepo;
        this.accountRepo = accountRepo;
        this.orderRepo = orderRepo;
        this.riskService = riskService;
        this.matching = matching;
        this.audit = audit;
        this.stream = stream;
        this.bonds = bonds;
    }

    public record PlaceResult(OrderView order, RiskResult risk) {}

    @Transactional
    public PlaceResult place(OrderRequest req, String actor) {
        Security sec = securityRepo.findById(req.securityId())
                .orElseThrow(() -> new IllegalArgumentException("Unknown security"));
        ClientAccount acc = accountRepo.findById(req.accountId())
                .orElseThrow(() -> new IllegalArgumentException("Unknown account"));

        OmsOrder o = new OmsOrder();
        o.setOrderRef("ORD-" + System.currentTimeMillis() + "-" + (int) (Math.random() * 900 + 100));
        o.setExchangeId(sec.getExchangeId());
        o.setBrokerId(acc.getBrokerId());
        o.setDealerId(req.dealerId());
        o.setAccountId(acc.getId());
        o.setSecurityId(sec.getId());
        o.setSide(req.side() == null ? null : req.side().toUpperCase());
        o.setOrderType(req.orderType() == null ? "LIMIT" : req.orderType().toUpperCase());
        o.setTradeWindow(req.tradeWindow() == null ? "NORMAL" : req.tradeWindow().toUpperCase());
        o.setValidity(req.validity() == null ? "DAY" : req.validity().toUpperCase());
        o.setExpireDate(req.expireDate() == null || req.expireDate().isBlank() ? null : LocalDate.parse(req.expireDate()));
        o.setPrice(req.price() == null ? BigDecimal.ZERO : req.price());
        o.setStopPrice(req.stopPrice());
        o.setQuantity(req.quantity());
        o.setFilledQty(0L);
        o.setAvgFillPrice(BigDecimal.ZERO);
        o.setStatus("NEW");
        o.setRiskScore(BigDecimal.ZERO);
        o.setCreatedAt(LocalDateTime.now());
        o.setUpdatedAt(LocalDateTime.now());
        applyBondPricing(o, req, sec);   // bonds: derive clean price from yield (or yield from price)

        // ---- pre-trade risk ----
        RiskResult risk = riskService.check(o);
        o.setRiskScore(risk.score());
        if (!risk.pass()) {
            o.setStatus("REJECTED");
            o.setRejectReason(risk.reason());
            orderRepo.save(o);
            audit.orderEvent(o.getId(), "RISK_REJECT", risk.reason());
            audit.audit(actor, "ORDER_REJECT", "ORDER", String.valueOf(o.getId()), risk.reason());
            publishOrder(o);
            return new PlaceResult(toView(o, sec), risk);
        }

        o.setStatus("PENDING_RISK");
        orderRepo.save(o);
        audit.orderEvent(o.getId(), "RISK_PASS", "Risk score " + risk.score());

        String type = o.getOrderType();
        if ("STOP".equals(type) || "STOP_LIMIT".equals(type)) {
            o.setStatus("OPEN");
            orderRepo.save(o);
            matching.arm(o);
            audit.orderEvent(o.getId(), "ARMED", "Stop armed @ " + (o.getStopPrice() == null ? "?" : o.getStopPrice()));
        } else {
            matching.submit(o);   // matches/rests + updates status
        }
        audit.audit(actor, "ORDER_PLACE", "ORDER", String.valueOf(o.getId()),
                o.getSide() + " " + o.getQuantity() + " " + sec.getSymbol() + " @ " + o.getPrice());

        OmsOrder fresh = orderRepo.findById(o.getId()).orElse(o);
        return new PlaceResult(toView(fresh, sec), risk);
    }

    /** Amend price and/or quantity of a working (OPEN/PARTIAL) order; re-prices on the book. */
    @Transactional
    public PlaceResult modify(Long orderId, BigDecimal newPrice, Long newQty, String actor) {
        OmsOrder o = orderRepo.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown order"));
        if (!"OPEN".equals(o.getStatus()) && !"PARTIAL".equals(o.getStatus()))
            throw new IllegalStateException("Only working orders (OPEN/PARTIAL) can be amended");
        if ("STOP".equals(o.getOrderType()) || "STOP_LIMIT".equals(o.getOrderType()))
            throw new IllegalStateException("A stop order can't be amended — cancel and re-place it (amending would defeat the trigger)");

        long filled = o.getFilledQty() == null ? 0 : o.getFilledQty();
        BigDecimal oldPrice = o.getPrice();
        Long oldQty = o.getQuantity();
        long qty = newQty != null ? newQty : o.getQuantity();
        if (qty <= filled) throw new IllegalStateException("New quantity must exceed already-filled " + filled);

        matching.cancel(o);                                  // pull from book
        o.setPrice(newPrice != null ? newPrice : o.getPrice());
        o.setQuantity(qty);

        RiskResult risk = riskService.checkAmend(o);
        if (!risk.pass()) {                                  // revert and re-rest the original
            o.setPrice(oldPrice);
            o.setQuantity(oldQty);
            orderRepo.save(o);
            matching.submit(o);
            throw new IllegalStateException("Amend rejected: " + risk.reason());
        }
        o.setRiskScore(risk.score());
        o.setStatus("PENDING_RISK");
        o.setUpdatedAt(LocalDateTime.now());
        orderRepo.save(o);
        matching.submit(o);                                  // re-match remaining, re-rest leftover
        audit.orderEvent(o.getId(), "AMENDED", "Amended to " + qty + " @ " + o.getPrice().toPlainString());
        audit.audit(actor, "ORDER_AMEND", "ORDER", String.valueOf(o.getId()),
                "qty " + oldQty + "→" + qty + ", px " + oldPrice + "→" + o.getPrice());
        OmsOrder fresh = orderRepo.findById(o.getId()).orElse(o);
        return new PlaceResult(toView(fresh, securityRepo.findById(o.getSecurityId()).orElse(null)), risk);
    }

    @Transactional
    public OrderView cancel(Long orderId, String actor) {
        OmsOrder o = orderRepo.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown order"));
        if (!CANCELABLE.contains(o.getStatus()))
            throw new IllegalStateException("Order is " + o.getStatus() + " and cannot be cancelled");
        matching.cancel(o);
        o.setStatus("CANCELLED");
        o.setUpdatedAt(LocalDateTime.now());
        orderRepo.save(o);
        audit.orderEvent(o.getId(), "CANCELLED", "Cancelled by " + actor);
        audit.audit(actor, "ORDER_CANCEL", "ORDER", String.valueOf(o.getId()), null);
        publishOrder(o);
        return toView(o, securityRepo.findById(o.getSecurityId()).orElse(null));
    }

    public List<OrderView> blotterByBroker(Long brokerId) {
        return mapViews(orderRepo.findByBrokerIdOrderByCreatedAtDesc(brokerId));
    }

    public List<OrderView> blotterByAccount(Long accountId) {
        return mapViews(orderRepo.findByAccountIdOrderByCreatedAtDesc(accountId));
    }

    public List<OrderView> recent() {
        return mapViews(orderRepo.findTop200ByOrderByCreatedAtDesc());
    }

    private List<OrderView> mapViews(List<OmsOrder> orders) {
        Map<Long, Security> secs = securityRepo.findAll().stream()
                .collect(Collectors.toMap(Security::getId, s -> s));
        return orders.stream().map(o -> toView(o, secs.get(o.getSecurityId()))).collect(Collectors.toList());
    }

    /** Bonds trade on price or yield: derive the missing side so the book always has a clean price and
     *  the order records the yield it was struck at. Equities are unaffected (PRICE basis, no yield). */
    private void applyBondPricing(OmsOrder o, OrderRequest req, Security sec) {
        if (!bonds.isBond(sec)) { o.setPriceBasis("PRICE"); o.setOrderYield(null); return; }
        boolean byYield = "YIELD".equalsIgnoreCase(req.priceBasis()) && req.orderYield() != null;
        if (!byYield && (o.getPrice() == null || o.getPrice().signum() <= 0)) {
            o.setPriceBasis("PRICE"); o.setOrderYield(null); return;   // market / no-price bond order
        }
        BondQuote q = byYield ? bonds.quote(sec, "YIELD", req.orderYield())
                              : bonds.quote(sec, "PRICE", o.getPrice());
        o.setPrice(q.cleanPrice());
        o.setOrderYield(q.yield());
        o.setPriceBasis(byYield ? "YIELD" : "PRICE");
    }

    private void publishOrder(OmsOrder o) {
        stream.publish("order", Map.of("id", o.getId(), "status", o.getStatus(),
                "brokerId", o.getBrokerId() == null ? 0 : o.getBrokerId(),
                "accountId", o.getAccountId() == null ? 0 : o.getAccountId()));
    }

    private OrderView toView(OmsOrder o, Security s) {
        return new OrderView(
                o.getId(), o.getOrderRef(),
                s == null ? "?" : s.getSymbol(), s == null ? "" : s.getName(),
                o.getSide(), o.getOrderType(), o.getTradeWindow(), o.getValidity(),
                o.getPrice(), o.getQuantity(), o.getFilledQty(), o.getAvgFillPrice(),
                o.getStatus(), o.getRejectReason(), o.getRiskScore(),
                o.getCreatedAt() == null ? null : o.getCreatedAt().toString(),
                o.getOrderYield(), o.getPriceBasis());
    }
}
