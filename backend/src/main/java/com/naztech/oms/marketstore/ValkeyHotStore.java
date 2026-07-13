package com.naztech.oms.marketstore;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.naztech.oms.api.Dtos.Depth;
import com.naztech.oms.exchange.config.HotStoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

/**
 * The live market picture in Valkey (or Redis, or Memurai — one protocol, three names).
 *
 * <p>Quotes are hashes keyed {@code oms:quote:{securityId}}; depth ladders are JSON at
 * {@code oms:depth:{securityId}}; ticks fan out on a pub/sub channel so every OMS instance sees them,
 * not just the one that produced them.
 *
 * <p><b>It is a cache, and it is treated like one.</b> Nothing here is a system of record: every value
 * is derivable from MySQL and the feed. So a Valkey outage degrades the OMS — it does not stop it.
 * Reads fall through to MySQL, writes are dropped, orders keep trading. That is why every call here
 * swallows its exception and returns empty rather than throwing into a caller that is in the middle
 * of booking a fill.
 */
public class ValkeyHotStore implements HotStore {

    private static final Logger log = LoggerFactory.getLogger(ValkeyHotStore.class);
    private static final String QUOTE = "oms:quote:";
    private static final String DEPTH = "oms:depth:";

    private final JedisPool pool;
    private final HotStoreProperties props;
    private final ObjectMapper json = new ObjectMapper();
    private volatile boolean live;

    public ValkeyHotStore(HotStoreProperties props) {
        this.props = props;

        JedisPoolConfig cfg = new JedisPoolConfig();
        cfg.setMaxTotal(props.getMaxConnections());
        cfg.setMaxIdle(props.getMaxConnections());
        cfg.setMinIdle(1);
        cfg.setTestOnBorrow(false);              // this is a hot path; a PING per borrow is not free
        this.pool = new JedisPool(cfg, props.getHost(), props.getPort(), props.getTimeoutMs(),
                props.getPassword() == null || props.getPassword().isBlank() ? null : props.getPassword());

        this.live = ping();
    }

    private boolean ping() {
        try (var j = pool.getResource()) {
            j.ping();
            log.info("Hot store connected: {}:{} — quotes, depth and tick fan-out are now in Valkey",
                    props.getHost(), props.getPort());
            return true;
        } catch (Exception e) {
            log.warn("Hot store not reachable at {}:{} ({}) — the OMS falls back to MySQL for quotes and "
                            + "to the in-JVM book for depth. Trading is unaffected.",
                    props.getHost(), props.getPort(), e.toString());
            return false;
        }
    }

    @Override
    public void putQuote(Long securityId, Quote q) {
        if (!live || securityId == null) {
            return;
        }
        try (var j = pool.getResource()) {
            j.hset(QUOTE + securityId, Map.of(
                    "ltp", str(q.ltp()),
                    "bid", str(q.bid()),
                    "ask", str(q.ask()),
                    "volume", String.valueOf(q.volume()),
                    "trades", String.valueOf(q.trades())));
        } catch (Exception e) {
            degrade("putQuote", e);
        }
    }

    @Override
    public Optional<Quote> quote(Long securityId) {
        if (!live || securityId == null) {
            return Optional.empty();
        }
        try (var j = pool.getResource()) {
            Map<String, String> h = j.hgetAll(QUOTE + securityId);
            if (h == null || h.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new Quote(dec(h.get("ltp")), dec(h.get("bid")), dec(h.get("ask")),
                    lng(h.get("volume")), lng(h.get("trades"))));
        } catch (Exception e) {
            degrade("quote", e);
            return Optional.empty();
        }
    }

    @Override
    public void putDepth(Long securityId, Depth depth) {
        if (!live || securityId == null || depth == null) {
            return;
        }
        try (var j = pool.getResource()) {
            // Depth is worthless the moment it is stale, so it expires on its own rather than
            // lingering to be served as if it were current after the feed has stopped.
            j.setex(DEPTH + securityId, props.getDepthTtlSeconds(), json.writeValueAsString(depth));
        } catch (Exception e) {
            degrade("putDepth", e);
        }
    }

    @Override
    public Optional<Depth> depth(Long securityId) {
        if (!live || securityId == null) {
            return Optional.empty();
        }
        try (var j = pool.getResource()) {
            String s = j.get(DEPTH + securityId);
            return s == null ? Optional.empty() : Optional.of(json.readValue(s, Depth.class));
        } catch (Exception e) {
            degrade("depth", e);
            return Optional.empty();
        }
    }

    @Override
    public void publish(String channel, String payload) {
        if (!live) {
            return;
        }
        try (var j = pool.getResource()) {
            j.publish(channel, payload);
        } catch (Exception e) {
            degrade("publish", e);
        }
    }

    @Override
    public boolean isLive() {
        return live;
    }

    @Override
    public String describe() {
        return "Valkey/Redis " + props.getHost() + ":" + props.getPort() + (live ? " (connected)" : " (unreachable)");
    }

    /** One log line per failure class, not one per tick — a dead cache must not also flood the log. */
    private void degrade(String op, Exception e) {
        if (live) {
            live = false;
            log.warn("Hot store {} failed ({}) — degrading to MySQL. Trading is unaffected.", op, e.toString());
        }
    }

    /** Periodic reconnect: a cache that went away should come back on its own. */
    public void recheck() {
        if (!live) {
            live = ping();
        }
    }

    private static String str(BigDecimal v) {
        return v == null ? "" : v.toPlainString();
    }

    private static BigDecimal dec(String v) {
        return v == null || v.isBlank() ? null : new BigDecimal(v);
    }

    private static long lng(String v) {
        try {
            return v == null || v.isBlank() ? 0 : Long.parseLong(v);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @PreDestroy
    public void close() {
        pool.close();
    }
}
