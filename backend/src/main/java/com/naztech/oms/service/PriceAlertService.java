package com.naztech.oms.service;

import com.naztech.oms.api.Dtos.AlertRequest;
import com.naztech.oms.api.Dtos.AlertView;
import com.naztech.oms.entity.MarketData;
import com.naztech.oms.entity.PriceAlert;
import com.naztech.oms.entity.Security;
import com.naztech.oms.repo.MarketDataRepo;
import com.naztech.oms.repo.PriceAlertRepo;
import com.naztech.oms.repo.SecurityRepo;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Price alerts (parity with DSE-FlexTP "Set Alert"): a client sets a target price on a security and is
 * notified when the last-traded price crosses it. A 5-second sweep evaluates ACTIVE alerts against the live
 * market picture and publishes an {@code alert} SSE event when one fires — so the UI can pop a notification.
 */
@Service
public class PriceAlertService {

    private final PriceAlertRepo repo;
    private final MarketDataRepo marketRepo;
    private final SecurityRepo securityRepo;
    private final StreamService stream;

    public PriceAlertService(PriceAlertRepo repo, MarketDataRepo marketRepo, SecurityRepo securityRepo, StreamService stream) {
        this.repo = repo;
        this.marketRepo = marketRepo;
        this.securityRepo = securityRepo;
        this.stream = stream;
    }

    public AlertView create(AlertRequest req) {
        PriceAlert a = new PriceAlert();
        a.setAccountId(req.accountId());
        a.setSecurityId(req.securityId());
        a.setTargetPrice(req.targetPrice());
        String dir = req.direction();
        if (dir == null || dir.isBlank()) {                 // infer from where the target sits vs current price
            BigDecimal ltp = ltp(req.securityId());
            dir = (ltp != null && req.targetPrice() != null && req.targetPrice().compareTo(ltp) < 0) ? "BELOW" : "ABOVE";
        }
        a.setDirection(dir.toUpperCase());
        a.setStatus("ACTIVE");
        a.setNote(req.note());
        a.setCreatedAt(LocalDateTime.now());
        repo.save(a);
        return toView(a);
    }

    public List<AlertView> byAccount(Long accountId) {
        return repo.findByAccountIdOrderByCreatedAtDesc(accountId).stream().map(this::toView).toList();
    }

    public void cancel(Long id) {
        repo.findById(id).ifPresent(a -> { if ("ACTIVE".equals(a.getStatus())) { a.setStatus("CANCELLED"); repo.save(a); } });
    }

    /** Sweep ACTIVE alerts against the live LTP; fire + notify on cross. */
    @Scheduled(fixedDelay = 5000)
    public void evaluate() {
        for (PriceAlert a : repo.findByStatus("ACTIVE")) {
            BigDecimal ltp = ltp(a.getSecurityId());
            if (ltp == null || ltp.signum() <= 0) continue;
            boolean hit = "ABOVE".equals(a.getDirection())
                    ? ltp.compareTo(a.getTargetPrice()) >= 0
                    : ltp.compareTo(a.getTargetPrice()) <= 0;
            if (!hit) continue;
            a.setStatus("TRIGGERED");
            a.setLtpAtTrigger(ltp);
            a.setTriggeredAt(LocalDateTime.now());
            repo.save(a);
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("id", a.getId());
            ev.put("symbol", symbol(a.getSecurityId()));
            ev.put("direction", a.getDirection());
            ev.put("target", a.getTargetPrice());
            ev.put("ltp", ltp);
            ev.put("accountId", a.getAccountId() == null ? 0 : a.getAccountId());
            stream.publish("alert", ev);
        }
    }

    private BigDecimal ltp(Long securityId) {
        if (securityId == null) return null;
        MarketData m = marketRepo.findById(securityId).orElse(null);
        return m == null ? null : m.getLtp();
    }

    private String symbol(Long securityId) {
        return securityRepo.findById(securityId).map(Security::getSymbol).orElse("?");
    }

    private AlertView toView(PriceAlert a) {
        Security s = a.getSecurityId() == null ? null : securityRepo.findById(a.getSecurityId()).orElse(null);
        return new AlertView(a.getId(), a.getSecurityId(),
                s == null ? "?" : s.getSymbol(), s == null ? "" : s.getName(),
                a.getTargetPrice(), a.getDirection(), a.getStatus(), a.getNote(),
                ltp(a.getSecurityId()), a.getLtpAtTrigger(),
                a.getCreatedAt() == null ? null : a.getCreatedAt().toString(),
                a.getTriggeredAt() == null ? null : a.getTriggeredAt().toString());
    }
}
