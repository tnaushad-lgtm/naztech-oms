package com.naztech.oms.market;

import com.naztech.oms.entity.Exchange;
import com.naztech.oms.exchange.itch.ItchGateway;
import com.naztech.oms.repo.ExchangeRepo;
import com.naztech.oms.repo.OmsOrderRepo;
import com.naztech.oms.service.AuditService;
import com.naztech.oms.service.StreamService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns the trading day: which phase each exchange is in, and everything that follows from it.
 *
 * <p>Real DSE runs 10:00–14:30 Asia/Dhaka. In development we cannot dial the exchange, so the phase
 * is driven by hand — Start / Halt / Resume / Close / Reset — and the OMS behaves exactly as it
 * would against the real venue: outside the session no order is accepted, nothing matches, and no
 * market data flows. Set {@code market.auto-schedule=true} and the clock drives it instead.
 *
 * <h2>Where the state lives</h2>
 * In a {@code volatile} field, written through to {@code exchange.status} so it survives a restart.
 * It is deliberately NOT in {@code RefDataCache}: that cache refuses to hold the broker kill-switch
 * because a stale trading gate is the one thing a risk system may never have, and the market session
 * is the same class of control — only more so, since it is checked before everything else. Reading a
 * field costs nothing, which matters because {@code RiskService.check} is the hot path.
 *
 * <h2>What each phase does</h2>
 * <pre>
 *   CLOSED    orders rejected · no matching · feed down
 *   PRE_OPEN  orders accepted and rest on the book · NO matching · feed live   (the book builds)
 *   OPEN      orders accepted · matching · feed live                            (continuous trading)
 *   HALTED    orders rejected · no matching · feed frozen, books stand          (cancels stay legal)
 * </pre>
 */
@Service
public class MarketSessionService {

    private static final Logger log = LoggerFactory.getLogger(MarketSessionService.class);
    private static final ZoneId DHAKA = ZoneId.of("Asia/Dhaka");
    private static final List<String> WORKING = List.of("OPEN", "PARTIAL");

    private final ExchangeRepo exchangeRepo;
    private final OmsOrderRepo orderRepo;
    private final ObjectProvider<ItchGateway> itch;   // absent when itch.enabled=false
    private final StreamService stream;
    private final AuditService audit;

    /** exchangeId → phase. The authority; the DB row is its durable shadow. */
    private final Map<Long, MarketSession> phases = new ConcurrentHashMap<>();
    private final Map<Long, LocalDateTime> since = new ConcurrentHashMap<>();
    private final Map<Long, String> reasons = new ConcurrentHashMap<>();

    @Value("${market.auto-schedule:false}")
    private boolean autoSchedule;

    public MarketSessionService(ExchangeRepo exchangeRepo, OmsOrderRepo orderRepo,
                                ObjectProvider<ItchGateway> itch, StreamService stream, AuditService audit) {
        this.exchangeRepo = exchangeRepo;
        this.orderRepo = orderRepo;
        this.itch = itch;
        this.stream = stream;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- the gates (hot path)

    /** The phase of the exchange this security trades on. Unknown exchange → CLOSED (fail safe). */
    public MarketSession phase(Long exchangeId) {
        if (exchangeId == null) {
            return MarketSession.CLOSED;
        }
        return phases.getOrDefault(exchangeId, MarketSession.CLOSED);
    }

    /** Why an order cannot be placed right now, or null if it can. Used by pre-trade risk. */
    public String blockReason(Long exchangeId) {
        MarketSession p = phase(exchangeId);
        if (p.acceptsOrders()) {
            return null;
        }
        return p == MarketSession.HALTED
                ? "Market is halted — new orders are not accepted (cancels are still allowed)"
                : "Market is closed — DSE trades 10:00–14:30 (Asia/Dhaka)";
    }

    /** False in PRE_OPEN: orders rest on the book but do not cross until the opening bell. */
    public boolean allowsMatching(Long exchangeId) {
        return phase(exchangeId).allowsMatching();
    }

    /** Is any exchange trading? Used to idle the simulator's background tick when nothing is open. */
    public boolean anyOpen() {
        return phases.values().stream().anyMatch(MarketSession::allowsMatching);
    }

    // ---------------------------------------------------------------- transitions

    /** Start the day. Goes through PRE_OPEN unless {@code straightToOpen}. */
    @Transactional
    public Map<String, Object> start(String code, boolean straightToOpen, String actor) {
        Exchange x = exchange(code);
        transition(x, straightToOpen ? MarketSession.OPEN : MarketSession.PRE_OPEN, actor, "Market started");
        itch.ifAvailable(ItchGateway::openMarket);       // day-start broadcast, then the feed
        return status(code);
    }

    /** PRE_OPEN → OPEN: the opening bell. Resting orders may now cross. */
    @Transactional
    public Map<String, Object> open(String code, String actor) {
        Exchange x = exchange(code);
        transition(x, MarketSession.OPEN, actor, "Continuous trading");
        itch.ifAvailable(g -> {
            g.openMarket();                             // no-op if already open
            g.haltFeed(false);
        });
        return status(code);
    }

    /** Halt mid-session. Orders are refused; the book stands; cancels remain legal. */
    @Transactional
    public Map<String, Object> halt(String code, String reason, String actor) {
        Exchange x = exchange(code);
        transition(x, MarketSession.HALTED, actor, reason == null || reason.isBlank() ? "Market halted" : reason);
        itch.ifAvailable(g -> g.haltFeed(true));
        return status(code);
    }

    /** Resume after a halt. */
    @Transactional
    public Map<String, Object> resume(String code, String actor) {
        Exchange x = exchange(code);
        transition(x, MarketSession.OPEN, actor, "Trading resumed");
        itch.ifAvailable(g -> g.haltFeed(false));
        return status(code);
    }

    /**
     * Close the day. DAY orders left working are expired, as they are at a real close — leaving them
     * OPEN would have them silently spring back to life at the next open, which is not what a client
     * who placed a day order asked for.
     */
    @Transactional
    public Map<String, Object> close(String code, String actor) {
        Exchange x = exchange(code);
        int expired = expireDayOrders(x.getId());
        transition(x, MarketSession.CLOSED, actor, "Market closed — " + expired + " day order(s) expired");
        itch.ifAvailable(ItchGateway::closeMarket);
        return status(code);
    }

    /**
     * Reset for another test run: close the market and clear the day. Same as {@link #close} — the
     * feed's books are torn down and rebuilt on the next Start, so the next session begins from a
     * clean slate rather than yesterday's depth.
     */
    @Transactional
    public Map<String, Object> reset(String code, String actor) {
        Exchange x = exchange(code);
        int expired = expireDayOrders(x.getId());
        transition(x, MarketSession.CLOSED, actor, "Session reset — " + expired + " working order(s) expired");
        itch.ifAvailable(ItchGateway::closeMarket);
        log.info("Market session reset for {} — press Start Market to begin a new session", x.getCode());
        return status(code);
    }

    // ---------------------------------------------------------------- status

    public Map<String, Object> status(String code) {
        Exchange x = exchange(code);
        MarketSession p = phase(x.getId());
        LocalDateTime now = LocalDateTime.now(DHAKA);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("exchange", x.getCode());
        out.put("state", p.name());
        out.put("acceptsOrders", p.acceptsOrders());
        out.put("matching", p.allowsMatching());
        out.put("feedLive", p.feedLive());
        out.put("openTime", String.valueOf(x.getOpenTime()));
        out.put("closeTime", String.valueOf(x.getCloseTime()));
        out.put("now", now.toLocalTime().withNano(0).toString());
        out.put("withinHours", withinHours(x, now.toLocalTime()));
        out.put("autoSchedule", autoSchedule);
        out.put("since", String.valueOf(since.get(x.getId())));
        out.put("reason", reasons.getOrDefault(x.getId(), ""));
        return out;
    }

    public List<String> exchanges() {
        return exchangeRepo.findAll().stream().map(Exchange::getCode).toList();
    }

    // ---------------------------------------------------------------- lifecycle

    /**
     * Restore the phase persisted in {@code exchange.status}. A market that was open when the process
     * died comes back open — and brings its feed back up with it.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void restore() {
        boolean anyLive = false;
        for (Exchange x : exchangeRepo.findAll()) {
            MarketSession p = parse(x.getStatus());
            phases.put(x.getId(), p);
            since.put(x.getId(), LocalDateTime.now(DHAKA));
            anyLive |= p.feedLive();
            log.info("Market session restored: {} is {}", x.getCode(), p);
        }
        if (anyLive) {
            itch.ifAvailable(ItchGateway::openMarket);
        } else {
            log.info("All markets are CLOSED — no orders will be accepted until Start Market. "
                    + "(Exchange Admin → Market Session)");
        }
    }

    /**
     * Optional clock-driven schedule ({@code market.auto-schedule=true}). Off by default: in
     * development the buttons are the point, and a market that opens itself at 10:00 in the middle of
     * a controlled test is a nuisance, not a feature.
     */
    @Scheduled(fixedDelay = 30_000)
    public void followTheClock() {
        if (!autoSchedule) {
            return;
        }
        LocalTime now = LocalTime.now(DHAKA);
        for (Exchange x : exchangeRepo.findAll()) {
            MarketSession p = phase(x.getId());
            boolean shouldTrade = withinHours(x, now);
            if (shouldTrade && p == MarketSession.CLOSED) {
                start(x.getCode(), true, "auto-schedule");
            } else if (!shouldTrade && p != MarketSession.CLOSED) {
                close(x.getCode(), "auto-schedule");
            }
        }
    }

    // ---------------------------------------------------------------- internals

    private void transition(Exchange x, MarketSession to, String actor, String reason) {
        MarketSession from = phase(x.getId());
        phases.put(x.getId(), to);
        since.put(x.getId(), LocalDateTime.now(DHAKA));
        reasons.put(x.getId(), reason);

        x.setStatus(to.name());                 // durable shadow of the authoritative field
        exchangeRepo.save(x);

        audit.audit(actor, "MARKET_" + to.name(), "EXCHANGE", String.valueOf(x.getId()),
                from + " → " + to + " (" + reason + ")");
        stream.publish("session", status(x.getCode()));
        log.info("MARKET {} → {} on {} by {} ({})", from, to, x.getCode(), actor, reason);
    }

    /** DAY orders do not survive the close. GTC/GTD orders do — that is the whole point of them. */
    private int expireDayOrders(Long exchangeId) {
        var working = orderRepo.findByExchangeIdAndStatusIn(exchangeId, WORKING);
        int n = 0;
        for (var o : working) {
            if (!"DAY".equalsIgnoreCase(o.getValidity())) {
                continue;
            }
            o.setStatus("EXPIRED");
            o.setUpdatedAt(LocalDateTime.now());
            orderRepo.save(o);
            n++;
        }
        return n;
    }

    private boolean withinHours(Exchange x, LocalTime now) {
        LocalTime open = x.getOpenTime() == null ? LocalTime.of(10, 0) : x.getOpenTime();
        LocalTime close = x.getCloseTime() == null ? LocalTime.of(14, 30) : x.getCloseTime();
        boolean weekend = switch (LocalDate.now(DHAKA).getDayOfWeek()) {
            case FRIDAY, SATURDAY -> true;              // the DSE trading week is Sunday–Thursday
            default -> false;
        };
        return !weekend && !now.isBefore(open) && now.isBefore(close);
    }

    private Exchange exchange(String code) {
        String c = code == null || code.isBlank() ? "DSE" : code.toUpperCase();
        return exchangeRepo.findAll().stream()
                .filter(e -> c.equals(e.getCode()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown exchange: " + c));
    }

    private static MarketSession parse(String status) {
        if (status == null) {
            return MarketSession.CLOSED;
        }
        try {
            return MarketSession.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            return MarketSession.CLOSED;    // an unrecognised status must not mean "trade freely"
        }
    }
}
