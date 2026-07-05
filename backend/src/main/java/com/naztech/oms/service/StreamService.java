package com.naztech.oms.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Server-Sent-Events hub. The terminal subscribes once to {@code /api/stream}; market ticks, trades
 * and order-status changes are pushed to every client.
 *
 * <p><b>Coalescing (Phase 7):</b> a real DSE ITCH feed can emit &gt;10k msg/sec, which would flood the
 * browser and freeze the UI if each was pushed individually. With {@code app.stream.coalesce=true},
 * high-frequency events are buffered and flushed on a short timer — {@code market}/{@code indices}
 * last-value-wins, {@code trade} rate-capped — while {@code order} updates always go out immediately.
 * Default is off, so the demo behaves exactly as before unless coalescing is switched on.
 */
@Service
public class StreamService {

    private static final Logger log = LoggerFactory.getLogger(StreamService.class);
    private static final Set<String> LATEST_WINS = Set.of("market", "indices");
    private static final Set<String> IMMEDIATE = Set.of("order", "hello");
    private static final int MAX_TRADES_PER_FLUSH = 25;
    private static final int MAX_TRADE_BACKLOG = 500;

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final ObjectMapper json = new ObjectMapper();

    // coalescing buffers
    private final Map<String, String> latest = new ConcurrentHashMap<>();   // event → latest json
    private final Queue<String[]> tradeBuf = new ConcurrentLinkedQueue<>(); // [event, json]

    @Value("${app.stream.coalesce:false}")
    private volatile boolean coalesce;

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L); // no timeout
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        try {
            emitter.send(SseEmitter.event().name("hello").data(Map.of("ok", true, "clients", emitters.size())));
        } catch (IOException ignored) {}
        return emitter;
    }

    public void publish(String event, Object payload) {
        if (!coalesce || IMMEDIATE.contains(event)) {   // immediate path (default, and always for order/hello)
            String data = toJson(payload);
            if (data != null) sendData(event, data);
            return;
        }
        String data = toJson(payload);
        if (data == null) return;
        if (LATEST_WINS.contains(event)) {
            latest.put(event, data);                     // collapse to the most recent
        } else if ("trade".equals(event)) {
            if (tradeBuf.size() < MAX_TRADE_BACKLOG) tradeBuf.add(new String[]{event, data});
        } else {
            sendData(event, data);                        // unknown high-freq type → send through
        }
    }

    /** Flush the coalesced buffers on a short cadence (only does work when coalescing is on). */
    @Scheduled(fixedDelay = 60)
    public void flush() {
        for (String[] ev : drain()) sendData(ev[0], ev[1]);
    }

    /** What the next flush would emit: coalesced latest-wins events + a capped batch of trades. */
    List<String[]> drain() {
        List<String[]> out = new ArrayList<>();
        for (String ev : LATEST_WINS) {
            String d = latest.remove(ev);
            if (d != null) out.add(new String[]{ev, d});
        }
        int n = 0;
        String[] t;
        while (n < MAX_TRADES_PER_FLUSH && (t = tradeBuf.poll()) != null) { out.add(t); n++; }
        return out;
    }

    private void sendData(String event, String data) {
        for (SseEmitter em : emitters) {
            try {
                em.send(SseEmitter.event().name(event).data(data));
            } catch (Exception e) {
                emitters.remove(em);
            }
        }
    }

    private String toJson(Object payload) {
        try {
            return json.writeValueAsString(payload);
        } catch (Exception e) {
            return null;
        }
    }

    public int clientCount() { return emitters.size(); }

    // test seams
    void setCoalesceForTest(boolean v) { this.coalesce = v; }
    int tradeBacklog() { return tradeBuf.size(); }
}
