/**
 * Technical indicators — DSE OMS chart overlays (MoM §20: RSI, EMA, moving average, configurable).
 *
 * Pure functions over a candle series, deliberately separate from the chart component so the maths
 * can be reasoned about and tested without mounting a chart. Every function returns points aligned to
 * the input times, with the warm-up period omitted rather than zero-filled: a moving average that
 * reports 0 for its first 20 bars is not a moving average, it is a cliff, and it makes the chart lie
 * at exactly the left edge a trader uses to judge trend.
 */

export type Candle = { time: number | string; open: number; high: number; low: number; close: number; volume?: number };
export type Point = { time: number | string; value: number };

/**
 * Simple moving average. The unweighted mean of the last `period` closes.
 * Emitted from bar `period-1` onward; earlier bars have no defined value.
 */
export function sma(candles: Candle[], period: number): Point[] {
  if (period < 1 || candles.length < period) return [];
  const out: Point[] = [];
  let sum = 0;
  for (let i = 0; i < candles.length; i++) {
    sum += candles[i].close;
    if (i >= period) sum -= candles[i - period].close;
    if (i >= period - 1) out.push({ time: candles[i].time, value: sum / period });
  }
  return out;
}

/**
 * Exponential moving average, seeded with the SMA of the first `period` closes.
 *
 * Seeding matters: starting the recursion from the first close alone makes the early curve a
 * function of one arbitrary bar, and the distortion takes several periods to decay. Seeding from the
 * SMA is the standard construction and is what a trader comparing against another terminal expects.
 */
export function ema(candles: Candle[], period: number): Point[] {
  if (period < 1 || candles.length < period) return [];
  const k = 2 / (period + 1);
  const out: Point[] = [];
  let seed = 0;
  for (let i = 0; i < period; i++) seed += candles[i].close;
  let prev = seed / period;
  out.push({ time: candles[period - 1].time, value: prev });
  for (let i = period; i < candles.length; i++) {
    prev = candles[i].close * k + prev * (1 - k);
    out.push({ time: candles[i].time, value: prev });
  }
  return out;
}

/**
 * Relative Strength Index — Wilder's smoothing, the original and the one every terminal quotes.
 *
 * Wilder's smoothing is NOT a simple average of the last `period` gains: after the initial seed it
 * carries the previous average forward with weight (period-1)/period. Using a plain rolling mean
 * gives a visibly different, jumpier line, and traders comparing it against another platform will
 * (rightly) call it wrong.
 *
 * Returns values in 0..100. An all-gains window is 100 by definition; guard the zero-loss case
 * rather than dividing by zero and emitting NaN, which silently breaks the whole series downstream.
 */
export function rsi(candles: Candle[], period = 14): Point[] {
  if (candles.length <= period) return [];
  let gain = 0, loss = 0;
  for (let i = 1; i <= period; i++) {
    const d = candles[i].close - candles[i - 1].close;
    if (d >= 0) gain += d; else loss -= d;
  }
  let avgGain = gain / period;
  let avgLoss = loss / period;

  const out: Point[] = [{
    time: candles[period].time,
    value: avgLoss === 0 ? 100 : 100 - 100 / (1 + avgGain / avgLoss),
  }];

  for (let i = period + 1; i < candles.length; i++) {
    const d = candles[i].close - candles[i - 1].close;
    const g = d > 0 ? d : 0;
    const l = d < 0 ? -d : 0;
    avgGain = (avgGain * (period - 1) + g) / period;   // Wilder, not a rolling mean
    avgLoss = (avgLoss * (period - 1) + l) / period;
    out.push({
      time: candles[i].time,
      value: avgLoss === 0 ? 100 : 100 - 100 / (1 + avgGain / avgLoss),
    });
  }
  return out;
}

/** Bollinger-style envelope around an SMA — returned as three aligned series. */
export function bollinger(candles: Candle[], period = 20, mult = 2): { mid: Point[]; upper: Point[]; lower: Point[] } {
  const mid = sma(candles, period);
  const upper: Point[] = [], lower: Point[] = [];
  for (let j = 0; j < mid.length; j++) {
    const i = j + period - 1;                       // index of this bar in `candles`
    let variance = 0;
    for (let k = i - period + 1; k <= i; k++) {
      const d = candles[k].close - mid[j].value;
      variance += d * d;
    }
    const sd = Math.sqrt(variance / period);
    upper.push({ time: mid[j].time, value: mid[j].value + mult * sd });
    lower.push({ time: mid[j].time, value: mid[j].value - mult * sd });
  }
  return { mid, upper, lower };
}

/** What the chart offers. `pane: "sub"` means it needs its own scale, not the price axis. */
export type IndicatorId = "SMA20" | "SMA50" | "EMA9" | "EMA21" | "BB20" | "RSI14";

export const INDICATORS: {
  id: IndicatorId; label: string; short: string; pane: "price" | "sub"; colour: string; hint: string;
}[] = [
  { id: "EMA9",  label: "EMA 9",         short: "EMA9",  pane: "price", colour: "#22d3ee", hint: "Exponential moving average, 9 bars — reacts fastest to a turn" },
  { id: "EMA21", label: "EMA 21",        short: "EMA21", pane: "price", colour: "#8b5cf6", hint: "Exponential moving average, 21 bars" },
  { id: "SMA20", label: "SMA 20",        short: "SMA20", pane: "price", colour: "#f59e0b", hint: "Simple moving average, 20 bars" },
  { id: "SMA50", label: "SMA 50",        short: "SMA50", pane: "price", colour: "#f472b6", hint: "Simple moving average, 50 bars — the slower trend" },
  { id: "BB20",  label: "Bollinger 20",  short: "BB20",  pane: "price", colour: "#2dd4bf", hint: "SMA 20 with ±2 standard deviations" },
  { id: "RSI14", label: "RSI 14",        short: "RSI14", pane: "sub",   colour: "#e879f9", hint: "Relative Strength Index, Wilder 14 — above 70 overbought, below 30 oversold" },
];

/** Compute one indicator's series from candles. Bollinger returns three lines. */
export function compute(id: IndicatorId, candles: Candle[]): Point[][] {
  switch (id) {
    case "SMA20": return [sma(candles, 20)];
    case "SMA50": return [sma(candles, 50)];
    case "EMA9":  return [ema(candles, 9)];
    case "EMA21": return [ema(candles, 21)];
    case "RSI14": return [rsi(candles, 14)];
    case "BB20": {
      const b = bollinger(candles, 20, 2);
      return [b.upper, b.mid, b.lower];
    }
    default: return [];
  }
}
