package com.naztech.oms.service;

import com.naztech.oms.api.Dtos.*;
import com.naztech.oms.entity.*;
import com.naztech.oms.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

/** Positions, cash, mark-to-market P&L, allocations and the fills ledger. */
@Service
public class PortfolioService {

    private final ClientAccountRepo accountRepo;
    private final HoldingRepo holdingRepo;
    private final SecurityRepo securityRepo;
    private final MarketDataRepo marketRepo;
    private final OmsOrderRepo orderRepo;
    private final TradeRepo tradeRepo;

    public PortfolioService(ClientAccountRepo accountRepo, HoldingRepo holdingRepo,
                            SecurityRepo securityRepo, MarketDataRepo marketRepo,
                            OmsOrderRepo orderRepo, TradeRepo tradeRepo) {
        this.accountRepo = accountRepo;
        this.holdingRepo = holdingRepo;
        this.securityRepo = securityRepo;
        this.marketRepo = marketRepo;
        this.orderRepo = orderRepo;
        this.tradeRepo = tradeRepo;
    }

    /** Apply one execution to an account: cash, holding, average cost and realized P&L. */
    @Transactional
    public void applyFill(Long accountId, Long securityId, String side, long qty, BigDecimal price) {
        if (accountId == null) return; // synthetic counterparty — no real portfolio
        Optional<ClientAccount> accOpt = accountRepo.findById(accountId);
        if (accOpt.isEmpty()) return;
        ClientAccount acc = accOpt.get();
        BigDecimal notional = price.multiply(BigDecimal.valueOf(qty));

        Holding h = holdingRepo.findByAccountIdAndSecurityId(accountId, securityId).orElseGet(() -> {
            Holding nh = new Holding();
            nh.setAccountId(accountId);
            nh.setSecurityId(securityId);
            nh.setQuantity(0L);
            nh.setAvgCost(BigDecimal.ZERO);
            return nh;
        });

        if ("BUY".equals(side)) {
            long newQty = h.getQuantity() + qty;
            BigDecimal oldCost = h.getAvgCost().multiply(BigDecimal.valueOf(h.getQuantity()));
            BigDecimal newAvg = newQty == 0 ? BigDecimal.ZERO :
                    oldCost.add(notional).divide(BigDecimal.valueOf(newQty), 4, RoundingMode.HALF_UP);
            h.setQuantity(newQty);
            h.setAvgCost(newAvg);
            acc.setCashBalance(acc.getCashBalance().subtract(notional));
            acc.setBuyingPower(acc.getBuyingPower().subtract(notional));
        } else { // SELL — book realized P&L against the current average cost
            BigDecimal realized = price.subtract(h.getAvgCost()).multiply(BigDecimal.valueOf(qty));
            acc.setRealizedPnl(nz(acc.getRealizedPnl()).add(realized));
            long newQty = Math.max(0, h.getQuantity() - qty);
            h.setQuantity(newQty);
            if (newQty == 0) h.setAvgCost(BigDecimal.ZERO);
            acc.setCashBalance(acc.getCashBalance().add(notional));
            acc.setBuyingPower(acc.getBuyingPower().add(notional));
        }
        holdingRepo.save(h);
        accountRepo.save(acc);
    }

    public PortfolioView portfolio(Long accountId) {
        ClientAccount acc = accountRepo.findById(accountId).orElse(null);
        if (acc == null) return null;

        List<PositionView> positions = new ArrayList<>();
        BigDecimal holdingsValue = BigDecimal.ZERO, invested = BigDecimal.ZERO;
        BigDecimal totalUnreal = BigDecimal.ZERO, totalDay = BigDecimal.ZERO;
        Map<String, BigDecimal> sectorVal = new LinkedHashMap<>();
        Map<String, BigDecimal> assetVal = new LinkedHashMap<>();

        // first pass — gather values
        record Tmp(Security s, MarketData m, Holding h, BigDecimal mktVal) {}
        List<Tmp> tmp = new ArrayList<>();
        for (Holding h : holdingRepo.findByAccountId(accountId)) {
            if (h.getQuantity() == null || h.getQuantity() == 0) continue;
            Security s = securityRepo.findById(h.getSecurityId()).orElse(null);
            if (s == null) continue;
            MarketData m = marketRepo.findById(h.getSecurityId()).orElse(null);
            BigDecimal ltp = (m == null || m.getLtp() == null) ? h.getAvgCost() : m.getLtp();
            BigDecimal mktVal = ltp.multiply(BigDecimal.valueOf(h.getQuantity()));
            holdingsValue = holdingsValue.add(mktVal);
            tmp.add(new Tmp(s, m, h, mktVal));
        }

        for (Tmp t : tmp) {
            BigDecimal qty = BigDecimal.valueOf(t.h().getQuantity());
            BigDecimal ltp = (t.m() == null || t.m().getLtp() == null) ? t.h().getAvgCost() : t.m().getLtp();
            BigDecimal ycp = (t.m() == null || t.m().getYcp() == null || t.m().getYcp().signum() == 0) ? ltp : t.m().getYcp();
            BigDecimal cost = t.h().getAvgCost().multiply(qty);
            BigDecimal pnl = t.mktVal().subtract(cost);
            BigDecimal pnlPct = cost.signum() == 0 ? BigDecimal.ZERO :
                    pnl.multiply(HUNDRED).divide(cost, 2, RoundingMode.HALF_UP);
            BigDecimal dayPnl = ltp.subtract(ycp).multiply(qty);
            BigDecimal dayPct = ycp.signum() == 0 ? BigDecimal.ZERO :
                    ltp.subtract(ycp).multiply(HUNDRED).divide(ycp, 2, RoundingMode.HALF_UP);
            BigDecimal weight = holdingsValue.signum() == 0 ? BigDecimal.ZERO :
                    t.mktVal().multiply(HUNDRED).divide(holdingsValue, 2, RoundingMode.HALF_UP);

            invested = invested.add(cost);
            totalUnreal = totalUnreal.add(pnl);
            totalDay = totalDay.add(dayPnl);
            String sec = t.s().getSector() == null ? "Other" : t.s().getSector();
            sectorVal.merge(sec, t.mktVal(), BigDecimal::add);
            assetVal.merge(t.s().getAssetClass(), t.mktVal(), BigDecimal::add);

            positions.add(new PositionView(t.s().getId(), t.s().getSymbol(), t.s().getName(),
                    t.s().getSector(), t.s().getAssetClass(), t.h().getQuantity(), t.h().getAvgCost(),
                    ltp, ycp, cost, t.mktVal(), pnl, pnlPct, dayPnl, dayPct, weight));
        }
        positions.sort(Comparator.comparing(PositionView::marketValue).reversed());

        BigDecimal total = nz(acc.getCashBalance()).add(holdingsValue);
        return new PortfolioView(acc.getId(), acc.getName(),
                nz(acc.getCashBalance()), nz(acc.getBuyingPower()), invested, holdingsValue, total,
                totalUnreal, nz(acc.getRealizedPnl()), totalDay, positions,
                slices(sectorVal, holdingsValue), slices(assetVal, holdingsValue));
    }

    /** Recent executions for an account, derived from its orders. */
    public List<FillRow> fills(Long accountId) {
        return fillsFromOrders(orderRepo.findByAccountIdOrderByCreatedAtDesc(accountId));
    }

    /** Broker-wide trade book (all executions across the broker's orders). */
    public List<FillRow> brokerFills(Long brokerId) {
        return fillsFromOrders(orderRepo.findByBrokerIdOrderByCreatedAtDesc(brokerId));
    }

    private List<FillRow> fillsFromOrders(List<OmsOrder> orders) {
        if (orders.isEmpty()) return List.of();
        Map<Long, OmsOrder> byId = orders.stream().collect(Collectors.toMap(OmsOrder::getId, o -> o));
        Set<Long> ids = byId.keySet();
        Map<Long, Security> secs = securityRepo.findAll().stream()
                .collect(Collectors.toMap(Security::getId, s -> s));
        return tradeRepo.findTop200ByBuyOrderIdInOrSellOrderIdInOrderByExecutedAtDesc(ids, ids).stream()
                .map(t -> {
                    OmsOrder own = byId.get(t.getBuyOrderId());
                    String side = own != null ? "BUY" : "SELL";
                    Security s = secs.get(t.getSecurityId());
                    return new FillRow(
                            t.getExecutedAt() == null ? null : t.getExecutedAt().toString(),
                            s == null ? "?" : s.getSymbol(), side, t.getQuantity(), t.getPrice(),
                            t.getPrice().multiply(BigDecimal.valueOf(t.getQuantity())), t.getTradeRef());
                })
                .collect(Collectors.toList());
    }

    private List<AllocSlice> slices(Map<String, BigDecimal> m, BigDecimal total) {
        if (total.signum() == 0) return List.of();
        return m.entrySet().stream()
                .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
                .map(e -> new AllocSlice(e.getKey(), e.getValue(),
                        e.getValue().multiply(HUNDRED).divide(total, 2, RoundingMode.HALF_UP)))
                .collect(Collectors.toList());
    }

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static BigDecimal nz(BigDecimal b) { return b == null ? BigDecimal.ZERO : b; }
}
