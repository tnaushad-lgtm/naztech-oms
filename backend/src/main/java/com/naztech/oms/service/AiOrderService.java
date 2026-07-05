package com.naztech.oms.service;

import com.naztech.oms.api.Dtos.AiHit;
import com.naztech.oms.api.Dtos.ParsedOrder;
import com.naztech.oms.entity.Security;
import com.naztech.oms.repo.SecurityRepo;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Natural-language order parser for the AI Order Bot: turns free text / speech such as
 * "buy GP 100 at 120 taka, then sell 50 BRACBANK at 64" or "buy PBLPBOND 10 at 9% yield" into structured
 * orders. Deterministic and offline (regex + exact-ticker match), with the in-process MiniLM semantic
 * search as a fuzzy fallback for company names ("square pharma" → SQURPHARMA). It only PARSES — the UI
 * shows each order for confirmation before anything is placed.
 */
@Service
public class AiOrderService {

    private static final Pattern SIDE = Pattern.compile("\\b(buy|purchase|acquire|sell|offload|dispose)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern YIELD = Pattern.compile("(?:@|at|of)?\\s*([0-9]+(?:\\.[0-9]+)?)\\s*%?\\s*(?:yield|ytm)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PRICE = Pattern.compile("(?:@|at|for|price(?:\\s+of)?)\\s*(?:tk|taka|rs\\.?)?\\s*([0-9]+(?:\\.[0-9]+)?)|([0-9]+(?:\\.[0-9]+)?)\\s*(?:tk|taka)", Pattern.CASE_INSENSITIVE);
    private static final Pattern QTY_LABELED = Pattern.compile("([0-9]{1,12})\\s*(?:shares?|qty|quantity|units?|lots?|nos?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern INT = Pattern.compile("\\b([0-9]{1,12})\\b");   // bounded: fits in a long, avoids overflow
    private static final Set<String> STOP = Set.of("buy", "sell", "purchase", "acquire", "bid", "long", "offload", "dispose",
            "short", "at", "for", "of", "price", "taka", "tk", "shares", "share", "qty", "quantity", "units", "unit",
            "lots", "lot", "yield", "ytm", "a", "an", "the", "order", "please", "and", "then", "some", "nos", "no", "rs");

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();
    private static final String GEMINI_HEAD =
            "You convert a spoken or typed trading instruction (which may be in English or Bangla) into structured "
          + "stock orders for the Dhaka Stock Exchange. Here is the full list of tradable instruments, one per line "
          + "as 'TICKER = Company Name':\n\n";
    private static final String GEMINI_TAIL =
            "\n\nReturn ONLY a compact JSON array and nothing else. Each element: {\"side\": \"BUY\" or \"SELL\", "
          + "\"qty\": <integer number of shares>, \"ticker\": \"<the EXACT ticker copied from the list above that the "
          + "user means — carefully map the company name the user said, whether Bangla or English, to the correct "
          + "ticker (e.g. 'brac bank' -> BRACBANK, 'grameenphone' -> GP); return null if none of the listed instruments "
          + "clearly matches>\", \"instrument\": \"<the company name or word the user actually said>\", "
          + "\"price\": <number, or null if the user said market/current price or gave no price>}. "
          + "If the text contains no valid order, return []. Instruction: ";

    private final SecurityRepo securityRepo;
    private final SecuritySearchService search;
    private final AiAdvisorService advisor;

    public AiOrderService(SecurityRepo securityRepo, SecuritySearchService search, AiAdvisorService advisor) {
        this.securityRepo = securityRepo;
        this.search = search;
        this.advisor = advisor;
    }

    public List<ParsedOrder> parse(String text) {
        if (text == null || text.isBlank()) return List.of();
        Map<String, Security> bySymbol = new HashMap<>();
        for (Security s : securityRepo.findAll()) if (s.getSymbol() != null) bySymbol.put(s.getSymbol().toUpperCase(), s);

        List<ParsedOrder> out = new ArrayList<>();
        for (String seg : splitOrders(text)) {
            ParsedOrder po = parseSegment(seg, bySymbol);
            if (po != null) out.add(po);
        }
        return out;
    }

    /** Multilingual entry point (English/Bangla, any phrasing) — tries Gemini, falls back to the
     *  deterministic regex parser. Used by the voice / free-text path. */
    public List<ParsedOrder> parseSmart(String text) {
        if (text == null || text.isBlank()) return List.of();
        try {
            List<ParsedOrder> viaAi = parseWithGemini(text);
            if (viaAi != null) return viaAi;
        } catch (Exception ignored) {}
        return parse(text);
    }

    private List<ParsedOrder> parseWithGemini(String text) throws Exception {
        // Build the ticker map + a TICKER=Name catalogue in one pass so Gemini resolves to an EXACT
        // listed ticker (reliable for Bangla/English names) instead of us fuzzy-matching a translated name.
        Map<String, Security> bySymbol = new HashMap<>();
        StringBuilder cat = new StringBuilder();
        java.util.Set<String> catSeen = new java.util.HashSet<>();
        for (Security s : securityRepo.findAll()) {
            if (s.getSymbol() == null) continue;
            String up = s.getSymbol().toUpperCase();
            bySymbol.putIfAbsent(up, s);                          // prefer the first (DSE) listing on symbol dupes
            if (!"INDEX".equalsIgnoreCase(s.getAssetClass()) && catSeen.add(up))
                cat.append(s.getSymbol()).append(" = ").append(s.getName() == null ? "" : s.getName()).append('\n');
        }
        String json = advisor.geminiComplete(GEMINI_HEAD + cat + GEMINI_TAIL + text);
        if (json == null) return null;                        // no Gemini key → caller falls back to regex
        int a = json.indexOf('['), b = json.lastIndexOf(']');
        if (a < 0 || b <= a) return List.of();
        List<Map<String, Object>> items = MAPPER.readValue(json.substring(a, b + 1),
                new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
        List<ParsedOrder> out = new ArrayList<>();
        for (Map<String, Object> it : items) {
            String side = String.valueOf(it.getOrDefault("side", "BUY")).trim().toUpperCase().startsWith("S") ? "SELL" : "BUY";
            Long qty = toLong(it.get("qty"));
            BigDecimal price = toBd(it.get("price"));
            String ticker = clean(it.get("ticker"));
            String instr = clean(it.get("instrument"));
            Security sec = null;
            if (!ticker.isEmpty()) sec = bySymbol.get(ticker.toUpperCase());     // Gemini picked an exact listed ticker
            if (sec == null && !instr.isEmpty()) sec = resolveInstrument(instr, bySymbol);   // fallbacks
            if (sec == null && !ticker.isEmpty()) sec = resolveInstrument(ticker, bySymbol);
            String said = !instr.isEmpty() ? instr : ticker;
            if (sec == null)
                out.add(new ParsedOrder(false, side, null, null, null, qty, price, "PRICE", null, "Couldn't identify: " + said));
            else if (qty == null || qty <= 0)
                out.add(new ParsedOrder(false, side, sec.getSymbol(), sec.getId(), sec.getName(), null, price, "PRICE", null, "Couldn't read a quantity"));
            else
                out.add(new ParsedOrder(true, side, sec.getSymbol(), sec.getId(), sec.getName(), qty, price, "PRICE", null, price == null ? "Market order" : null));
        }
        return out;
    }

    private static String clean(Object o) {
        if (o == null) return "";
        String s = o.toString().trim();
        return "null".equalsIgnoreCase(s) ? "" : s;
    }

    private Security resolveInstrument(String instr, Map<String, Security> bySymbol) {
        if (instr == null || instr.isBlank()) return null;
        String up = instr.toUpperCase();
        if (bySymbol.containsKey(up)) return bySymbol.get(up);
        for (String tok : up.split("[^A-Z0-9]+")) if (tok.length() >= 2 && bySymbol.containsKey(tok)) return bySymbol.get(tok);
        try {
            List<AiHit> hits = search.search(instr, 40, 1);
            if (!hits.isEmpty()) return securityRepo.findById(hits.get(0).securityId()).orElse(null);
        } catch (Exception ignored) {}
        return null;
    }

    private static Long toLong(Object o) {
        if (o == null) return null;
        try { return (long) Double.parseDouble(o.toString().replaceAll("[^0-9.]", "")); } catch (Exception e) { return null; }
    }
    private static BigDecimal toBd(Object o) {
        if (o == null || "null".equalsIgnoreCase(String.valueOf(o))) return null;
        try { String s = o.toString().replaceAll("[^0-9.]", ""); return s.isBlank() ? null : new BigDecimal(s); } catch (Exception e) { return null; }
    }

    /** One segment per side keyword — each order starts at a buy/sell verb. */
    private List<String> splitOrders(String text) {
        String t = text.replaceAll("(?<=\\d),(?=\\d)", "");   // 1,000 → 1000
        Matcher m = SIDE.matcher(t);
        List<Integer> starts = new ArrayList<>();
        while (m.find()) starts.add(m.start());
        if (starts.isEmpty()) return List.of(t);
        List<String> segs = new ArrayList<>();
        for (int i = 0; i < starts.size(); i++) {
            int end = (i + 1 < starts.size()) ? starts.get(i + 1) : t.length();
            segs.add(t.substring(starts.get(i), end));
        }
        return segs;
    }

    private ParsedOrder parseSegment(String seg, Map<String, Security> bySymbol) {
        Matcher sm = SIDE.matcher(seg);
        if (!sm.find()) return null;
        String kw = sm.group(1).toLowerCase();
        String side = Set.of("sell", "offload", "dispose").contains(kw) ? "SELL" : "BUY";

        String work = seg;

        BigDecimal orderYield = null; String basis = "PRICE";
        Matcher ym = YIELD.matcher(work);
        if (ym.find()) { orderYield = bd(ym.group(1)); basis = "YIELD"; work = blank(work, ym.start(), ym.end()); }

        BigDecimal price = null;
        if (orderYield == null) {
            Matcher pm = PRICE.matcher(work);
            if (pm.find()) { price = bd(pm.group(1) != null ? pm.group(1) : pm.group(2)); work = blank(work, pm.start(), pm.end()); }
        }

        Long qty = null;
        Matcher qm = QTY_LABELED.matcher(work);
        if (qm.find()) { qty = Long.parseLong(qm.group(1)); work = blank(work, qm.start(), qm.end()); }
        else { Matcher nm = INT.matcher(work); if (nm.find()) { qty = Long.parseLong(nm.group(1)); work = blank(work, nm.start(), nm.end()); } }

        Security sec = resolveSymbol(work, seg, bySymbol);
        boolean market = price == null && orderYield == null;

        if (sec == null)
            return new ParsedOrder(false, side, null, null, null, qty, price, basis, orderYield,
                    "Couldn't identify the security — name a valid ticker or company.");
        if (qty == null || qty <= 0)
            return new ParsedOrder(false, side, sec.getSymbol(), sec.getId(), sec.getName(), null, price, basis, orderYield,
                    "Couldn't read a quantity.");
        return new ParsedOrder(true, side, sec.getSymbol(), sec.getId(), sec.getName(), qty, price, basis, orderYield,
                market ? "Market order" : null);
    }

    private Security resolveSymbol(String residual, String full, Map<String, Security> bySymbol) {
        for (String tok : full.toUpperCase().split("[^A-Z0-9]+"))
            if (tok.length() >= 2 && bySymbol.containsKey(tok)) return bySymbol.get(tok);

        StringBuilder q = new StringBuilder();
        for (String tok : residual.toLowerCase().split("[^a-z0-9]+")) {
            if (tok.isBlank() || STOP.contains(tok) || tok.matches("[0-9.]+")) continue;
            q.append(tok).append(' ');
        }
        String query = q.toString().trim();
        if (query.isEmpty()) return null;
        try {
            List<AiHit> hits = search.search(query, 45, 1);
            if (!hits.isEmpty()) return securityRepo.findById(hits.get(0).securityId()).orElse(null);
        } catch (Exception ignored) {}
        return null;
    }

    private static String blank(String s, int a, int b) { return s.substring(0, a) + " ".repeat(b - a) + s.substring(b); }
    private static BigDecimal bd(String s) { try { return new BigDecimal(s); } catch (Exception e) { return null; } }
}
