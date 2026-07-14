package com.naztech.oms.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI (ChatGPT) — the second AI provider, alongside Gemini.
 *
 * <p>Two quite different things live here, and it is worth being clear about which is which:
 *
 * <ul>
 *   <li><b>Text advice</b> ({@link #complete}) — the ordinary request/response path, the same shape as
 *       the Gemini advisor. Goes through the <em>Responses</em> API, which is what OpenAI now
 *       recommends over Chat Completions.</li>
 *   <li><b>Live voice</b> ({@link #realtimeSession}) — genuine <em>speech-to-speech</em>. Not the
 *       speak-then-transcribe-then-think-then-synthesise pipeline the OMS has today: the model hears
 *       audio and answers in audio, and you can interrupt it mid-sentence. That is why it feels alive
 *       and the old pipeline feels like a form submission.</li>
 * </ul>
 *
 * <h2>The browser never sees our API key</h2>
 * A realtime session runs over WebRTC <em>directly</em> between the browser and OpenAI — the audio
 * cannot come through this backend without adding a relay and the latency that kills the whole point.
 * So the browser needs a credential. It gets an <b>ephemeral one</b>: this service posts our real key
 * to OpenAI and receives a short-lived token ({@code ek_…}, ten minutes) scoped to one session, which
 * is what the browser holds. Shipping the real key to the front end would put it in the page source
 * of anything the dealer opens, which is the sort of decision that ends up in an audit finding.
 */
@Service
public class OpenAiService {

    private static final Logger log = LoggerFactory.getLogger(OpenAiService.class);

    private static final String RESPONSES_URL = "https://api.openai.com/v1/responses";
    private static final String CLIENT_SECRETS_URL = "https://api.openai.com/v1/realtime/client_secrets";

    @Value("${app.ai.openai.key:}")
    private String key;

    /** Text advisor model. {@code gpt-5.6-luna} is the current cost-optimised GA tier. */
    @Value("${app.ai.openai.model:gpt-5.6-luna}")
    private String model;

    /**
     * Live-voice model. The {@code -mini} is roughly three times cheaper on audio
     * ($10/$20 per 1M tokens vs $32/$64) and is the sane default while testing on a small credit —
     * realtime audio is billed per token of speech, and a chatty session spends it quickly.
     */
    @Value("${app.ai.openai.realtime-model:gpt-realtime-2.1-mini}")
    private String realtimeModel;

    @Value("${app.ai.openai.voice:marin}")
    private String voice;

    public boolean enabled() {
        return key != null && !key.isBlank();
    }

    public String model() {
        return model;
    }

    public String realtimeModel() {
        return realtimeModel;
    }

    // ------------------------------------------------------------------ text

    /**
     * One-shot text completion through the Responses API.
     *
     * <p>Note the role is {@code developer}, not {@code system} — the Responses API renamed it, and a
     * {@code system} role is silently treated as an ordinary message, which quietly turns your system
     * prompt into something the model may argue with.
     *
     * @return the answer, or {@code null} when no key is set or the call fails (the caller falls back)
     */
    @SuppressWarnings("unchecked")
    public String complete(String systemPrompt, String userMessage, double temperature, int maxTokens) {
        if (!enabled() || userMessage == null || userMessage.isBlank()) {
            return null;
        }
        try {
            List<Map<String, Object>> input = new ArrayList<>();
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                input.add(Map.of("role", "developer", "content", systemPrompt));
            }
            input.add(Map.of("role", "user", "content", userMessage));

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("input", input);
            body.put("max_output_tokens", maxTokens);

            Map<String, Object> resp = RestClient.create().post()
                    .uri(RESPONSES_URL)
                    .header("Authorization", "Bearer " + key)
                    .body(body)
                    .retrieve()
                    .body(Map.class);

            return textOf(resp);
        } catch (Exception e) {
            log.warn("OpenAI text call failed: {}", e.toString());
            return null;
        }
    }

    /**
     * Pull the answer out of a Responses payload.
     *
     * <p>The output is a list of items, and a reasoning model puts a {@code reasoning} item in front of
     * the message — so taking {@code output[0]} gets you an empty string and a confusing afternoon.
     * Take the text from every {@code output_text} part of every {@code message} item, in order.
     */
    @SuppressWarnings("unchecked")
    private static String textOf(Map<String, Object> resp) {
        if (resp == null) {
            return null;
        }
        Object error = resp.get("error");
        if (error != null) {
            log.warn("OpenAI returned an error: {}", error);
            return null;
        }
        List<Map<String, Object>> output = (List<Map<String, Object>>) resp.get("output");
        if (output == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> item : output) {
            if (!"message".equals(item.get("type"))) {
                continue;
            }
            List<Map<String, Object>> content = (List<Map<String, Object>>) item.get("content");
            if (content == null) {
                continue;
            }
            for (Map<String, Object> part : content) {
                if ("output_text".equals(part.get("type")) && part.get("text") != null) {
                    sb.append(part.get("text"));
                }
            }
        }
        String out = sb.toString().trim();
        return out.isEmpty() ? null : out;
    }

    // ------------------------------------------------------------------ live voice

    /** What the browser needs to open its own WebRTC call to OpenAI. The token is short-lived. */
    public record RealtimeSession(String token, long expiresAt, String model, String voice) {
    }

    /**
     * Mint an ephemeral token for one live-voice session, with the OMS's live market and portfolio
     * data baked into the model's instructions.
     *
     * <p>That last part is the whole point. A generic voice assistant asked "how is my portfolio doing"
     * has nothing to say. This one is handed the dealer's holdings, the index levels, the day's gainers
     * and the instrument table before it says a word — so it answers about <em>this</em> market and
     * <em>this</em> book, out loud, in the language it was spoken to in.
     *
     * @param instructions the system prompt plus the live OMS context (built by the caller)
     * @param tools        function definitions the model may call mid-conversation (e.g. a fresh quote)
     * @return the ephemeral token, or {@code null} if no key is configured or OpenAI refused
     */
    @SuppressWarnings("unchecked")
    /**
     * The voices the Realtime API will actually accept — verified against the API, not assumed.
     *
     * <p>Worth stating because it trips people up: the voices in the ChatGPT <em>app</em> (Maple, Breeze,
     * Cove, Juniper…) are <b>not</b> available here. Ask for "maple" and the session is rejected outright.
     * These ten are what the API has.
     */
    public static final List<String> VOICES =
            List.of("marin", "cedar", "alloy", "ash", "ballad", "coral", "echo", "sage", "shimmer", "verse");

    public List<String> voices() {
        return VOICES;
    }

    public String defaultVoice() {
        return voice;
    }

    public RealtimeSession realtimeSession(String instructions, List<Map<String, Object>> tools) {
        return realtimeSession(instructions, tools, null);
    }

    public RealtimeSession realtimeSession(String instructions, List<Map<String, Object>> tools, String wantVoice) {
        if (!enabled()) {
            return null;
        }
        // An unknown voice is not a small mistake — OpenAI refuses the whole session and the dealer just
        // sees "could not start". Fall back to the configured default rather than failing the call.
        String useVoice = wantVoice != null && VOICES.contains(wantVoice.toLowerCase())
                ? wantVoice.toLowerCase() : voice;
        try {
            Map<String, Object> session = new LinkedHashMap<>();
            session.put("type", "realtime");
            session.put("model", realtimeModel);
            session.put("instructions", instructions);

            // A voice agent should answer, not deliberate — and this is the setting that decides which.
            // Left on its default, the model reasons at length before speaking, and because it cannot
            // hide that silence in a conversation, it fills it: "let me think this through", "let me
            // look at Grameenphone's data, then I'll tell you clearly." Sentences that promise an
            // answer and are not one. OpenAI's own guidance says to start voice agents at 'low'.
            session.put("reasoning", Map.of("effort", "low"));

            session.put("audio", Map.of(
                    "input", Map.of(
                            // Server-side voice activity detection: the model decides when the dealer has
                            // stopped talking and may be interrupted mid-answer — which is what makes it
                            // feel like a conversation rather than a walkie-talkie.
                            "turn_detection", Map.of(
                                    "type", "server_vad",
                                    "threshold", 0.5,
                                    "prefix_padding_ms", 300,
                                    "silence_duration_ms", 500,
                                    "create_response", true,
                                    "interrupt_response", true)),
                    "output", Map.of("voice", useVoice)));
            if (tools != null && !tools.isEmpty()) {
                session.put("tools", tools);
                session.put("tool_choice", "auto");
            }

            Map<String, Object> resp = RestClient.create().post()
                    .uri(CLIENT_SECRETS_URL)
                    .header("Authorization", "Bearer " + key)
                    .body(Map.of("session", session))
                    .retrieve()
                    .body(Map.class);

            if (resp == null || resp.get("value") == null) {
                log.warn("OpenAI did not return a realtime client secret: {}", resp);
                return null;
            }
            long expiresAt = resp.get("expires_at") instanceof Number n ? n.longValue() : 0L;
            log.info("Realtime voice session minted ({}, voice={}), expires at {}", realtimeModel, useVoice, expiresAt);
            return new RealtimeSession(String.valueOf(resp.get("value")), expiresAt, realtimeModel, useVoice);
        } catch (Exception e) {
            log.warn("Could not mint an OpenAI realtime session: {}", e.toString());
            return null;
        }
    }
}
