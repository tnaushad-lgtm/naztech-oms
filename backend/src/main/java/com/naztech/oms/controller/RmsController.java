package com.naztech.oms.controller;

import com.naztech.oms.api.Dtos.OrderView;
import com.naztech.oms.entity.Broker;
import com.naztech.oms.entity.RiskLimit;
import com.naztech.oms.repo.BrokerRepo;
import com.naztech.oms.repo.RiskLimitRepo;
import com.naztech.oms.service.AuditService;
import com.naztech.oms.service.OrderService;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Risk Management terminal: limits, live alerts, and the per-broker kill-switch. */
@RestController
@RequestMapping("/api/rms")
public class RmsController {

    private final RiskLimitRepo limitRepo;
    private final BrokerRepo brokerRepo;
    private final OrderService orders;
    private final AuditService audit;

    public RmsController(RiskLimitRepo limitRepo, BrokerRepo brokerRepo, OrderService orders, AuditService audit) {
        this.limitRepo = limitRepo;
        this.brokerRepo = brokerRepo;
        this.orders = orders;
        this.audit = audit;
    }

    @GetMapping("/brokers")
    public List<Broker> brokers() { return brokerRepo.findAll(); }

    /** Per-broker kill-switch: halt suspends all new orders; resume restores trading. */
    @PostMapping("/broker/{id}/halt")
    public Object halt(@PathVariable Long id, @RequestParam(defaultValue = "true") boolean halt,
                       @RequestHeader(value = "X-Actor", defaultValue = "rms") String actor) {
        Broker b = brokerRepo.findById(id).orElseThrow();
        b.setStatus(halt ? "SUSPENDED" : "ACTIVE");
        brokerRepo.save(b);
        audit.audit(actor, halt ? "BROKER_HALT" : "BROKER_RESUME", "BROKER", String.valueOf(id), b.getName());
        return Map.of("brokerId", id, "status", b.getStatus());
    }

    @GetMapping("/limits")
    public List<RiskLimit> limits() { return limitRepo.findAll(); }

    @PutMapping("/limits/{id}")
    public RiskLimit update(@PathVariable Long id, @RequestBody RiskLimit body) {
        RiskLimit l = limitRepo.findById(id).orElseThrow();
        if (body.getMaxOrderValue() != null) l.setMaxOrderValue(body.getMaxOrderValue());
        if (body.getMaxOrderQty() != null) l.setMaxOrderQty(body.getMaxOrderQty());
        if (body.getMaxGrossExposure() != null) l.setMaxGrossExposure(body.getMaxGrossExposure());
        if (body.getMtmLossLimit() != null) l.setMtmLossLimit(body.getMtmLossLimit());
        if (body.getWashSaleBlock() != null) l.setWashSaleBlock(body.getWashSaleBlock());
        if (body.getEnabled() != null) l.setEnabled(body.getEnabled());
        return limitRepo.save(l);
    }

    /** Recent rejected or elevated-risk orders for the RMS alert feed. */
    @GetMapping("/alerts")
    public List<OrderView> alerts(@RequestParam(defaultValue = "40") double minScore) {
        return orders.recent().stream()
                .filter(o -> "REJECTED".equals(o.status())
                        || (o.riskScore() != null && o.riskScore().compareTo(BigDecimal.valueOf(minScore)) >= 0))
                .limit(50)
                .collect(Collectors.toList());
    }
}
