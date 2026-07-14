package com.naztech.oms.controller;

import com.naztech.oms.api.Dtos.*;
import com.naztech.oms.entity.Security;
import com.naztech.oms.entity.Trade;
import com.naztech.oms.repo.SecurityRepo;
import com.naztech.oms.repo.TradeRepo;
import com.naztech.oms.service.DepthBroadcaster;
import com.naztech.oms.service.MarketDataGateway;
import com.naztech.oms.service.MarketDataService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/market")
public class MarketDataController {

    private final MarketDataService market;
    private final MarketDataGateway depthGateway;
    private final DepthBroadcaster depthBroadcaster;
    private final TradeRepo tradeRepo;
    private final SecurityRepo securityRepo;

    public MarketDataController(MarketDataService market, MarketDataGateway depthGateway,
                               DepthBroadcaster depthBroadcaster, TradeRepo tradeRepo,
                               SecurityRepo securityRepo) {
        this.market = market;
        this.depthGateway = depthGateway;
        this.depthBroadcaster = depthBroadcaster;
        this.tradeRepo = tradeRepo;
        this.securityRepo = securityRepo;
    }

    @GetMapping("/watch")
    public List<MarketRow> watch(@RequestParam(defaultValue = "DSE") String exchange) {
        return market.watch(exchange, false);
    }

    @GetMapping("/movers")
    public Map<String, List<MarketRow>> movers(@RequestParam(defaultValue = "DSE") String exchange) {
        return market.movers(exchange);
    }

    @GetMapping("/indices")
    public List<MarketRow> indices() { return market.indices(); }

    /**
     * One instrument's live price, by ticker.
     *
     * <p>This exists because the AI voice assistant was answering "what is Grameenphone trading at?" by
     * downloading the <em>entire</em> 402-instrument board — 128 KB — and scanning it for one row. On a
     * written page nobody notices. In a spoken conversation that pause is the whole experience: the
     * model, left with dead air, fills it — <em>"give me a moment, I'll check and tell you"</em> — and a
     * dealer who has to wait to be told a price they can already see is a dealer who stops asking.
     */
    @GetMapping("/quote")
    public ResponseEntity<?> quote(@RequestParam String symbol) {
        Security s = securityRepo.findFirstBySymbolIgnoreCase(symbol == null ? "" : symbol.trim())
                .orElse(null);
        if (s == null) {
            return ResponseEntity.status(404).body(Map.of(
                    "error", "No instrument with the ticker '" + symbol + "' is listed on the DSE or CSE."));
        }
        return ResponseEntity.ok(market.quote(s));
    }

    @GetMapping("/{securityId}/depth")
    public Depth depth(@PathVariable Long securityId,
                       @RequestParam(defaultValue = "5") int levels) {
        return depthGateway.depth(securityId, levels);
    }

    /**
     * "I am looking at this book — push it to me." The terminal calls this when it selects an
     * instrument and periodically while it stays selected; updates then arrive on the SSE stream as
     * {@code depth} events instead of being polled for. Returns the book straight away so there is
     * something to draw before the first push.
     */
    @PostMapping("/{securityId}/depth/watch")
    public Map<String, Object> watchDepth(@PathVariable Long securityId,
                                          @RequestParam(defaultValue = "10") int levels) {
        return depthBroadcaster.watch(securityId, levels);
    }

    /** The book and the sequence number it is current as of — how a client re-syncs after a gap. */
    @GetMapping("/{securityId}/depth/snapshot")
    public Map<String, Object> depthSnapshot(@PathVariable Long securityId,
                                             @RequestParam(defaultValue = "10") int levels) {
        return depthBroadcaster.snapshot(securityId, levels);
    }

    @GetMapping("/{securityId}/candles")
    public List<com.naztech.oms.api.Dtos.Candle> candles(@PathVariable Long securityId,
                                                         @RequestParam(defaultValue = "5m") String tf,
                                                         @RequestParam(defaultValue = "120") int limit) {
        int bucket = switch (tf) {
            case "1m" -> 60; case "5m" -> 300; case "15m" -> 900; case "1h" -> 3600;
            case "1d" -> 86400; default -> 300;
        };
        return market.candles(securityId, bucket, limit);
    }

    @GetMapping("/{securityId}/trades")
    public List<TradeTick> trades(@PathVariable Long securityId) {
        Security s = securityRepo.findById(securityId).orElse(null);
        String sym = s == null ? "?" : s.getSymbol();
        return tradeRepo.findTop100BySecurityIdOrderByExecutedAtDesc(securityId).stream()
                .map(t -> new TradeTick(securityId, sym, t.getPrice(), t.getQuantity(),
                        t.getAggressorSide(), t.getExecutedAt() == null ? null : t.getExecutedAt().toString()))
                .collect(Collectors.toList());
    }

    @GetMapping("/tape")
    public List<TradeTick> tape() {
        Map<Long, Security> secs = securityRepo.findAll().stream()
                .collect(Collectors.toMap(Security::getId, x -> x));
        return tradeRepo.findTop200ByOrderByExecutedAtDesc().stream()
                .map(t -> {
                    Security s = secs.get(t.getSecurityId());
                    return new TradeTick(t.getSecurityId(), s == null ? "?" : s.getSymbol(),
                            t.getPrice(), t.getQuantity(), t.getAggressorSide(),
                            t.getExecutedAt() == null ? null : t.getExecutedAt().toString());
                })
                .collect(Collectors.toList());
    }

    /** Ingest endpoint for the Python feed (bdshare / simulator). */
    @PostMapping("/ingest")
    public Map<String, Object> ingest(@RequestBody IngestRequest req) {
        int n = market.ingest(req.quotes());
        return Map.of("ingested", n);
    }
}
