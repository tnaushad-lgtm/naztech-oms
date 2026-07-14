package com.naztech.oms.service;

import com.naztech.oms.api.Dtos.PortfolioView;
import com.naztech.oms.api.Dtos.PositionView;
import com.naztech.oms.entity.MarketData;
import com.naztech.oms.entity.Security;
import com.naztech.oms.repo.MarketDataRepo;
import com.naztech.oms.repo.SecurityRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * AI Investment Advisor. Grounds a Gemini 2.5 Flash model in the LIVE OMS data
 * (the investor's portfolio + the current market) so answers about "my position",
 * "is GP good today", "which sector is doing well", "show me Z companies" are factual.
 * Falls back to an OMS-grounded rule-based answerer when no Gemini key is configured.
 *
 * <p>Responses are informational/educational only — not licensed financial advice.
 */
@Service
public class AiAdvisorService {

    private static final Logger log = LoggerFactory.getLogger(AiAdvisorService.class);

    private static final String SYSTEM = """
        You are the NAZTECH OMS AI Advisor for investors on the Dhaka (DSE) and Chittagong (CSE)
        stock exchanges. Use ONLY the LIVE DATA provided below for any facts about prices, the
        market, share categories, and the user's portfolio — never invent numbers or tickers.
        Be concise, practical and friendly. Reply in the SAME language the user writes in
        (English or Bangla / বাংলা). When discussing a stock, mention its live price, day change %,
        DSE category (A/B/N/Z/G), Shariah status if relevant, and the user's holding & P&L if they
        own it. Explain trading concepts (order types, etc.) clearly when asked. ALWAYS end with a
        one-line disclaimer that this is informational only, not licensed financial advice, and that
        the investor should do their own research / consult a licensed advisor. Currency is BDT (৳).
        """;

    private final PortfolioService portfolio;
    private final SecurityRepo securityRepo;
    private final MarketDataRepo marketRepo;

    @Value("${app.ai.gemini.key:}")   private String geminiKey;
    @Value("${app.ai.gemini.model:gemini-2.5-flash}") private String geminiModel;

    private final SecuritySearchService search;
    private final OpenAiService openai;

    public AiAdvisorService(PortfolioService portfolio, SecurityRepo securityRepo, MarketDataRepo marketRepo,
                            SecuritySearchService search, OpenAiService openai) {
        this.portfolio = portfolio;
        this.securityRepo = securityRepo;
        this.marketRepo = marketRepo;
        this.search = search;
        this.openai = openai;
    }

    /** The system prompt, shared by every provider — and by the live-voice session. */
    public String systemPrompt() {
        return SYSTEM;
    }

    /** The live OMS picture: indices, breadth, movers, sectors, the instrument table, the portfolio. */
    public String liveContext(Long accountId) {
        return buildContext(accountId);
    }

    /**
     * The same picture, small enough to speak with.
     *
     * <p>{@link #buildContext} lists all 402 instruments — roughly ten thousand tokens. For the text
     * advisor that is exactly right: it can read the table and answer precisely. Handing it to a
     * <em>realtime voice</em> model was a mistake, and an audible one. The model spent its budget
     * reading a table instead of talking, hedged, and narrated its own hesitation: "let me look at
     * Grameenphone's data, then I'll tell you clearly" — a sentence that is not an answer.
     *
     * <p>So the voice model gets the shape of the market — indices, breadth, the movers, the sectors,
     * and this dealer's actual holdings — and <b>tools</b> for anything specific. Which is how a person
     * works too: you hold the picture in your head and look up the number when asked.
     */
    public String voiceContext(Long accountId) {
        List<Row> rows = marketRows();
        List<Row> eq = rows.stream().filter(Row::isEq).collect(Collectors.toList());
        StringBuilder sb = new StringBuilder();

        sb.append("DATE: ").append(java.time.LocalDate.now()).append('\n');

        sb.append("INDICES: ").append(rows.stream()
                .filter(r -> "INDEX".equals(r.s().getAssetClass()))
                .map(r -> r.s().getSymbol() + " " + nf(r.ltp()) + " (" + sign(r.chg()) + "%)")
                .collect(Collectors.joining(", "))).append('\n');

        long up = eq.stream().filter(r -> r.chg().signum() > 0).count();
        long dn = eq.stream().filter(r -> r.chg().signum() < 0).count();
        sb.append("BREADTH: ").append(up).append(" up, ").append(dn).append(" down, of ")
                .append(eq.size()).append(" instruments.\n");

        sb.append("TOP GAINERS: ").append(eq.stream()
                .sorted(Comparator.comparing(Row::chg).reversed()).limit(5)
                .map(r -> r.s().getSymbol() + " +" + nf(r.chg()) + "%").collect(Collectors.joining(", "))).append('\n');
        sb.append("TOP LOSERS: ").append(eq.stream()
                .sorted(Comparator.comparing(Row::chg)).limit(5)
                .map(r -> r.s().getSymbol() + " " + nf(r.chg()) + "%").collect(Collectors.joining(", "))).append('\n');
        sb.append("MOST ACTIVE: ").append(eq.stream()
                .sorted(Comparator.comparing(Row::valueMn).reversed()).limit(5)
                .map(r -> r.s().getSymbol()).collect(Collectors.joining(", "))).append('\n');

        Map<String, double[]> sec = new LinkedHashMap<>();
        for (Row r : eq) {
            String k = r.s().getSector() == null ? "Other" : r.s().getSector();
            double[] a = sec.computeIfAbsent(k, x -> new double[2]);
            a[0] += r.chg().doubleValue();
            a[1] += 1;
        }
        sb.append("SECTORS (best to worst): ").append(sec.entrySet().stream()
                .map(e -> Map.entry(e.getKey(), e.getValue()[1] == 0 ? 0 : e.getValue()[0] / e.getValue()[1]))
                .sorted((x, y) -> Double.compare(y.getValue(), x.getValue()))
                .map(e -> e.getKey() + " " + String.format("%+.2f%%", e.getValue()))
                .collect(Collectors.joining(", "))).append('\n');

        // The dealer's own book — small, and the thing they are most likely to ask about.
        if (accountId != null) {
            try {
                PortfolioView p = portfolio.portfolio(accountId);
                if (p != null) {
                    sb.append("\nTHE DEALER'S PORTFOLIO (").append(p.accountName()).append("): cash ৳")
                            .append(nf(p.cash())).append(", buying power ৳").append(nf(p.buyingPower()))
                            .append(", market value ৳").append(nf(p.totalValue()))
                            .append(", unrealised P&L ৳").append(nf(p.unrealizedPnl())).append('\n');
                    sb.append("HOLDINGS: ").append(p.positions().stream()
                            .map(x -> x.symbol() + " " + x.quantity() + " @ ৳" + nf(x.avgCost())
                                    + " (now ৳" + nf(x.ltp()) + ", " + sign(x.pnlPct()) + "%)")
                            .collect(Collectors.joining("; "))).append('\n');
                }
            } catch (Exception e) {
                log.debug("voiceContext: no portfolio for {}", accountId);
            }
        }
        return sb.toString();
    }

    /** Which providers are actually usable right now — the UI only offers what is configured. */
    public Map<String, Object> providers() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("gemini", geminiKey != null && !geminiKey.isBlank());
        m.put("geminiModel", geminiModel);
        m.put("openai", openai.enabled());
        m.put("openaiModel", openai.model());
        m.put("realtimeModel", openai.realtimeModel());
        m.put("liveVoice", openai.enabled());   // speech-to-speech is OpenAI-only today
        // The ten the API actually accepts. The ChatGPT app's voices (Maple, Breeze, Cove…) are not
        // among them — ask for one and OpenAI rejects the whole session.
        m.put("voices", openai.voices());
        m.put("voice", openai.defaultVoice());
        return m;
    }

    /** {@code route} is set when the answer is "it is on this screen" — the UI turns it into a link. */
    public record Reply(String answer, String source, boolean ai, String route) {
        public Reply(String answer, String source, boolean ai) {
            this(answer, source, ai, null);
        }
    }

    public Reply advise(String message, Long accountId, String action,
                        String imageBase64, String imageMime, List<Map<String, String>> history, String lang) {
        return advise(message, accountId, action, imageBase64, imageMime, history, lang, null);
    }

    /**
     * @param provider {@code "openai"} or {@code "gemini"}; null/blank picks whichever is configured,
     *                 preferring Gemini (it is the incumbent, and it handles the screenshot path).
     */
    public Reply advise(String message, Long accountId, String action,
                        String imageBase64, String imageMime, List<Map<String, String>> history, String lang,
                        String provider) {
        boolean dseStatus = "DSE_STATUS".equalsIgnoreCase(action);
        String userMsg = dseStatus
                ? "Give me a concise current DSE market status summary: index direction, market breadth, "
                  + "the notable top gainers and losers, and which sectors look strong or weak today."
                : (message == null ? "" : message.trim());

        String langDirective = "bn".equalsIgnoreCase(lang)
                ? "VERY IMPORTANT: Write your ENTIRE answer in Bangla (বাংলা ভাষায় সম্পূর্ণ উত্তর দিন), "
                  + "even if the question is in English. Use Bangla for the disclaimer too."
                : "en".equalsIgnoreCase(lang) ? "Write your answer in clear English." : "";

        // "Where do I find market depth?" is a question about the product, not the market. It used to
        // fall through to the ticker branches and come back with a gainers list — an answer shaped like
        // an answer, to a question nobody asked. The screens are indexed by the same on-prem model that
        // finds securities, so this works with no Gemini key and nothing leaves the exchange.
        Reply nav = navigate(userMsg);
        if (nav != null) {
            return nav;
        }

        String context = buildContext(accountId);
        boolean wantsOpenAi = "openai".equalsIgnoreCase(provider) || "chatgpt".equalsIgnoreCase(provider);
        boolean hasGemini = geminiKey != null && !geminiKey.isBlank();

        // ChatGPT, when asked for. It has no vision path here, so a screenshot still goes to Gemini —
        // silently dropping the image the dealer just pasted would be worse than ignoring the toggle.
        if (wantsOpenAi && openai.enabled() && (imageBase64 == null || imageBase64.isBlank())) {
            String prompt = "LIVE OMS DATA (use only this for facts):\n" + context
                    + "\n\n----\nINVESTOR QUESTION: " + userMsg
                    + (langDirective.isBlank() ? "" : "\n\n" + langDirective);
            String ans = openai.complete(SYSTEM, prompt, 0.4, 1536);
            if (ans != null && !ans.isBlank()) {
                return new Reply(ans, "openai:" + openai.model(), true);
            }
            log.warn("OpenAI returned nothing — falling back");
        }

        if (hasGemini) {
            try {
                String ans = callGemini(userMsg, context, imageBase64, imageMime, history, langDirective);
                if (ans != null && !ans.isBlank()) return new Reply(ans, "gemini:" + geminiModel, true);
            } catch (Exception e) {
                log.warn("Gemini call failed, using fallback: {}", e.getMessage());
            }
        }

        // Gemini absent or failed, and the caller did not ask for OpenAI: try it anyway rather than
        // dropping to the regex fallback while a perfectly good provider sits configured and idle.
        if (!wantsOpenAi && openai.enabled() && (imageBase64 == null || imageBase64.isBlank())) {
            String ans = openai.complete(SYSTEM,
                    "LIVE OMS DATA (use only this for facts):\n" + context + "\n\n----\nINVESTOR QUESTION: " + userMsg,
                    0.4, 1536);
            if (ans != null && !ans.isBlank()) {
                return new Reply(ans, "openai:" + openai.model(), true);
            }
        }
        return new Reply(fallback(userMsg, accountId, imageBase64 != null), "rule-based (offline)", false);
    }

    // ----------------------------------------------------------------- Gemini
    @SuppressWarnings("unchecked")
    private String callGemini(String msg, String context, String imageBase64, String imageMime,
                              List<Map<String, String>> history, String langDirective) {
        List<Map<String, Object>> contents = new ArrayList<>();
        if (history != null) {
            for (Map<String, String> h : history) {
                String role = "assistant".equals(h.get("role")) ? "model" : "user";
                contents.add(Map.of("role", role, "parts", List.of(Map.of("text", h.getOrDefault("text", "")))));
            }
        }
        List<Map<String, Object>> parts = new ArrayList<>();
        parts.add(Map.of("text", "LIVE OMS DATA (use only this for facts):\n" + context
                + "\n\n----\nINVESTOR QUESTION: " + msg));
        if (imageBase64 != null && !imageBase64.isBlank()) {
            parts.add(Map.of("inline_data", Map.of(
                    "mime_type", imageMime == null ? "image/png" : imageMime, "data", imageBase64)));
            parts.add(Map.of("text", "The investor also uploaded a stock-market screenshot above. "
                    + "Read the visible tickers/prices and explain what it shows and the status."));
        }
        if (langDirective != null && !langDirective.isBlank()) parts.add(Map.of("text", langDirective));
        contents.add(Map.of("role", "user", "parts", parts));

        Map<String, Object> body = Map.of(
                "system_instruction", Map.of("parts", List.of(Map.of("text", SYSTEM))),
                "contents", contents,
                // gemini-2.5-flash is a "thinking" model — reasoning tokens count against maxOutputTokens,
                // which truncated answers mid-sentence (e.g. the DSE Status reply). Disable thinking so the
                // whole budget goes to the visible answer, and give it more room.
                "generationConfig", Map.of(
                        "temperature", 0.4,
                        "maxOutputTokens", 1536,
                        "thinkingConfig", Map.of("thinkingBudget", 0)));

        String url = "https://generativelanguage.googleapis.com/v1beta/models/"
                + geminiModel + ":generateContent?key=" + geminiKey;
        Map<String, Object> resp = RestClient.create().post().uri(url)
                .body(body).retrieve().body(Map.class);
        if (resp == null) return null;
        List<Map<String, Object>> cands = (List<Map<String, Object>>) resp.get("candidates");
        if (cands == null || cands.isEmpty()) return null;
        Map<String, Object> content = (Map<String, Object>) cands.get(0).get("content");
        List<Map<String, Object>> rparts = (List<Map<String, Object>>) content.get("parts");
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> p : rparts) if (p.get("text") != null) sb.append(p.get("text"));
        return sb.toString();
    }

    /** Plain low-temperature Gemini text completion — used by the AI Order Bot to parse free
     *  English/Bangla speech into structured orders. Returns null when no key is set or on failure. */
    @SuppressWarnings("unchecked")
    public String geminiComplete(String prompt) {
        if (geminiKey == null || geminiKey.isBlank() || prompt == null || prompt.isBlank()) return null;
        try {
            Map<String, Object> body = Map.of(
                    "contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", prompt)))),
                    "generationConfig", Map.of("temperature", 0.1, "maxOutputTokens", 512,
                            "thinkingConfig", Map.of("thinkingBudget", 0)));
            String url = "https://generativelanguage.googleapis.com/v1beta/models/" + geminiModel + ":generateContent?key=" + geminiKey;
            Map<String, Object> resp = RestClient.create().post().uri(url).body(body).retrieve().body(Map.class);
            if (resp == null) return null;
            List<Map<String, Object>> cands = (List<Map<String, Object>>) resp.get("candidates");
            if (cands == null || cands.isEmpty()) return null;
            Map<String, Object> content = (Map<String, Object>) cands.get(0).get("content");
            List<Map<String, Object>> rparts = (List<Map<String, Object>>) content.get("parts");
            StringBuilder sb = new StringBuilder();
            for (Map<String, Object> p : rparts) if (p.get("text") != null) sb.append(p.get("text"));
            return sb.toString();
        } catch (Exception e) {
            log.warn("geminiComplete failed: {}", e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------- TTS
    /**
     * Server-side text-to-speech so Bangla (and English) audio works even when the
     * browser has no matching voice installed. Streams MP3 synthesised via Google
     * Translate TTS (chunked to its length limit). Returns null on failure so the
     * client can fall back to the browser's SpeechSynthesis.
     */
    public record TtsAudio(byte[] data, String contentType) {}

    public TtsAudio tts(String text, String lang, boolean hd) {
        if (text == null || text.isBlank()) return null;
        String clean = text.replaceAll("[*#>`_]", " ").replaceAll("\\s+", " ").trim();
        if (clean.length() > 1500) clean = clean.substring(0, 1500);
        // The client sends short sentence-sized chunks so even the richer Gemini voice
        // returns the first chunk in ~4-5s. hd=true → Gemini (realistic); hd=false → Translate (instant).
        if (hd) {
            byte[] wav = ttsGemini(clean);
            if (wav != null) return new TtsAudio(wav, "audio/wav");
            byte[] mp3 = ttsTranslate(clean, lang);
            if (mp3 != null) return new TtsAudio(mp3, "audio/mpeg");
        } else {
            byte[] mp3 = ttsTranslate(clean, lang);
            if (mp3 != null) return new TtsAudio(mp3, "audio/mpeg");
            byte[] wav = ttsGemini(clean);
            if (wav != null) return new TtsAudio(wav, "audio/wav");
        }
        return null;
    }

    private byte[] ttsGemini(String text) {
        if (geminiKey == null || geminiKey.isBlank()) return null;
        String url = "https://generativelanguage.googleapis.com/v1beta/models/"
                + "gemini-2.5-flash-preview-tts:generateContent?key=" + geminiKey;
        Map<String, Object> body = Map.of(
                "contents", List.of(Map.of("parts", List.of(Map.of("text", text)))),
                "generationConfig", Map.of(
                        "responseModalities", List.of("AUDIO"),
                        "speechConfig", Map.of("voiceConfig",
                                Map.of("prebuiltVoiceConfig", Map.of("voiceName", "Kore")))));
        // up to 3 attempts so a transient rate-limit doesn't drop us to the robotic voice
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                Map<String, Object> resp = RestClient.create().post().uri(url).body(body).retrieve().body(Map.class);
                byte[] wav = extractWav(resp);
                if (wav != null) return wav;
            } catch (Exception e) {
                log.warn("Gemini TTS attempt {} failed: {}", attempt + 1, e.getMessage());
            }
            if (attempt < 2) { try { Thread.sleep(900); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; } }
        }
        return null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private byte[] extractWav(Map<String, Object> resp) {
        if (resp == null) return null;
        List<Map<String, Object>> cands = (List<Map<String, Object>>) resp.get("candidates");
        if (cands == null || cands.isEmpty()) return null;
        Map<String, Object> content = (Map<String, Object>) cands.get(0).get("content");
        if (content == null) return null;
        List<Map<String, Object>> parts = (List<Map<String, Object>>) content.get("parts");
        if (parts == null) return null;
        for (Map<String, Object> p : parts) {
            Object inline = p.containsKey("inlineData") ? p.get("inlineData") : p.get("inline_data");
            if (inline instanceof Map) {
                Map m = (Map) inline;
                Object data = m.get("data");
                Object mime = m.containsKey("mimeType") ? m.get("mimeType") : m.get("mime_type");
                if (data != null) {
                    byte[] pcm = java.util.Base64.getDecoder().decode(data.toString());
                    int rate = 24000;
                    if (mime != null) {
                        String mt = mime.toString();
                        int i = mt.indexOf("rate=");
                        if (i >= 0) try { rate = Integer.parseInt(mt.substring(i + 5).replaceAll("[^0-9].*", "")); } catch (Exception ignore) {}
                    }
                    return buildWav(pcm, rate);
                }
            }
        }
        return null;
    }

    private byte[] ttsTranslate(String clean, String lang) {
        String tl = (lang != null && lang.toLowerCase().startsWith("bn")) ? "bn" : "en";
        try {
            RestClient rc = RestClient.create();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            for (String chunk : chunk(clean, 180)) {
                String url = "https://translate.google.com/translate_tts?ie=UTF-8&client=tw-ob&tl=" + tl
                        + "&q=" + java.net.URLEncoder.encode(chunk, java.nio.charset.StandardCharsets.UTF_8);
                byte[] b = rc.get().uri(java.net.URI.create(url))
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                        .header("Referer", "https://translate.google.com/")
                        .retrieve().body(byte[].class);
                if (b != null) out.write(b);
            }
            return out.size() > 0 ? out.toByteArray() : null;
        } catch (Exception e) {
            log.warn("Translate TTS failed: {}", e.getMessage());
            return null;
        }
    }

    /** Wrap raw 16-bit mono PCM (from Gemini TTS) in a WAV container the browser can play. */
    private static byte[] buildWav(byte[] pcm, int sampleRate) {
        int channels = 1, bits = 16;
        int byteRate = sampleRate * channels * bits / 8;
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate(44 + pcm.length).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        bb.put("RIFF".getBytes()); bb.putInt(36 + pcm.length); bb.put("WAVE".getBytes());
        bb.put("fmt ".getBytes()); bb.putInt(16); bb.putShort((short) 1); bb.putShort((short) channels);
        bb.putInt(sampleRate); bb.putInt(byteRate); bb.putShort((short) (channels * bits / 8)); bb.putShort((short) bits);
        bb.put("data".getBytes()); bb.putInt(pcm.length); bb.put(pcm);
        return bb.array();
    }

    private static List<String> chunk(String s, int max) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String w : s.split(" ")) {
            if (cur.length() + w.length() + 1 > max) { if (cur.length() > 0) out.add(cur.toString()); cur.setLength(0); }
            if (cur.length() > 0) cur.append(' ');
            cur.append(w);
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    // --------------------------------------------------------------- context
    private List<Row> marketRows() {
        Map<Long, MarketData> md = marketRepo.findAll().stream()
                .collect(Collectors.toMap(MarketData::getSecurityId, m -> m));
        List<Row> rows = new ArrayList<>();
        for (Security s : securityRepo.findAll()) {
            MarketData m = md.get(s.getId());
            rows.add(new Row(s, m == null ? BigDecimal.ZERO : nz(m.getLtp()),
                    m == null ? BigDecimal.ZERO : nz(m.getChangePct()),
                    m == null ? 0L : (m.getVolume() == null ? 0 : m.getVolume()),
                    m == null ? BigDecimal.ZERO : nz(m.getValueMn())));
        }
        return rows;
    }
    private record Row(Security s, BigDecimal ltp, BigDecimal chg, long vol, BigDecimal valueMn) {
        boolean isEq() { return !"INDEX".equals(s.getAssetClass()); }
    }

    private String buildContext(Long accountId) {
        List<Row> rows = marketRows();
        StringBuilder sb = new StringBuilder();
        sb.append("DATE: ").append(java.time.LocalDate.now()).append("\n\n");

        // Indices
        sb.append("INDICES:\n");
        rows.stream().filter(r -> "INDEX".equals(r.s().getAssetClass()))
                .forEach(r -> sb.append("  ").append(r.s().getSymbol()).append(" = ").append(nf(r.ltp()))
                        .append(" (").append(sign(r.chg())).append("%)\n"));

        List<Row> eq = rows.stream().filter(Row::isEq).collect(Collectors.toList());
        long up = eq.stream().filter(r -> r.chg().signum() > 0).count();
        long dn = eq.stream().filter(r -> r.chg().signum() < 0).count();
        sb.append("\nMARKET BREADTH: ").append(up).append(" advancing, ").append(dn).append(" declining of ")
                .append(eq.size()).append(" instruments.\n");

        sb.append("\nTOP GAINERS: ").append(eq.stream()
                .sorted(Comparator.comparing(Row::chg).reversed()).limit(5)
                .map(r -> r.s().getSymbol() + " +" + nf(r.chg()) + "%").collect(Collectors.joining(", ")));
        sb.append("\nTOP LOSERS: ").append(eq.stream()
                .sorted(Comparator.comparing(Row::chg)).limit(5)
                .map(r -> r.s().getSymbol() + " " + nf(r.chg()) + "%").collect(Collectors.joining(", ")));
        sb.append("\nMOST ACTIVE: ").append(eq.stream()
                .sorted(Comparator.comparing(Row::valueMn).reversed()).limit(5)
                .map(r -> r.s().getSymbol()).collect(Collectors.joining(", ")));

        // Sector strength
        Map<String, double[]> sec = new LinkedHashMap<>(); // sector -> [sumChg, count]
        for (Row r : eq) {
            String k = r.s().getSector() == null ? "Other" : r.s().getSector();
            double[] a = sec.computeIfAbsent(k, x -> new double[2]);
            a[0] += r.chg().doubleValue(); a[1] += 1;
        }
        String sectors = sec.entrySet().stream()
                .map(e -> Map.entry(e.getKey(), e.getValue()[1] == 0 ? 0 : e.getValue()[0] / e.getValue()[1]))
                .sorted((x, y) -> Double.compare(y.getValue(), x.getValue()))
                .map(e -> e.getKey() + " " + String.format("%+.2f%%", e.getValue()))
                .collect(Collectors.joining(", "));
        sb.append("\nSECTOR AVG CHANGE (best→worst): ").append(sectors).append("\n");

        // Full instrument table
        sb.append("\nINSTRUMENTS (symbol | name | sector | category | shariah | LTP | day%):\n");
        for (Row r : eq) {
            Security s = r.s();
            sb.append("  ").append(s.getSymbol()).append(" | ").append(s.getName()).append(" | ")
                    .append(s.getSector()).append(" | cat ").append(s.getCategory())
                    .append(Boolean.TRUE.equals(s.getShariah()) ? " | Shariah" : " | -")
                    .append(" | ").append(nf(r.ltp())).append(" | ").append(sign(r.chg())).append("%\n");
        }

        // Portfolio
        if (accountId != null) {
            PortfolioView p = portfolio.portfolio(accountId);
            if (p != null) {
                sb.append("\nUSER PORTFOLIO (").append(p.accountName()).append("):\n");
                sb.append("  Cash ৳").append(nf(p.cash())).append(", Buying power ৳").append(nf(p.buyingPower()))
                        .append(", Total value ৳").append(nf(p.totalValue())).append("\n");
                sb.append("  Unrealized P&L ৳").append(nf(p.unrealizedPnl()))
                        .append(", Realized P&L ৳").append(nf(p.realizedPnl()))
                        .append(", Day P&L ৳").append(nf(p.dayPnl())).append("\n");
                sb.append("  Holdings:\n");
                for (PositionView pos : p.positions())
                    sb.append("    ").append(pos.symbol()).append(": ").append(pos.quantity())
                            .append(" sh, avg ৳").append(nf(pos.avgCost())).append(", LTP ৳").append(nf(pos.ltp()))
                            .append(", P&L ৳").append(nf(pos.unrealizedPnl())).append(" (").append(nf(pos.pnlPct()))
                            .append("%)\n");
                if (p.positions().isEmpty()) sb.append("    (no open positions)\n");
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------- fallback
    /** Words that mean "take me to it", rather than "tell me about it". */
    private static final List<String> NAV_CUES = List.of(
            "where", "how do i", "how to", "how can i", "find", "navigate", "go to", "open the",
            "which screen", "which page", "show me the", "take me");

    /**
     * If the user is asking where something is, answer with the screen, how to use it, and a link.
     * A match below the threshold means we are guessing — and a confident wrong direction is worse
     * than admitting we did not understand, so we fall through to the normal advisor instead.
     */
    private Reply navigate(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        String q = message.toLowerCase();
        boolean asksWhere = NAV_CUES.stream().anyMatch(q::contains);
        if (!asksWhere) {
            return null;
        }
        List<com.naztech.oms.api.Dtos.NavHit> hits = search.findFeature(message, 30, 3);
        if (hits.isEmpty()) {
            return null;
        }
        com.naztech.oms.api.Dtos.NavHit top = hits.get(0);

        StringBuilder sb = new StringBuilder();
        sb.append("**").append(top.title()).append("**\n\n");
        sb.append(top.what()).append("\n\n");
        sb.append("**How:** ").append(top.how()).append("\n\n");
        sb.append("It is at `").append(top.route()).append("` — in the left sidebar.");
        if (hits.size() > 1) {
            sb.append("\n\nAlso related: ");
            sb.append(hits.subList(1, hits.size()).stream()
                    .map(h -> h.title() + " (" + h.route() + ")")
                    .collect(Collectors.joining(" · ")));
        }
        return new Reply(sb.toString(), "on-prem MiniLM (offline)", false, top.route());
    }

    private String fallback(String msg, Long accountId, boolean hasImage) {
        if (hasImage)
            return "📷 To analyse an uploaded screenshot I need the Gemini AI key configured "
                 + "(set GEMINI_API_KEY and restart the backend). Meanwhile, ask me about any DSE/CSE "
                 + "ticker, your portfolio, sectors or order types and I'll answer from live data.";
        String q = msg.toLowerCase();
        List<Row> rows = marketRows();
        List<Row> eq = rows.stream().filter(Row::isEq).collect(Collectors.toList());

        // order-type explainer
        if (q.contains("order type") || (q.contains("buy") && q.contains("sell") && q.contains("option"))
                || q.contains("what are different")) return orderTypesHelp();

        // portfolio / profit-loss / cash
        if (q.contains("portfolio") || q.contains("position") || q.contains("profit") || q.contains("loss")
                || q.contains("how much") || q.contains("my holding") || q.contains("amount available")
                || q.contains("cash") || q.contains("buying power")) return portfolioAnswer(accountId);

        // category Z / A etc.
        for (String cat : List.of("z", "a", "b", "n")) {
            if (q.matches(".*\\b" + cat + "\\b.*(categor|compan).*") || q.contains("category " + cat)
                    || q.contains(cat + " category")) {
                String list = eq.stream().filter(r -> cat.equalsIgnoreCase(r.s().getCategory()))
                        .map(r -> r.s().getSymbol() + " (" + r.s().getName() + ", " + sign(r.chg()) + "%)")
                        .collect(Collectors.joining("\n• "));
                if (!list.isBlank()) return "Category " + cat.toUpperCase() + " shares:\n• " + list
                        + "\n\nNote: Z-category shares carry higher risk (irregular/no dividend). "
                        + "Informational only — not licensed financial advice.";
            }
        }

        // sector strength
        if (q.contains("sector")) {
            Map<String, double[]> sec = new LinkedHashMap<>();
            for (Row r : eq) { String k = r.s().getSector() == null ? "Other" : r.s().getSector();
                double[] a = sec.computeIfAbsent(k, x -> new double[2]); a[0] += r.chg().doubleValue(); a[1] += 1; }
            String ranked = sec.entrySet().stream()
                    .map(e -> Map.entry(e.getKey(), e.getValue()[1] == 0 ? 0 : e.getValue()[0] / e.getValue()[1]))
                    .sorted((x, y) -> Double.compare(y.getValue(), x.getValue()))
                    .map(e -> String.format("%s: %+.2f%%", e.getKey(), e.getValue()))
                    .collect(Collectors.joining("\n• "));
            return "Sector performance today (avg day change, best first):\n• " + ranked
                    + "\n\nInformational only — not licensed financial advice.";
        }

        // pharma / bank / specific sector "top X"
        for (String kw : List.of("pharma", "bank", "telecom", "cement", "fuel", "power", "food", "engineering")) {
            if (q.contains(kw)) {
                String list = eq.stream().filter(r -> r.s().getSector() != null && r.s().getSector().toLowerCase().contains(kw))
                        .sorted(Comparator.comparing(Row::chg).reversed()).limit(6)
                        .map(r -> r.s().getSymbol() + " — ৳" + nf(r.ltp()) + " (" + sign(r.chg()) + "%, cat " + r.s().getCategory() + ")")
                        .collect(Collectors.joining("\n• "));
                if (!list.isBlank()) return "Notable " + kw + " sector stocks today (by day change):\n• " + list
                        + "\n\nConsider fundamentals & your risk appetite. Informational only — not licensed financial advice.";
            }
        }

        // specific ticker / company
        Row hit = eq.stream().filter(r -> q.contains(r.s().getSymbol().toLowerCase())
                || q.contains(r.s().getName().toLowerCase().split(" ")[0])).findFirst().orElse(null);
        if (hit != null) return tickerAnswer(hit, accountId);

        // market status
        if (q.contains("market") || q.contains("dse") || q.contains("status") || q.contains("today") || q.isBlank())
            return marketStatus(rows, eq);

        return "I can help with: a specific stock (e.g. \"is GP good today?\"), your portfolio & P&L, "
             + "sector performance, share categories (A/B/N/Z), order types, and overall market status. "
             + "Try one of the suggestions below. (Informational only — not licensed financial advice.)";
    }

    private String tickerAnswer(Row r, Long accountId) {
        Security s = r.s();
        StringBuilder sb = new StringBuilder();
        sb.append(s.getSymbol()).append(" — ").append(s.getName()).append("\n");
        sb.append("Sector: ").append(s.getSector()).append(" · Category ").append(s.getCategory())
                .append(Boolean.TRUE.equals(s.getShariah()) ? " · Shariah-compliant" : "").append("\n");
        sb.append("LTP ৳").append(nf(r.ltp())).append(", today ").append(sign(r.chg())).append("%. ");
        sb.append(r.chg().signum() > 0 ? "It is trading up today" : r.chg().signum() < 0 ? "It is trading down today" : "It is flat today").append(".\n");
        if (accountId != null) {
            PortfolioView p = portfolio.portfolio(accountId);
            if (p != null) p.positions().stream().filter(x -> x.symbol().equals(s.getSymbol())).findFirst()
                    .ifPresent(pos -> sb.append("You hold ").append(pos.quantity()).append(" @ avg ৳").append(nf(pos.avgCost()))
                            .append(" → P&L ৳").append(nf(pos.unrealizedPnl())).append(" (").append(nf(pos.pnlPct())).append("%).\n"));
        }
        sb.append("\nConsider its category, sector trend, fundamentals and your risk appetite. ");
        sb.append("Informational only — not licensed financial advice.");
        return sb.toString();
    }

    private String portfolioAnswer(Long accountId) {
        if (accountId == null) return "I couldn't identify your account. Please select an account first.";
        PortfolioView p = portfolio.portfolio(accountId);
        if (p == null) return "No portfolio found for this account.";
        StringBuilder sb = new StringBuilder();
        sb.append("Portfolio — ").append(p.accountName()).append("\n");
        sb.append("Total value: ৳").append(nf(p.totalValue())).append(" | Cash: ৳").append(nf(p.cash()))
                .append(" | Buying power: ৳").append(nf(p.buyingPower())).append("\n");
        boolean profit = p.unrealizedPnl().signum() >= 0;
        sb.append("Unrealized P&L: ৳").append(nf(p.unrealizedPnl())).append(profit ? " (in profit ✅)" : " (in loss 🔻)")
                .append(" | Realized: ৳").append(nf(p.realizedPnl())).append(" | Day: ৳").append(nf(p.dayPnl())).append("\n");
        if (!p.positions().isEmpty()) {
            sb.append("Positions:\n");
            for (PositionView pos : p.positions())
                sb.append("• ").append(pos.symbol()).append(": ").append(pos.quantity()).append(" sh, P&L ৳")
                        .append(nf(pos.unrealizedPnl())).append(" (").append(nf(pos.pnlPct())).append("%)\n");
        }
        sb.append("\nInformational only — not licensed financial advice.");
        return sb.toString();
    }

    private String marketStatus(List<Row> rows, List<Row> eq) {
        long up = eq.stream().filter(r -> r.chg().signum() > 0).count();
        long dn = eq.stream().filter(r -> r.chg().signum() < 0).count();
        String idx = rows.stream().filter(r -> "INDEX".equals(r.s().getAssetClass()))
                .map(r -> r.s().getSymbol() + " " + nf(r.ltp()) + " (" + sign(r.chg()) + "%)").collect(Collectors.joining(", "));
        String gain = eq.stream().sorted(Comparator.comparing(Row::chg).reversed()).limit(3)
                .map(r -> r.s().getSymbol() + " +" + nf(r.chg()) + "%").collect(Collectors.joining(", "));
        String lose = eq.stream().sorted(Comparator.comparing(Row::chg)).limit(3)
                .map(r -> r.s().getSymbol() + " " + nf(r.chg()) + "%").collect(Collectors.joining(", "));
        return "📊 Market status\nIndices: " + idx + "\nBreadth: " + up + " up / " + dn + " down.\n"
                + "Top gainers: " + gain + "\nTop losers: " + lose
                + "\nSentiment looks " + (up >= dn ? "broadly positive" : "cautious/negative") + " right now.\n"
                + "\nInformational only — not licensed financial advice.";
    }

    private String orderTypesHelp() {
        return "Order types & options in NAZTECH OMS:\n"
             + "• LIMIT — buy/sell only at your price or better (price protection).\n"
             + "• MARKET — execute immediately at the best available price (no price protection).\n"
             + "• STOP — a market order that triggers once the price crosses your stop level.\n"
             + "• STOP-LIMIT — like STOP but becomes a LIMIT order when triggered.\n"
             + "Validity: DAY (expires end of session), GTC (good-till-cancelled), GTD (good-till-date), GTS (good-till-session).\n"
             + "Windows: NORMAL, SPOT, BLOCK, ODD-LOT, FOREIGN.\n"
             + "BUY uses your cash/buying power; SELL requires you to hold the shares.\n"
             + "\nInformational only — not licensed financial advice.";
    }

    // helpers
    private static BigDecimal nz(BigDecimal b) { return b == null ? BigDecimal.ZERO : b; }
    private static String nf(BigDecimal b) {
        return (b == null ? BigDecimal.ZERO : b).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }
    private static String sign(BigDecimal b) {
        BigDecimal v = b == null ? BigDecimal.ZERO : b;
        return (v.signum() >= 0 ? "+" : "") + nf(v);
    }
}
