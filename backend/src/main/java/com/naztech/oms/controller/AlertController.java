package com.naztech.oms.controller;

import com.naztech.oms.api.Dtos.AlertRequest;
import com.naztech.oms.api.Dtos.AlertView;
import com.naztech.oms.service.PriceAlertService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Price alerts: set / list / cancel. Alerts fire via the 5s sweep in {@link PriceAlertService}. */
@RestController
@RequestMapping("/api/alerts")
public class AlertController {

    private final PriceAlertService alerts;

    public AlertController(PriceAlertService alerts) { this.alerts = alerts; }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody AlertRequest req) {
        try { return ResponseEntity.ok(alerts.create(req)); }
        catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }

    @GetMapping
    public List<AlertView> list(@RequestParam Long accountId) { return alerts.byAccount(accountId); }

    @DeleteMapping("/{id}")
    public Map<String, Object> cancel(@PathVariable Long id) { alerts.cancel(id); return Map.of("ok", true); }
}
