package com.naztech.oms.controller;

import com.naztech.oms.api.Dtos.AiHit;
import com.naztech.oms.api.Dtos.OrderRequest;
import com.naztech.oms.api.Dtos.RiskResult;
import com.naztech.oms.entity.ClientAccount;
import com.naztech.oms.entity.OmsOrder;
import com.naztech.oms.entity.Security;
import com.naztech.oms.repo.ClientAccountRepo;
import com.naztech.oms.repo.SecurityRepo;
import com.naztech.oms.service.AiOrderService;
import com.naztech.oms.service.RiskService;
import com.naztech.oms.service.SecuritySearchService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** On-prem AI endpoints: semantic security search + pre-trade risk preview. */
@RestController
@RequestMapping("/api/ai")
public class AiController {

    private final SecuritySearchService search;
    private final RiskService riskService;
    private final SecurityRepo securityRepo;
    private final ClientAccountRepo accountRepo;
    private final com.naztech.oms.service.AiAdvisorService advisor;
    private final AiOrderService aiOrder;

    public AiController(SecuritySearchService search, RiskService riskService,
                        SecurityRepo securityRepo, ClientAccountRepo accountRepo,
                        com.naztech.oms.service.AiAdvisorService advisor, AiOrderService aiOrder) {
        this.search = search;
        this.riskService = riskService;
        this.securityRepo = securityRepo;
        this.accountRepo = accountRepo;
        this.advisor = advisor;
        this.aiOrder = aiOrder;
    }

    public record AdvisorRequest(String message, Long accountId, String action,
                                 String imageBase64, String imageMime,
                                 java.util.List<java.util.Map<String, String>> history, String lang) {}

    @GetMapping("/tts")
    public ResponseEntity<byte[]> tts(@RequestParam String text, @RequestParam(defaultValue = "en") String lang,
                                      @RequestParam(defaultValue = "true") boolean hd) {
        var audio = advisor.tts(text, lang, hd);
        if (audio == null || audio.data().length == 0) return ResponseEntity.status(503).body(new byte[0]);
        return ResponseEntity.ok().header("Content-Type", audio.contentType()).body(audio.data());
    }

    @PostMapping("/advisor")
    public ResponseEntity<?> advisor(@RequestBody AdvisorRequest req) {
        try {
            var r = advisor.advise(req.message(), req.accountId(), req.action(),
                    req.imageBase64(), req.imageMime(), req.history(), req.lang());
            Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("answer", r.answer());
        out.put("source", r.source());
        out.put("ai", r.ai());
        if (r.route() != null) {
            out.put("route", r.route());   // "it is on this screen" — the UI renders it as a link
        }
        return ResponseEntity.ok(out);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of("ready", search.isReady(), "indexed", search.indexed(),
                "model", "all-MiniLM-L6-v2 (in-process, offline)");
    }

    @GetMapping("/search")
    public List<AiHit> search(@RequestParam String q,
                              @RequestParam(defaultValue = "35") double min,
                              @RequestParam(defaultValue = "12") int limit) {
        return search.search(q, min, limit);
    }

    /**
     * "Where do I find market depth?" — semantic search over the OMS's own screens, so a dealer can
     * ask for a feature in their own words instead of hunting the sidebar. Same on-prem model as the
     * security search: no key required, nothing leaves the exchange.
     */
    @GetMapping("/help")
    public List<com.naztech.oms.api.Dtos.NavHit> help(@RequestParam String q,
                                                      @RequestParam(defaultValue = "28") double min,
                                                      @RequestParam(defaultValue = "5") int limit) {
        return search.findFeature(q, min, limit);
    }

    /** Parse a natural-language / spoken instruction into structured orders (parsed only — the UI confirms). */
    @PostMapping("/order-intent")
    public Map<String, Object> orderIntent(@RequestBody Map<String, String> req) {
        String text = req == null ? "" : req.getOrDefault("text", "");
        if (text == null) text = "";
        return Map.of("text", text, "orders", aiOrder.parseSmart(text));
    }

    /** Risk + AI-score preview without placing the order. */
    @PostMapping("/risk-preview")
    public ResponseEntity<?> riskPreview(@RequestBody OrderRequest req) {
        try {
            Security sec = securityRepo.findById(req.securityId())
                    .orElseThrow(() -> new IllegalArgumentException("Unknown security"));
            ClientAccount acc = accountRepo.findById(req.accountId())
                    .orElseThrow(() -> new IllegalArgumentException("Unknown account"));
            OmsOrder o = new OmsOrder();
            o.setExchangeId(sec.getExchangeId());
            o.setBrokerId(acc.getBrokerId());
            o.setDealerId(req.dealerId());
            o.setAccountId(acc.getId());
            o.setSecurityId(sec.getId());
            o.setSide(req.side() == null ? null : req.side().toUpperCase());
            o.setOrderType(req.orderType() == null ? "LIMIT" : req.orderType().toUpperCase());
            o.setPrice(req.price() == null ? BigDecimal.ZERO : req.price());
            o.setStopPrice(req.stopPrice());
            o.setQuantity(req.quantity());
            o.setFilledQty(0L);
            RiskResult r = riskService.check(o);
            return ResponseEntity.ok(r);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
