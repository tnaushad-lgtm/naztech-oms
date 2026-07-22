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

/* ==================================================================================================
 * Catalogue additions.
 *
 * Everything above predates the indicator picker and is kept exactly as it was: PriceChart and
 * CandleWidget consume INDICATORS/compute directly, and changing those shapes would break both.
 * What follows is the wider set the picker offers, written to the canonical definitions rather
 * than to whatever is quickest — a trader comparing against another terminal will notice.
 * ================================================================================================ */

/** True range per bar. `tr[j]` belongs to `candles[j + 1]`; the first bar has no previous close. */
function trueRanges(candles: Candle[]): number[] {
  const tr: number[] = [];
  for (let i = 1; i < candles.length; i++) {
    const c = candles[i], p = candles[i - 1];
    tr.push(Math.max(c.high - c.low, Math.abs(c.high - p.close), Math.abs(c.low - p.close)));
  }
  return tr;
}

/**
 * Wilder's smoothing, aligned to the input. Seeded with the SMA of the first `period` values, then
 * carried forward with weight (period-1)/period. Indices before the seed are null rather than 0 —
 * a zero would be drawn as a real reading.
 */
function wilderSmooth(raw: number[], period: number): (number | null)[] {
  const out: (number | null)[] = new Array(raw.length).fill(null);
  if (period < 1 || raw.length < period) return out;
  let sum = 0;
  for (let i = 0; i < period; i++) sum += raw[i];
  let prev = sum / period;
  out[period - 1] = prev;
  for (let i = period; i < raw.length; i++) {
    prev = (prev * (period - 1) + raw[i]) / period;
    out[i] = prev;
  }
  return out;
}

/** Simple moving average over an already-computed point series (used to smooth %K into %D). */
function smoothPoints(pts: Point[], period: number): Point[] {
  if (period <= 1) return pts;
  if (pts.length < period) return [];
  const out: Point[] = [];
  let sum = 0;
  for (let i = 0; i < pts.length; i++) {
    sum += pts[i].value;
    if (i >= period) sum -= pts[i - period].value;
    if (i >= period - 1) out.push({ time: pts[i].time, value: sum / period });
  }
  return out;
}

/** Weighted moving average — linear weights 1..period, heaviest on the most recent close. */
export function wma(candles: Candle[], period: number): Point[] {
  if (period < 1 || candles.length < period) return [];
  const denom = (period * (period + 1)) / 2;
  const out: Point[] = [];
  for (let i = period - 1; i < candles.length; i++) {
    let acc = 0;
    for (let k = 0; k < period; k++) acc += candles[i - period + 1 + k].close * (k + 1);
    out.push({ time: candles[i].time, value: acc / denom });
  }
  return out;
}

/** Rolling standard deviation of closes — population, the same convention bollinger() uses. */
export function stddev(candles: Candle[], period = 20): Point[] {
  const mid = sma(candles, period);
  const out: Point[] = [];
  for (let j = 0; j < mid.length; j++) {
    const i = j + period - 1;
    let variance = 0;
    for (let k = i - period + 1; k <= i; k++) {
      const d = candles[k].close - mid[j].value;
      variance += d * d;
    }
    out.push({ time: mid[j].time, value: Math.sqrt(variance / period) });
  }
  return out;
}

/**
 * Stochastic oscillator. Raw %K is where the close sits in the high-low range of the last
 * `kPeriod` bars; the reported %K is that smoothed over `kSmooth` (the "slow" stochastic every
 * terminal shows by default), and %D is %K smoothed again over `dPeriod`.
 *
 * A completely flat window has no range and no defined position in it. 50 — dead centre, neither
 * overbought nor oversold — is the honest reading; 0 or 100 would assert an extreme that the bars
 * do not support.
 */
export function stochastic(candles: Candle[], kPeriod = 14, kSmooth = 3, dPeriod = 3):
    { k: Point[]; d: Point[] } {
  if (kPeriod < 1 || candles.length < kPeriod) return { k: [], d: [] };
  const rawK: Point[] = [];
  for (let i = kPeriod - 1; i < candles.length; i++) {
    let hh = -Infinity, ll = Infinity;
    for (let j = i - kPeriod + 1; j <= i; j++) {
      if (candles[j].high > hh) hh = candles[j].high;
      if (candles[j].low < ll) ll = candles[j].low;
    }
    const range = hh - ll;
    rawK.push({ time: candles[i].time, value: range === 0 ? 50 : (100 * (candles[i].close - ll)) / range });
  }
  const k = smoothPoints(rawK, kSmooth);
  return { k, d: smoothPoints(k, dPeriod) };
}

/**
 * ADX with +DI / -DI (Wilder's Directional Movement).
 *
 * Directional movement is exclusive: only the larger of the two range extensions counts, and only
 * when it is positive — an inside bar contributes nothing to either side. ADX is then Wilder's
 * smoothing of DX, which is why it needs roughly 2x`period` bars before it reports anything.
 */
export function adx(candles: Candle[], period = 14):
    { adx: Point[]; plusDI: Point[]; minusDI: Point[] } {
  const empty = { adx: [], plusDI: [], minusDI: [] };
  if (period < 1 || candles.length < period * 2) return empty;

  const tr = trueRanges(candles);
  const plusDM: number[] = [], minusDM: number[] = [];
  for (let i = 1; i < candles.length; i++) {
    const up = candles[i].high - candles[i - 1].high;
    const down = candles[i - 1].low - candles[i].low;
    plusDM.push(up > down && up > 0 ? up : 0);
    minusDM.push(down > up && down > 0 ? down : 0);
  }

  const trS = wilderSmooth(tr, period);
  const pS = wilderSmooth(plusDM, period);
  const mS = wilderSmooth(minusDM, period);

  const plusDI: Point[] = [], minusDI: Point[] = [], dx: number[] = [];
  for (let j = 0; j < tr.length; j++) {
    const t = trS[j], p = pS[j], m = mS[j];
    if (t == null || p == null || m == null || t === 0) continue;
    const pdi = (100 * p) / t, mdi = (100 * m) / t;
    plusDI.push({ time: candles[j + 1].time, value: pdi });
    minusDI.push({ time: candles[j + 1].time, value: mdi });
    const sum = pdi + mdi;
    dx.push(sum === 0 ? 0 : (100 * Math.abs(pdi - mdi)) / sum);   // stays index-aligned with plusDI
  }

  const smoothed = wilderSmooth(dx, period);
  const out: Point[] = [];
  for (let n = 0; n < dx.length; n++) {
    if (smoothed[n] != null) out.push({ time: plusDI[n].time, value: smoothed[n] as number });
  }
  return { adx: out, plusDI, minusDI };
}

/** On-Balance Volume — running total of volume signed by the close-to-close direction. */
export function obv(candles: Candle[]): Point[] {
  const out: Point[] = [];
  let acc = 0;
  for (let i = 0; i < candles.length; i++) {
    if (i > 0) {
      const d = candles[i].close - candles[i - 1].close;
      const v = candles[i].volume ?? 0;
      acc += d > 0 ? v : d < 0 ? -v : 0;      // an unchanged close moves OBV not at all
    }
    out.push({ time: candles[i].time, value: acc });
  }
  return out;
}

/**
 * Commodity Channel Index. The 0.015 constant is Lambert's, chosen so roughly 70-80% of readings
 * fall within ±100; it is part of the definition, not a tunable.
 *
 * Note the denominator is MEAN ABSOLUTE deviation, not standard deviation — a common and silent
 * error that produces a plausible-looking but consistently wrong line.
 */
export function cci(candles: Candle[], period = 20): Point[] {
  if (period < 1 || candles.length < period) return [];
  const tp = candles.map((c) => (c.high + c.low + c.close) / 3);
  const out: Point[] = [];
  for (let i = period - 1; i < candles.length; i++) {
    let sum = 0;
    for (let j = i - period + 1; j <= i; j++) sum += tp[j];
    const mean = sum / period;
    let md = 0;
    for (let j = i - period + 1; j <= i; j++) md += Math.abs(tp[j] - mean);
    md /= period;
    out.push({ time: candles[i].time, value: md === 0 ? 0 : (tp[i] - mean) / (0.015 * md) });
  }
  return out;
}

/** Williams %R — where the close sits in the recent range, on a 0 (top) to -100 (bottom) scale. */
export function williamsR(candles: Candle[], period = 14): Point[] {
  if (period < 1 || candles.length < period) return [];
  const out: Point[] = [];
  for (let i = period - 1; i < candles.length; i++) {
    let hh = -Infinity, ll = Infinity;
    for (let j = i - period + 1; j <= i; j++) {
      if (candles[j].high > hh) hh = candles[j].high;
      if (candles[j].low < ll) ll = candles[j].low;
    }
    const range = hh - ll;
    out.push({ time: candles[i].time, value: range === 0 ? -50 : (-100 * (hh - candles[i].close)) / range });
  }
  return out;
}

/** Rate of change — percentage move against the close `period` bars ago. */
export function roc(candles: Candle[], period = 12): Point[] {
  if (period < 1 || candles.length <= period) return [];
  const out: Point[] = [];
  for (let i = period; i < candles.length; i++) {
    const prev = candles[i - period].close;
    out.push({ time: candles[i].time, value: prev === 0 ? 0 : (100 * (candles[i].close - prev)) / prev });
  }
  return out;
}

/** Donchian channel — the highest high and lowest low of the last `period` bars. */
export function donchian(candles: Candle[], period = 20): { upper: Point[]; lower: Point[]; mid: Point[] } {
  const upper: Point[] = [], lower: Point[] = [], mid: Point[] = [];
  if (period < 1 || candles.length < period) return { upper, lower, mid };
  for (let i = period - 1; i < candles.length; i++) {
    let hh = -Infinity, ll = Infinity;
    for (let j = i - period + 1; j <= i; j++) {
      if (candles[j].high > hh) hh = candles[j].high;
      if (candles[j].low < ll) ll = candles[j].low;
    }
    upper.push({ time: candles[i].time, value: hh });
    lower.push({ time: candles[i].time, value: ll });
    mid.push({ time: candles[i].time, value: (hh + ll) / 2 });
  }
  return { upper, lower, mid };
}

/** Keltner channel — an EMA centre with ATR-scaled bands. Reacts to range, not to dispersion. */
export function keltner(candles: Candle[], period = 20, mult = 2):
    { upper: Point[]; mid: Point[]; lower: Point[] } {
  const raw = ema(candles, period);
  const upper: Point[] = [], lower: Point[] = [], mid: Point[] = [];
  if (!raw.length) return { upper, mid, lower };

  const tr = trueRanges(candles);
  const atrS = wilderSmooth(tr, period);
  const atrAt = new Map<string, number>();
  for (let j = 0; j < tr.length; j++) {
    if (atrS[j] != null) atrAt.set(String(candles[j + 1].time), atrS[j] as number);
  }

  for (const m of raw) {
    const a = atrAt.get(String(m.time));
    if (a == null) continue;                 // only where both the EMA and the ATR exist
    mid.push(m);
    upper.push({ time: m.time, value: m.value + mult * a });
    lower.push({ time: m.time, value: m.value - mult * a });
  }
  return { upper, mid, lower };
}

/**
 * Supertrend — an ATR band that flips side when price closes through it.
 *
 * The carry-forward rules are what make it a trend follower rather than a pair of noisy bands: a
 * final band only moves in the trend's favour, and only resets when price closes beyond it.
 * Returned as two series so the up-leg and down-leg can be drawn in bull and bear colours; each
 * carries whitespace ({time} with no value) where the other is active, which is how
 * lightweight-charts is told to break a line rather than join across the gap.
 */
export function supertrend(candles: Candle[], period = 10, mult = 3):
    { up: ({ time: number | string; value?: number })[]; down: ({ time: number | string; value?: number })[] } {
  const up: ({ time: number | string; value?: number })[] = [];
  const down: ({ time: number | string; value?: number })[] = [];
  if (period < 1 || candles.length <= period) return { up, down };

  const tr = trueRanges(candles);
  const atrS = wilderSmooth(tr, period);

  let prevUpper = Infinity, prevLower = -Infinity, trendUp = true, started = false;
  for (let j = 0; j < tr.length; j++) {
    const a = atrS[j];
    if (a == null) continue;
    const i = j + 1;
    const c = candles[i], prev = candles[i - 1];
    const hl2 = (c.high + c.low) / 2;
    const basicUpper = hl2 + mult * a;
    const basicLower = hl2 - mult * a;

    const upperFinal = !started || basicUpper < prevUpper || prev.close > prevUpper ? basicUpper : prevUpper;
    const lowerFinal = !started || basicLower > prevLower || prev.close < prevLower ? basicLower : prevLower;

    if (!started) { trendUp = c.close >= lowerFinal; started = true; }
    else if (trendUp && c.close < lowerFinal) trendUp = false;
    else if (!trendUp && c.close > upperFinal) trendUp = true;

    const value = trendUp ? lowerFinal : upperFinal;
    if (trendUp) { up.push({ time: c.time, value }); down.push({ time: c.time }); }
    else { down.push({ time: c.time, value }); up.push({ time: c.time }); }

    prevUpper = upperFinal;
    prevLower = lowerFinal;
  }
  return { up, down };
}

/**
 * Classic floor pivots, computed from the last COMPLETED session.
 *
 * A pivot taken from the session in progress is not a pivot — it moves every tick, and the whole
 * point of the level is that it was fixed before the open. Bars are grouped by calendar day from
 * their own timestamps and the most recent finished day is used, so an intraday chart gets
 * yesterday's levels and a daily chart gets the previous bar's. Fewer than two days of data means
 * there is no completed prior session, and the honest answer is no levels at all.
 */
export function pivotPoints(candles: Candle[]): { label: string; value: number }[] {
  const days = new Map<number, { high: number; low: number; close: number }>();
  const order: number[] = [];
  for (const c of candles) {
    const t = typeof c.time === "number" ? c.time : Math.floor(new Date(c.time).getTime() / 1000);
    if (!t || Number.isNaN(t)) continue;
    const day = Math.floor(t / 86400);
    const cur = days.get(day);
    if (!cur) { days.set(day, { high: c.high, low: c.low, close: c.close }); order.push(day); }
    else {
      cur.high = Math.max(cur.high, c.high);
      cur.low = Math.min(cur.low, c.low);
      cur.close = c.close;                    // the day's last close, since bars arrive in order
    }
  }
  if (order.length < 2) return [];
  const d = days.get(order[order.length - 2]);
  if (!d) return [];

  const { high: h, low: l, close: c } = d;
  const p = (h + l + c) / 3;
  const range = h - l;
  return [
    { label: "R3", value: h + 2 * (p - l) },
    { label: "R2", value: p + range },
    { label: "R1", value: 2 * p - l },
    { label: "P",  value: p },
    { label: "S1", value: 2 * p - h },
    { label: "S2", value: p - range },
    { label: "S3", value: l - 2 * (h - p) },
  ];
}
