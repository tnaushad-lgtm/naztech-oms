package com.naztech.oms.controller;

import com.naztech.oms.api.Dtos.OrderRequest;
import com.naztech.oms.entity.ClientAccount;
import com.naztech.oms.entity.Security;
import com.naztech.oms.exchange.config.ExchangeProperties;
import com.naztech.oms.exchange.config.FixProperties;
import com.naztech.oms.exchange.config.ItchProperties;
import com.naztech.oms.exchange.fix.FixSessionState;
import com.naztech.oms.repo.ClientAccountRepo;
import com.naztech.oms.repo.SecurityRepo;
import com.naztech.oms.service.MarketDataGateway;
import com.naztech.oms.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;
import quickfix.Session;
import quickfix.SessionID;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Exchange-connectivity control plane for the Admin UI (Phase 6): live FIX/ITCH session status,
 * reconnect, a one-click test order, and the raw FIX message log. Read-only status works in any mode;
 * reconnect/test-order act only when a FIX session is present.
 */
@RestController
@RequestMapping("/api/admin/connectivity")
public class ConnectivityController {

    private static final Logger log = LoggerFactory.getLogger(ConnectivityController.class);
    private static final char SOH = (char) 1;

    private final ExchangeProperties exchange;
    private final FixProperties fix;
    private final ItchProperties itch;
    private final FixSessionState fixState;
    private final MarketDataGateway marketDataGateway;
    private final OrderService orderService;
    private final ClientAccountRepo accountRepo;
    private final SecurityRepo securityRepo;

    public ConnectivityController(ExchangeProperties exchange, FixProperties fix, ItchProperties itch,
                                  FixSessionState fixState, MarketDataGateway marketDataGateway,
                                  OrderService orderService, ClientAccountRepo accountRepo, SecurityRepo securityRepo) {
        this.exchange = exchange;
        this.fix = fix;
        this.itch = itch;
        this.fixState = fixState;
        this.marketDataGateway = marketDataGateway;
        this.orderService = orderService;
        this.accountRepo = accountRepo;
        this.securityRepo = securityRepo;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> fixMap = new LinkedHashMap<>();
        fixMap.put("enabled", fix.isEnabled());
        fixMap.put("configured", fix.isConfigured());
        fixMap.put("loggedOn", fixState.isLoggedOn());
        fixMap.put("sessionId", fixState.getSessionId());
        fixMap.put("lastEvent", fixState.getLastEvent());
        fixMap.put("lastEventAt", fixState.getLastEventAt());
        fixMap.put("lastHeartbeatAt", fixState.getLastHeartbeatAt());
        fixMap.put("nextSenderSeq", fixState.getNextSenderMsgSeqNum());
        fixMap.put("nextTargetSeq", fixState.getNextTargetMsgSeqNum());
        fixMap.put("lastInMsgType", fixState.getLastInMsgType());
        fixMap.put("lastOutMsgType", fixState.getLastOutMsgType());
        fixMap.put("host", fix.getHost());
        fixMap.put("port", fix.getPort());
        fixMap.put("senderCompId", fix.getSenderCompId());
        fixMap.put("targetCompId", fix.getTargetCompId());
        fixMap.put("beginString", fix.getBeginString());
        fixMap.put("applVerId", fix.getDefaultApplVerId());

        Map<String, Object> itchMap = new LinkedHashMap<>();
        itchMap.put("enabled", itch.isEnabled());
        itchMap.put("transport", itch.getTransport());
        itchMap.put("depthSource", marketDataGateway.source());

        // Feed health, but only a live transport has any: the simulator and a replay cannot lose a
        // message, so reporting "0 gaps" for them would be a claim about nothing.
        if (marketDataGateway instanceof com.naztech.oms.exchange.itch.ItchGateway gw) {
            var health = gw.feedHealth();
            if (health != null) {
                Map<String, Object> feed = new LinkedHashMap<>();
                feed.put("expectedSeq", health.expected());
                feed.put("delivered", health.delivered());
                feed.put("duplicates", health.duplicates());
                feed.put("gapsDetected", health.gapsDetected());
                feed.put("gapsRecovered", health.gapsRecovered());
                feed.put("lost", health.lost());
                feed.put("buffered", health.buffered());
                feed.put("healthy", health.healthy());
                // Liveness by heartbeat, not by message volume: a quiet market is still a live feed.
                long idleMs = gw.feedIdleMs();
                feed.put("idleMs", idleMs);
                feed.put("live", idleMs >= 0 && idleMs < 8000);   // a packet within 8s = the venue is there
                feed.put("session", gw.feedSession());            // venue session id, for the header
                feed.put("seq", Math.max(0, health.expected() - 1));   // the last sequence we applied
                itchMap.put("feed", feed);
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", exchange.getMode());
        out.put("fix", fixMap);
        out.put("itch", itchMap);
        return out;
    }

    @PostMapping("/reconnect")
    public Map<String, Object> reconnect() {
        try {
            Session s = Session.lookupSession(sessionId());
            if (s == null) return Map.of("ok", false, "message", "No FIX session (fix.enabled=false or not started).");
            s.logon();
            return Map.of("ok", true, "message", "Logon requested for " + s.getSessionID());
        } catch (Exception e) {
            log.warn("reconnect failed: {}", e.toString());
            return Map.of("ok", false, "message", e.toString());
        }
    }

    /** Place a small canned order — routes over FIX in dse-cert/prod, or to the simulator otherwise. */
    @PostMapping("/test-order")
    public Map<String, Object> testOrder() {
        try {
            ClientAccount acc = accountRepo.findAll().stream().findFirst().orElse(null);
            Security sec = securityRepo.findAll().stream()
                    .filter(s -> "ACTIVE".equals(s.getStatus()) && !"INDEX".equals(s.getAssetClass()))
                    .findFirst().orElse(null);
            if (acc == null || sec == null) return Map.of("ok", false, "message", "No account/security available.");

            OrderRequest req = new OrderRequest(acc.getId(), sec.getId(), "BUY", "LIMIT", "NORMAL", "DAY",
                    null, java.math.BigDecimal.valueOf(1), null, 10L, null);   // tiny, low price → rests
            OrderService.PlaceResult res = orderService.place(req, "connectivity-test");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", res.risk().pass());
            out.put("symbol", sec.getSymbol());
            out.put("orderRef", res.order().orderRef());
            out.put("status", res.order().status());
            out.put("routedVia", exchange.getMode());
            return out;
        } catch (Exception e) {
            log.warn("test-order failed: {}", e.toString());
            return Map.of("ok", false, "message", e.toString());
        }
    }

    /** Raw FIX message + event logs (for the "Download logs" button). */
    @GetMapping("/logs")
    public Map<String, Object> logs() {
        String base = "./fixlog/" + fix.getBeginString() + "-" + fix.getSenderCompId() + "-" + fix.getTargetCompId();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("messages", tail(base + ".messages.log", 300));
        out.put("events", tail(base + ".event.log", 200));
        return out;
    }

    private String tail(String path, int maxLines) {
        try {
            Path p = Path.of(path);
            if (!Files.exists(p)) return "(no log file yet)";
            List<String> lines = Files.readAllLines(p);
            int from = Math.max(0, lines.size() - maxLines);
            // show the FIX SOH (0x01) delimiter as a visible separator
            return String.join("\n", lines.subList(from, lines.size())).replace(SOH, '|');
        } catch (Exception e) {
            return "(could not read log: " + e + ")";
        }
    }

    private SessionID sessionId() {
        return new SessionID(fix.getBeginString(), fix.getSenderCompId(), fix.getTargetCompId());
    }
}
