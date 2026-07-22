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

/* ------------------------------------------------------------------ additions for the chart screen */

/**
 * MACD — the difference between a fast and a slow EMA, its own EMA (signal), and the gap between
 * them (histogram). Returned aligned to the same times so all three can be drawn in one pane.
 *
 * The two EMAs warm up at different bars, so the series are trimmed to where BOTH exist. Drawing a
 * MACD line that starts before its slow EMA is meaningful is drawing noise.
 */
export function macd(candles: Candle[], fast = 12, slow = 26, signalPeriod = 9):
    { macd: Point[]; signal: Point[]; histogram: Point[] } {
  const empty = { macd: [], signal: [], histogram: [] };
  if (candles.length < slow + signalPeriod) return empty;

  const ef = ema(candles, fast);
  const es = ema(candles, slow);
  const byTime = new Map(ef.map((p) => [String(p.time), p.value]));

  const line: Point[] = [];
  for (const s of es) {
    const f = byTime.get(String(s.time));
    if (f == null) continue;                 // only where both EMAs exist
    line.push({ time: s.time, value: f - s.value });
  }
  if (line.length < signalPeriod) return empty;

  // EMA of the MACD line itself, seeded from its own SMA — same construction as ema() above.
  const k = 2 / (signalPeriod + 1);
  let seed = 0;
  for (let i = 0; i < signalPeriod; i++) seed += line[i].value;
  let prev = seed / signalPeriod;
  const signal: Point[] = [{ time: line[signalPeriod - 1].time, value: prev }];
  for (let i = signalPeriod; i < line.length; i++) {
    prev = line[i].value * k + prev * (1 - k);
    signal.push({ time: line[i].time, value: prev });
  }
  const sigAt = new Map(signal.map((p) => [String(p.time), p.value]));
  const histogram = line
    .filter((p) => sigAt.has(String(p.time)))
    .map((p) => ({ time: p.time, value: p.value - (sigAt.get(String(p.time)) as number) }));

  return { macd: line.filter((p) => sigAt.has(String(p.time))), signal, histogram };
}

/**
 * VWAP — volume-weighted average price, cumulative from the first bar supplied.
 *
 * Deliberately anchored to the start of the data rather than to a rolling window: VWAP is a
 * session benchmark, and a "rolling VWAP" is a different indicator wearing the same name. Bars
 * with no volume contribute nothing rather than dragging the average toward the typical price.
 */
export function vwap(candles: Candle[]): Point[] {
  const out: Point[] = [];
  let pv = 0, vol = 0;
  for (const c of candles) {
    const v = c.volume ?? 0;
    if (v > 0) {
      pv += ((c.high + c.low + c.close) / 3) * v;
      vol += v;
    }
    if (vol > 0) out.push({ time: c.time, value: pv / vol });
  }
  return out;
}

/** Heikin-Ashi candles — smoothed bars that make trend legible. A transform, not an indicator. */
export function heikinAshi(candles: Candle[]): Candle[] {
  const out: Candle[] = [];
  for (let i = 0; i < candles.length; i++) {
    const c = candles[i];
    const close = (c.open + c.high + c.low + c.close) / 4;
    const open = i === 0 ? (c.open + c.close) / 2 : (out[i - 1].open + out[i - 1].close) / 2;
    out.push({
      time: c.time, open, close,
      high: Math.max(c.high, open, close),
      low: Math.min(c.low, open, close),
      volume: c.volume,
    });
  }
  return out;
}

/** Average True Range — Wilder-smoothed, the standard volatility measure for position sizing. */
export function atr(candles: Candle[], period = 14): Point[] {
  if (candles.length <= period) return [];
  const tr: number[] = [];
  for (let i = 1; i < candles.length; i++) {
    const c = candles[i], p = candles[i - 1];
    tr.push(Math.max(c.high - c.low, Math.abs(c.high - p.close), Math.abs(c.low - p.close)));
  }
  let sum = 0;
  for (let i = 0; i < period; i++) sum += tr[i];
  let prev = sum / period;
  const out: Point[] = [{ time: candles[period].time, value: prev }];
  for (let i = period; i < tr.length; i++) {
    prev = (prev * (period - 1) + tr[i]) / period;   // Wilder, not a rolling mean
    out.push({ time: candles[i + 1].time, value: prev });
  }
  return out;
}
