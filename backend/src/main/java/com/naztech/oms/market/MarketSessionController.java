package com.naztech.oms.market;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Market-session control plane: Start / Open / Halt / Resume / Close / Reset.
 *
 * <p>DSE trades 10:00–14:30 Asia/Dhaka. Until the OMS is connected to the real exchange, the session
 * is driven from here — which is also how it will be tested afterwards, since a controlled open and
 * close is the only way to exercise the day's edges (pre-open book building, a mid-session halt, the
 * expiry of unfilled day orders at the close) without waiting for a Tuesday morning.
 */
@RestController
@RequestMapping("/api/admin/session")
public class MarketSessionController {

    private static final Logger log = LoggerFactory.getLogger(MarketSessionController.class);

    private final MarketSessionService session;

    public MarketSessionController(MarketSessionService session) {
        this.session = session;
    }

    @GetMapping("/status")
    public Map<String, Object> status(@RequestParam(defaultValue = "DSE") String exchange) {
        Map<String, Object> out = new LinkedHashMap<>(session.status(exchange));
        out.put("exchanges", session.exchanges());
        return out;
    }

    /** Start the trading day. Defaults to PRE_OPEN — the book builds, nothing crosses. */
    @PostMapping("/start")
    public Map<String, Object> start(@RequestParam(defaultValue = "DSE") String exchange,
                                     @RequestParam(defaultValue = "false") boolean straightToOpen,
                                     @RequestHeader(value = "X-Actor", defaultValue = "exchange-admin") String actor) {
        return act(() -> session.start(exchange, straightToOpen, actor));
    }

    /** The opening bell: PRE_OPEN → OPEN. Resting orders may now trade. */
    @PostMapping("/open")
    public Map<String, Object> open(@RequestParam(defaultValue = "DSE") String exchange,
                                    @RequestHeader(value = "X-Actor", defaultValue = "exchange-admin") String actor) {
        return act(() -> session.open(exchange, actor));
    }

    @PostMapping("/halt")
    public Map<String, Object> halt(@RequestParam(defaultValue = "DSE") String exchange,
                                    @RequestParam(required = false) String reason,
                                    @RequestHeader(value = "X-Actor", defaultValue = "exchange-admin") String actor) {
        return act(() -> session.halt(exchange, reason, actor));
    }

    @PostMapping("/resume")
    public Map<String, Object> resume(@RequestParam(defaultValue = "DSE") String exchange,
                                      @RequestHeader(value = "X-Actor", defaultValue = "exchange-admin") String actor) {
        return act(() -> session.resume(exchange, actor));
    }

    /** Close the day: unfilled DAY orders expire, the feed stops. */
    @PostMapping("/close")
    public Map<String, Object> close(@RequestParam(defaultValue = "DSE") String exchange,
                                     @RequestHeader(value = "X-Actor", defaultValue = "exchange-admin") String actor) {
        return act(() -> session.close(exchange, actor));
    }

    /** Reset for another run: closes the market and tears the books down, ready for a fresh Start. */
    @PostMapping("/reset")
    public Map<String, Object> reset(@RequestParam(defaultValue = "DSE") String exchange,
                                     @RequestHeader(value = "X-Actor", defaultValue = "exchange-admin") String actor) {
        return act(() -> session.reset(exchange, actor));
    }

    /** House style: control-plane actions report failure, they do not throw it at the browser. */
    private Map<String, Object> act(java.util.function.Supplier<Map<String, Object>> action) {
        try {
            Map<String, Object> out = new LinkedHashMap<>(action.get());
            out.put("ok", true);
            return out;
        } catch (Exception e) {
            log.warn("market session action failed: {}", e.toString());
            return Map.of("ok", false, "message", e.toString());
        }
    }
}
