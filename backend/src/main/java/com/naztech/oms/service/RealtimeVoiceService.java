package com.naztech.oms.service;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Builds the live speech-to-speech session: what the voice assistant knows, how it should speak, and
 * what it is allowed to look up while you are talking to it.
 *
 * <p>The difference between this and the OMS's existing voice feature is worth stating plainly,
 * because they look the same from the outside and are not. Today the dealer speaks, the browser
 * transcribes, the text goes to a model, the answer comes back as text, and a second service reads it
 * aloud. Four hops, each waiting for the last — a form submission with a microphone attached. The
 * realtime model <em>hears</em> and <em>speaks</em>; it can be interrupted, it hears hesitation, and it
 * answers in about the time a person would. That is why it is worth the extra plumbing.
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
        if (!openai.enabled()) {
            return null;
        }
        return openai.realtimeSession(instructions(accountId, lang), tools());
    }

    /**
     * The system prompt plus the whole live OMS picture — indices, breadth, movers, sectors, every
     * instrument, and this dealer's holdings and P&amp;L.
     *
     * <p>A generic voice assistant asked "how is my portfolio doing today?" has nothing to say. This one
     * is handed the answer before the question is asked. It is a large prompt, and it is worth every
     * token: the alternative is a model that sounds impressive and knows nothing.
     */
    private String instructions(Long accountId, String lang) {
        String spoken = """

                YOU ARE SPEAKING ALOUD, NOT WRITING. Therefore:
                - Keep answers SHORT — two or three sentences unless asked for detail. A spoken paragraph
                  is unbearable; a written one is fine. This is the single most important rule.
                - Never read out markdown, bullet characters, asterisks or tables. Say the numbers.
                - Prices are in taka. Say "three hundred and five taka forty", not "305.40 BDT".
                - When you are asked for a price and the market has moved since this conversation began,
                  call the get_quote tool rather than reading the figure below. A stale price spoken with
                  confidence is worse than saying you will check.
                - You may be interrupted. If you are, stop immediately and listen.
                - Give the not-financial-advice disclaimer ONCE, at the end of the first substantive
                  answer, and never again in the same conversation. Repeating it aloud every turn is
                  intolerable to listen to.
                """;

        String language = "bn".equalsIgnoreCase(lang)
                ? "\nSPEAK IN BANGLA (বাংলা). The dealer is speaking Bangla; answer in Bangla, "
                  + "including numbers and the disclaimer. Use English only for ticker symbols.\n"
                : "\nSpeak in clear English. If the dealer switches to Bangla, switch with them.\n";

        return advisor.systemPrompt()
                + spoken
                + language
                + "\n\n================ LIVE OMS DATA (the only source of facts) ================\n"
                + advisor.liveContext(accountId);
    }

    /**
     * What the assistant may look up mid-sentence.
     *
     * <p>The context above is a snapshot taken when the call was placed. A conversation lasts minutes
     * and the market moves inside them, so the assistant is given a way to ask for a fresh price rather
     * than confidently reciting a stale one. The browser answers the call against the OMS's own market
     * endpoint and hands the result back over the data channel — so the tool result is the same number
     * on the dealer's screen, not a second opinion.
     */
    private List<Map<String, Object>> tools() {
        return List.of(Map.of(
                "type", "function",
                "name", "get_quote",
                "description", "Get the CURRENT live price of a DSE/CSE instrument. Use this whenever "
                        + "the dealer asks what something is trading at right now, or before quoting any "
                        + "price you intend to act on — the data in your instructions is a snapshot from "
                        + "the start of this conversation and the market has moved since.",
                "parameters", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "symbol", Map.of(
                                        "type", "string",
                                        "description", "The ticker symbol, e.g. GP, BRACBANK, SQURPHARMA, LHBL")),
                        "required", List.of("symbol"))));
    }
}
