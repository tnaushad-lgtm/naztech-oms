package com.naztech.oms.perf;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Drives the throughput harness from the Exchange Connectivity screen.
 *
 * <p>This endpoint floods the OMS with orders, and the backend has no authentication layer, so it
 * is <b>disabled unless {@code app.loadtest.enabled=true}</b> — set {@code LOADTEST_ENABLED=false}
 * for UAT and production and the whole controller stops existing.
 */
@RestController
@RequestMapping("/api/admin/loadtest")
@ConditionalOnProperty(prefix = "app.loadtest", name = "enabled", havingValue = "true")
public class LoadTestController {

    private final LoadTestService loadTest;

    public LoadTestController(LoadTestService loadTest) {
        this.loadTest = loadTest;
    }

    @PostMapping("/start")
    public ResponseEntity<?> start(@RequestBody LoadTest.Request req) {
        try {
            loadTest.start(req);
            return ResponseEntity.ok(Map.of("ok", true, "status", loadTest.status()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", e.getMessage()));
        }
    }

    @PostMapping("/stop")
    public ResponseEntity<?> stop() {
        loadTest.stop();
        return ResponseEntity.ok(Map.of("ok", true, "status", loadTest.status()));
    }

    @GetMapping("/status")
    public LoadTest.Status status() {
        return loadTest.status();
    }
}
