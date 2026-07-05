package com.naztech.oms.controller;

import com.naztech.oms.entity.AppUser;
import com.naztech.oms.entity.AuditLog;
import com.naztech.oms.entity.Broker;
import com.naztech.oms.repo.*;
import com.naztech.oms.service.SecuritySearchService;
import com.naztech.oms.service.StreamService;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Exchange-level control plane: overview, broker onboarding, users, audit. */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final BrokerRepo brokerRepo;
    private final AppUserRepo userRepo;
    private final SecurityRepo securityRepo;
    private final ClientAccountRepo accountRepo;
    private final OmsOrderRepo orderRepo;
    private final TradeRepo tradeRepo;
    private final AuditLogRepo auditRepo;
    private final SecuritySearchService search;
    private final StreamService stream;

    public AdminController(BrokerRepo brokerRepo, AppUserRepo userRepo, SecurityRepo securityRepo,
                           ClientAccountRepo accountRepo, OmsOrderRepo orderRepo, TradeRepo tradeRepo,
                           AuditLogRepo auditRepo, SecuritySearchService search, StreamService stream) {
        this.brokerRepo = brokerRepo;
        this.userRepo = userRepo;
        this.securityRepo = securityRepo;
        this.accountRepo = accountRepo;
        this.orderRepo = orderRepo;
        this.tradeRepo = tradeRepo;
        this.auditRepo = auditRepo;
        this.search = search;
        this.stream = stream;
    }

    @GetMapping("/overview")
    public Map<String, Object> overview() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("brokers", brokerRepo.count());
        m.put("users", userRepo.count());
        m.put("securities", securityRepo.count());
        m.put("accounts", accountRepo.count());
        m.put("orders", orderRepo.count());
        m.put("trades", tradeRepo.count());
        m.put("sseClients", stream.clientCount());
        m.put("aiReady", search.isReady());
        m.put("aiIndexed", search.indexed());
        return m;
    }

    @GetMapping("/brokers")
    public List<Broker> brokers() { return brokerRepo.findAll(); }

    @PostMapping("/brokers")
    public Broker onboard(@RequestBody Broker b) {
        if (b.getStatus() == null) b.setStatus("ACTIVE");
        b.setOnboardedAt(LocalDateTime.now());
        return brokerRepo.save(b);
    }

    @GetMapping("/users")
    public List<Map<String, Object>> users() {
        return userRepo.findAll().stream().map(this::safeUser).collect(Collectors.toList());
    }

    private Map<String, Object> safeUser(AppUser u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.getId());
        m.put("username", u.getUsername());
        m.put("displayName", u.getDisplayName());
        m.put("role", u.getRole());
        m.put("brokerId", u.getBrokerId());
        m.put("status", u.getStatus());
        m.put("lastLogin", u.getLastLogin());
        return m;
    }

    @GetMapping("/audit")
    public List<AuditLog> audit() { return auditRepo.findTop100ByOrderByCreatedAtDesc(); }
}
