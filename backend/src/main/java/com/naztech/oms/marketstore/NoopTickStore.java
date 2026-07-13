package com.naztech.oms.marketstore;

import com.naztech.oms.api.Dtos.Candle;

import java.math.BigDecimal;
import java.util.List;

/**
 * No tick store configured. The OMS behaves exactly as it did before QuestDB existed: ticks are
 * published to the browser and forgotten, and candles come from the trade table.
 *
 * <p>This is what makes the tick store optional rather than a new hard dependency — the same
 * posture the AI advisor takes without a Gemini key.
 */
public class NoopTickStore implements TickStore {

    @Override
    public void tick(Long securityId, String symbol, BigDecimal price, long qty, long epochMillis) {
        // deliberately nothing
    }

    @Override
    public List<Candle> candles(Long securityId, int bucketSeconds, int limit) {
        return List.of();
    }

    @Override
    public boolean isLive() {
        return false;
    }

    @Override
    public String describe() {
        return "none (candles come from the trade table; no tick history is kept)";
    }
}
