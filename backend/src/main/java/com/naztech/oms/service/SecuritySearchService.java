package com.naztech.oms.service;

import com.naztech.oms.api.Dtos.AiHit;
import com.naztech.oms.entity.Security;
import com.naztech.oms.repo.SecurityRepo;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Typo-tolerant, meaning-aware security search using an in-process all-MiniLM-L6-v2
 * model (LangChain4j). 100% offline — no ticker or order text ever leaves the
 * exchange, which is the right posture for capital-market infrastructure.
 *
 * <p>Mirrors the proven pattern in the sibling sanction-search project.
 */
@Service
public class SecuritySearchService {

    private static final Logger log = LoggerFactory.getLogger(SecuritySearchService.class);

    private final SecurityRepo securityRepo;
    private final EmbeddingModel model = new AllMiniLmL6V2EmbeddingModel();
    private final Map<Long, float[]> vectors = new ConcurrentHashMap<>();

    /**
     * The OMS's own screens, embedded with the same model — so "where do I find market depth?" is
     * answered by the same semantic search that finds a security. Sharing the model matters: each
     * AllMiniLmL6V2EmbeddingModel loads its own ONNX weights into the JVM, and one copy is enough.
     */
    private final Map<String, float[]> featureVectors = new ConcurrentHashMap<>();
    private final AtomicBoolean ready = new AtomicBoolean(false);

    public SecuritySearchService(SecurityRepo securityRepo) {
        this.securityRepo = securityRepo;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "sec-embed-warmup");
            t.setDaemon(true);
            return t;
        }).submit(this::buildIndex);
    }

    public void buildIndex() {
        try {
            long start = System.currentTimeMillis();
            List<Security> all = securityRepo.findAll();
            List<TextSegment> segs = new ArrayList<>();
            List<Long> ids = new ArrayList<>();
            for (Security s : all) {
                segs.add(TextSegment.from(s.toSearchText()));
                ids.add(s.getId());
            }
            if (!segs.isEmpty()) {
                List<Embedding> embs = model.embedAll(segs).content();
                for (int i = 0; i < embs.size(); i++) vectors.put(ids.get(i), embs.get(i).vector());
            }
            buildFeatureIndex();
            ready.set(true);
            log.info("Security semantic index ready: {} vectors in {} ms",
                    vectors.size(), System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.error("Security embedding warm-up failed", e);
        }
    }

    public boolean isReady() { return ready.get(); }

    private void buildFeatureIndex() {
        for (FeatureCatalog.Feature f : FeatureCatalog.FEATURES) {
            // Keyed by title, not route: several features live on one screen (the blotter, the
            // watchlist and the order ticket are all on the Trader Terminal), and each has to be
            // findable in its own right.
            featureVectors.put(f.title(), model.embed(f.searchText()).content().vector());
        }
        log.info("Feature index ready: {} screens are now findable by asking for them",
                featureVectors.size());
    }

    /**
     * "Where do I find X?" — matches a question against the OMS's own screens.
     *
     * <p>Runs on the in-process model, so it works with no Gemini key and nothing leaves the exchange.
     */
    public List<com.naztech.oms.api.Dtos.NavHit> findFeature(String question, double minPercent, int limit) {
        if (question == null || question.isBlank() || featureVectors.isEmpty()) {
            return List.of();
        }
        float[] q = model.embed(question.trim()).content().vector();
        List<com.naztech.oms.api.Dtos.NavHit> hits = new ArrayList<>();
        for (FeatureCatalog.Feature f : FeatureCatalog.FEATURES) {
            float[] v = featureVectors.get(f.title());
            if (v == null) {
                continue;
            }
            double pct = Math.max(0.0, cosine(q, v)) * 100.0;
            if (pct < minPercent) {
                continue;
            }
            hits.add(new com.naztech.oms.api.Dtos.NavHit(f.route(), f.title(), f.what(), f.how(),
                    Math.round(pct * 10.0) / 10.0));
        }
        hits.sort(java.util.Comparator.comparingDouble(com.naztech.oms.api.Dtos.NavHit::matchPct).reversed());
        return hits.size() > limit ? hits.subList(0, limit) : hits;
    }
    public int indexed() { return vectors.size(); }

    /** Returns securities ranked by semantic similarity to {@code query}. */
    public List<AiHit> search(String query, double minPercent, int limit) {
        if (query == null || query.isBlank() || vectors.isEmpty()) return List.of();
        float[] q = model.embed(query.trim()).content().vector();
        Map<Long, Security> byId = new HashMap<>();
        for (Security s : securityRepo.findAll()) byId.put(s.getId(), s);

        List<AiHit> hits = new ArrayList<>();
        for (Map.Entry<Long, float[]> e : vectors.entrySet()) {
            double pct = Math.max(0.0, cosine(q, e.getValue())) * 100.0;
            if (pct < minPercent) continue;
            Security s = byId.get(e.getKey());
            if (s == null) continue;
            hits.add(new AiHit(s.getId(), s.getSymbol(), s.getName(), s.getAssetClass(),
                    Math.round(pct * 10.0) / 10.0));
        }
        hits.sort(Comparator.comparingDouble(AiHit::matchPct).reversed());
        return hits.size() > limit ? hits.subList(0, limit) : hits;
    }

    private static double cosine(float[] a, float[] b) {
        if (a.length != b.length) return 0.0;
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i];
        }
        double denom = Math.sqrt(na) * Math.sqrt(nb);
        return denom == 0 ? 0 : dot / denom;
    }
}
