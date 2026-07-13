package com.naztech.oms.marketstore;

import com.naztech.oms.api.Dtos.Candle;
import com.naztech.oms.exchange.config.TickStoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.annotation.PreDestroy;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Ticks into QuestDB: written over the InfluxDB line protocol (ILP), read back as candles over
 * QuestDB's HTTP/SQL endpoint.
 *
 * <p>Two deliberate choices worth defending:
 *
 * <p><b>No client library.</b> ILP is a line of text on a socket and the query endpoint is HTTP+JSON,
 * so this adds no dependency to the OMS — which matters when the same code has to run against
 * QuestDB on a Linux server, and when the alternative is dragging a driver into a build that already
 * pins its own versions.
 *
 * <p><b>Writes never block the caller.</b> Ticks are queued and drained by one background thread.
 * This sits on the fill path: an order that has just traded must not wait on a market-data write, and
 * if the tick store falls over, orders keep trading — we drop ticks, loudly, and carry on. The
 * system of record is MySQL; this store is for history and charts.
 */
public class QuestDbTickStore implements TickStore {

    private static final Logger log = LoggerFactory.getLogger(QuestDbTickStore.class);

    static final String TABLE = "market_tick";
    private static final int QUEUE_CAPACITY = 200_000;

    /** The tick table. WAL + daily partitions is what QuestDB wants for an append-only series. */
    private static final String DDL =
            "CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                    + "ts TIMESTAMP, security_id LONG, symbol SYMBOL, price DOUBLE, qty LONG"
                    + ") TIMESTAMP(ts) PARTITION BY DAY WAL";

    private record Tick(long securityId, String symbol, double price, long qty, long epochMillis) {}

    private final TickStoreProperties props;
    private final BlockingQueue<Tick> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicLong written = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private volatile boolean live;
    private volatile boolean running = true;
    private final Thread writer;

    public QuestDbTickStore(TickStoreProperties props) {
        this.props = props;
        this.live = createTable();
        this.writer = new Thread(this::drain, "questdb-tick-writer");
        this.writer.setDaemon(true);
        this.writer.start();
    }

    @Override
    public void tick(Long securityId, String symbol, BigDecimal price, long qty, long epochMillis) {
        if (!live || securityId == null || price == null) {
            return;
        }
        Tick t = new Tick(securityId, symbol == null ? "?" : symbol, price.doubleValue(), qty, epochMillis);
        if (!queue.offer(t) && dropped.incrementAndGet() % 1000 == 1) {
            log.warn("QuestDB tick queue is full — {} tick(s) dropped. Orders and fills are unaffected.",
                    dropped.get());
        }
    }

    /**
     * Candles straight from the ticks. This is the whole point of a time-series store: what took a
     * thousand-row fetch and a hand-rolled bucketing loop in Java is one SAMPLE BY, and it is exact
     * rather than a synthetic random walk when the tape is thin.
     */
    @Override
    public List<Candle> candles(Long securityId, int bucketSeconds, int limit) {
        if (!live) {
            return List.of();
        }
        String unit = bucketSeconds >= 86400 ? "d" : bucketSeconds >= 3600 ? "h" : "m";
        long every = switch (unit) {
            case "d" -> Math.max(1, bucketSeconds / 86400);
            case "h" -> Math.max(1, bucketSeconds / 3600);
            default -> Math.max(1, bucketSeconds / 60);
        };
        String sql = "SELECT ts, first(price) o, max(price) h, min(price) l, last(price) c, sum(qty) v "
                + "FROM " + TABLE + " WHERE security_id = " + securityId + " "
                + "SAMPLE BY " + every + unit + " ALIGN TO CALENDAR "
                + "ORDER BY ts DESC LIMIT " + Math.max(1, limit);
        try {
            String json = query(sql);
            return parseCandles(json);
        } catch (Exception e) {
            log.warn("QuestDB candle query failed ({}) — falling back to the trade table", e.toString());
            return List.of();
        }
    }

    @Override
    public boolean isLive() {
        return live;
    }

    @Override
    public String describe() {
        return "QuestDB " + props.getHost() + ":" + props.getIlpPort()
                + " (written=" + written.get() + ", dropped=" + dropped.get() + ")";
    }

    // ---------------------------------------------------------------- writer

    /**
     * One connection, drained continuously. Reconnects on failure — a market-data store going away
     * must never take the OMS with it.
     */
    private void drain() {
        while (running) {
            try (Socket s = new Socket(props.getHost(), props.getIlpPort());
                 BufferedWriter out = new BufferedWriter(
                         new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), 1 << 16)) {
                live = true;
                log.info("QuestDB tick writer connected to {}:{}", props.getHost(), props.getIlpPort());
                while (running) {
                    Tick t = queue.poll(200, TimeUnit.MILLISECONDS);
                    if (t == null) {
                        out.flush();                 // idle: get what we have on the wire
                        continue;
                    }
                    out.write(line(t));
                    written.incrementAndGet();
                    if (queue.isEmpty()) {
                        out.flush();
                    }
                }
                out.flush();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (IOException e) {
                live = false;
                log.warn("QuestDB tick writer lost its connection ({}) — retrying in 5s. "
                        + "Trading is unaffected; ticks in this window are lost.", e.toString());
                sleep();
            }
        }
    }

    /** ILP: {@code market_tick,symbol=GP security_id=7i,price=305.4,qty=100i <nanos>} */
    private static String line(Tick t) {
        return TABLE + ",symbol=" + escape(t.symbol())
                + " security_id=" + t.securityId() + "i"
                + ",price=" + t.price()
                + ",qty=" + t.qty() + "i"
                + " " + (t.epochMillis() * 1_000_000L) + "\n";
    }

    private static String escape(String tag) {
        return tag.replace(" ", "\\ ").replace(",", "\\,").replace("=", "\\=");
    }

    // ---------------------------------------------------------------- query

    private boolean createTable() {
        try {
            query(DDL);
            log.info("QuestDB ready at {}:{} — tick history and candles are live",
                    props.getHost(), props.getHttpPort());
            return true;
        } catch (Exception e) {
            log.warn("QuestDB is not reachable at {}:{} ({}) — the OMS will run without tick history, "
                            + "and candles fall back to the trade table.",
                    props.getHost(), props.getHttpPort(), e.toString());
            return false;
        }
    }

    private String query(String sql) throws IOException {
        URI uri = URI.create("http://" + props.getHost() + ":" + props.getHttpPort()
                + "/exec?query=" + URLEncoder.encode(sql, StandardCharsets.UTF_8));
        HttpURLConnection c = (HttpURLConnection) uri.toURL().openConnection();
        c.setConnectTimeout(2000);
        c.setReadTimeout(5000);
        try (var in = c.getResponseCode() < 400 ? c.getInputStream() : c.getErrorStream()) {
            String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            if (c.getResponseCode() >= 400) {
                throw new IOException("QuestDB " + c.getResponseCode() + ": " + body);
            }
            return body;
        } finally {
            c.disconnect();
        }
    }

    /** QuestDB returns {"dataset":[[ts,o,h,l,c,v], …]}. Small and fixed, so parse it directly. */
    private static List<Candle> parseCandles(String json) {
        List<Candle> out = new ArrayList<>();
        int ds = json.indexOf("\"dataset\"");
        if (ds < 0) {
            return out;
        }
        int start = json.indexOf('[', ds);
        int depth = 0;
        StringBuilder row = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (ch == '[') {
                depth++;
                if (depth == 2) row.setLength(0);
                continue;
            }
            if (ch == ']') {
                depth--;
                if (depth == 1) {
                    Candle c = parseRow(row.toString());
                    if (c != null) out.add(c);
                } else if (depth == 0) {
                    break;
                }
                continue;
            }
            if (depth >= 2) row.append(ch);
        }
        java.util.Collections.reverse(out);       // the query is DESC; charts want oldest first
        return out;
    }

    private static Candle parseRow(String row) {
        try {
            String[] f = row.split(",");
            if (f.length < 6) {
                return null;
            }
            String ts = f[0].trim().replace("\"", "");
            long epochSec = java.time.Instant.parse(ts.endsWith("Z") ? ts : ts + "Z").getEpochSecond();
            return new Candle(epochSec, dbl(f[1]), dbl(f[2]), dbl(f[3]), dbl(f[4]), (long) dbl(f[5]));
        } catch (Exception e) {
            return null;                          // a malformed row is not worth failing a chart over
        }
    }

    private static double dbl(String s) {
        String v = s.trim().replace("\"", "");
        return "null".equals(v) || v.isEmpty() ? 0 : Double.parseDouble(v);
    }

    private static void sleep() {
        try {
            Thread.sleep(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @PreDestroy
    public void stop() {
        running = false;
        writer.interrupt();
        log.info("QuestDB tick writer stopped ({} ticks written, {} dropped)", written.get(), dropped.get());
    }
}
