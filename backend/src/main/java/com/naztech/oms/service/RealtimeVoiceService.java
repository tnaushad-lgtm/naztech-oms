package com.naztech.oms.service;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Builds the live speech-to-speech session: what the voice assistant knows, how it should speak, and
 * what it may look up while you are talking to it.
 *
 * <h2>What the first version got wrong</h2>
 * It was handed the text advisor's context — all 402 instruments, about ten thousand tokens — and left
 * on the model's default reasoning effort. The result was a voice agent that thought out loud and never
 * arrived: <em>"Okay, I'll keep this quick and practical, let me think through…"</em>, <em>"let me look at
 * Grameenphone's recent data, then I'll tell you clearly"</em>. Sentences that promise an answer and are
 * not one. Nobody wants to listen to a model reading a table.
 *
 * <p>Two changes fix it, and both are what the API's own guidance says to do for a voice agent:
 * <ul>
 *   <li><b>{@code reasoning.effort = low}</b> — a voice agent should answer, not deliberate. Long
 *       reasoning is exactly what you cannot hide in a conversation, because the silence is audible.</li>
 *   <li><b>A small prompt and real tools.</b> The model holds the <em>shape</em> of the market — indices,
 *       breadth, movers, sectors, the dealer's holdings — and looks up specifics when asked. Which is
 *       how a dealer works: you carry the picture, you check the number.</li>
 * </ul>
 */
@Service
public class RealtimeVoiceService {

    private final AiAdvisorService advisor;
    private final OpenAiService openai;

    public RealtimeVoiceService(AiAdvisorService advisor, OpenAiService openai) {
        this.advisor = advisor;
        this.openai = openai;
    }

    /**
     * Mint a session for one conversation.
     *
     * @param accountId whose portfolio the assistant should know about (may be null)
     * @param lang      {@code en} or {@code bn} — Bangla is a first-class language here, not a translation
     */
    public OpenAiService.RealtimeSession open(Long accountId, String lang) {
        return open(accountId, lang, null);
    }

    /** @param voice one of {@link OpenAiService#VOICES}; anything else falls back to the configured default */
    public OpenAiService.RealtimeSession open(Long accountId, String lang, String voice) {
        if (!openai.enabled()) {
            return null;
        }
        return openai.realtimeSession(instructions(accountId, lang), tools(), voice);
    }

    /**
     * Short, blunt, and about speaking rather than writing.
     *
     * <p>Every rule below was earned by listening to the thing get it wrong.
     */
    private String instructions(Long accountId, String lang) {
        String spoken = """
                You are the Naztech OMS voice assistant for a dealer on the Dhaka (DSE) and Chittagong
                (CSE) stock exchanges. You are having a SPOKEN conversation. Currency is taka (৳).

                HOW TO SPEAK — these matter more than anything else:
                - ANSWER THE QUESTION IMMEDIATELY. Lead with the answer, then one line of why.
                - NEVER say "let me think", "let me check", "let me look at that", "I'll keep this
                  quick", or any other sentence that announces an answer instead of giving one. If you
                  need a number, call the tool SILENTLY and then speak the answer. The dealer must never
                  hear you preparing to work — only the work.
                - Two or three sentences. A spoken paragraph is unbearable. Stop talking early.
                - Never read out markdown, asterisks, bullets or tables. Never say "BDT" or "**".

                SAYING PRICES — get this exactly right:
                - A price is ONE number followed by the word taka. The decimal part is part of that
                  number. It is NOT a separate quantity and it has NO unit of its own.
                - English: 258.80 is "two hundred fifty-eight point eight zero taka".
                - Bangla: ২৫৮.৮০ is "দুইশো আটান্ন দশমিক আট শূন্য টাকা".
                - NEVER attach any unit to the digits after the decimal point. Not metre, not মিটার,
                  not kilo, not paisa, not anything. Saying "two hundred fifty-eight taka, eighty
                  metres" is nonsense and it destroys the dealer's trust in every number you say.
                - When in doubt, round and say the whole number: "about two hundred fifty-nine taka".
                - You will be interrupted. When it happens, stop instantly and listen.
                - Say the not-financial-advice disclaimer ONCE, at the end of your first real answer,
                  and never again in this conversation. Repeating it aloud every turn is intolerable.

                YOU ARE INSIDE THE OMS, AND YOU CAN DRIVE IT:
                - This is the Naztech OMS — a real trading application with screens: Trader Terminal,
                  Market Depth, Order Blotter, Portfolio, Market Watch, Screener, Heatmap, Trade Tape,
                  Price Alerts, RMS/risk limits, Reports, Exchange Admin, AI Order Bot.
                - When the dealer asks WHERE something is, or asks to be TAKEN somewhere — "where is
                  market depth?", "show me my orders", "open the portfolio screen", "কোথায় মার্কেট ডেপথ
                  পাবো?" — call find_screen. It opens the screen for them AND tells you what it does.
                  Then say, in one sentence, that you have opened it and what they will see.
                - NEVER say you cannot see market depth, or that you only have a snapshot, when the
                  dealer is asking where a FEATURE is. They are asking about the software, not the data.
                  The software has a Market Depth screen with the full order book. Open it.

                USING YOUR TOOLS:
                - The market data below is a snapshot from when this call started. The market moves.
                - For ANY specific instrument's price, call get_quote. Do not read the snapshot back.
                - To find instruments by sector, category, or "what's doing well", call find_instruments.
                - To find or open a SCREEN in this application, call find_screen.
                - Call the tool first, speak second. Never narrate that you are calling it.
                """;

        String language = "bn".equalsIgnoreCase(lang)
                ? """

                  LANGUAGE — READ THIS TWICE:
                  Speak BANGLA (বাংলা / Bengali), the language of Bangladesh. NOT Hindi. NOT Urdu.
                  NOT any other Indian language. The dealer is Bangladeshi and speaks Bangla.
                  If you find yourself producing Hindi, stop and switch to Bangla immediately.
                  Answer in natural spoken Bangla, including the numbers and the disclaimer.
                  Keep ticker symbols in English (GP, BRACBANK, SQURPHARMA).
                  If the dealer switches to English, switch with them.
                  """
                : """

                  LANGUAGE: Speak clear English. If the dealer switches to Bangla, switch with them
                  immediately and stay in Bangla (Bengali — never Hindi).
                  """;

        return spoken
                + language
                + "\n===== LIVE MARKET SNAPSHOT (the shape of today; use tools for specifics) =====\n"
                + advisor.voiceContext(accountId);
    }

    /**
     * What the assistant may look up mid-sentence.
     *
     * <p>These exist so the prompt can stay small. Rather than reciting a table of 402 instruments at
     * the model and hoping it finds the row, we give it the two questions a dealer actually asks —
     * <em>"what is X trading at"</em> and <em>"what is doing well"</em> — and let it fetch the answer
     * from the OMS's own market data, which is the same number on the dealer's screen.
     */
    private List<Map<String, Object>> tools() {
        return List.of(
                Map.of(
                        "type", "function",
                        "name", "get_quote",
                        "description", "Get the CURRENT live price of one DSE/CSE instrument. Call this "
                                + "whenever the dealer names an instrument — the snapshot in your "
                                + "instructions is from the start of the call and the market has moved. "
                                + "Call it silently; do not say you are checking.",
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "symbol", Map.of(
                                                "type", "string",
                                                "description", "Ticker symbol, e.g. GP, BRACBANK, SQURPHARMA, LHBL, BEXIMCO")),
                                "required", List.of("symbol"))),

                Map.of(
                        "type", "function",
                        "name", "find_instruments",
                        "description", "Find instruments that match what the dealer is asking for — by "
                                + "sector (Bank, Pharmaceuticals, Telecommunication, Fuel & Power, Cement…), "
                                + "by DSE share category (A/B/N/Z/G), or simply the best or worst performers "
                                + "today. Use this for questions like 'which shares are doing well today?', "
                                + "'show me good bank stocks', 'what is falling?'. Call it silently.",
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "sector", Map.of("type", "string",
                                                "description", "Optional sector filter, e.g. Bank, Pharmaceuticals"),
                                        "category", Map.of("type", "string",
                                                "description", "Optional DSE category: A, B, N, Z or G"),
                                        "order", Map.of("type", "string", "enum", List.of("gainers", "losers", "active"),
                                                "description", "Sort by biggest risers, biggest fallers, or most traded"),
                                        "limit", Map.of("type", "integer",
                                                "description", "How many to return; keep it small, 3 to 5, because this is spoken")),
                                "required", List.of())),

                // The one the first version was missing, and the dealer asked for within a minute:
                // "where do I find market depth?" It answered that it could not see market depth — a
                // question about the software, answered as though it were a question about the data.
                Map.of(
                        "type", "function",
                        "name", "find_screen",
                        "description", "Find a SCREEN or FEATURE in this OMS application and OPEN it for "
                                + "the dealer. Use this whenever they ask where something is, or ask to be "
                                + "taken somewhere: 'where is market depth?', 'show me my order blotter', "
                                + "'open my portfolio', 'কোথায় মার্কেট ডেপথ দেখব?'. It navigates the "
                                + "dealer's screen and returns what that screen does and how to use it. "
                                + "Call it silently, then tell them you have opened it.",
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "query", Map.of(
                                                "type", "string",
                                                "description", "What the dealer is looking for, in their own words, "
                                                        + "in English — e.g. 'market depth order book', "
                                                        + "'order blotter my orders', 'portfolio holdings P&L', "
                                                        + "'place a buy order', 'risk limits', 'price alerts'")),
                                "required", List.of("query"))));
    }
}
