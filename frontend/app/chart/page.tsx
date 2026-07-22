"use client";

/**
 * Advanced Chart — the full-screen charting workspace.
 *
 * The terminal already carries an inline chart, and it is deliberately small: it sits beside the
 * ticket and the ladder, so it can only ever be a glance. This screen is the other half — the chart
 * as the subject rather than the sidebar.
 *
 * What makes it a chart *in an OMS* rather than a generic chart, and the reason it is worth building
 * at all: it draws YOUR OWN orders and fills on the price. A working limit shows as a labelled price
 * line, an execution as a marker on the bar it printed in. On a public chart you look at the market;
 * here you look at your position in it. Nothing else on the screen can answer "where am I relative to
 * where it has traded" in one glance.
 */

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Shell } from "@/components/Shell";
import { ComboBox, ComboItem } from "@/components/ComboBox";
import { useLive } from "@/lib/useLive";
import { get, API } from "@/lib/api";
import { nf } from "@/lib/format";
import { chartBase, candleColours, cssRGB } from "@/lib/chartTheme";
import {
  Candle, Point, sma, ema, rsi, bollinger, macd, vwap, atr, heikinAshi,
} from "@/lib/indicators";

type Sec = { securityId: number; symbol: string; name: string; ltp: number; assetClass: string; changePct?: number };
type Order = {
  id: number; symbol: string; side: string; price: number; quantity: number;
  filledQty?: number; avgFillPrice?: number; status: string; createdAt?: string;
};

/** An order of ours that has traded. Not the public tape — see loadMine(). */
type Fill = { id: number; side: string; price: number; quantity: number; at: string };

/** Statuses after which an order can no longer rest on the book. */
const TERMINAL = new Set(["FILLED", "CANCELLED", "REJECTED", "EXPIRED"]);

const TFS = [
  { k: "1m", label: "1m" }, { k: "5m", label: "5m" },
  { k: "15m", label: "15m" }, { k: "1d", label: "1D" },
];
const TYPES = [
  { k: "candles", label: "Candles" },
  { k: "heikin", label: "Heikin-Ashi" },
  { k: "line", label: "Line" },
  { k: "area", label: "Area" },
] as const;

/** Price-pane overlays. Each returns one or more line series. */
const OVERLAYS = [
  { id: "EMA9",  label: "EMA 9",        colour: "#22d3ee", fn: (c: Candle[]) => [ema(c, 9)] },
  { id: "EMA21", label: "EMA 21",       colour: "#8b5cf6", fn: (c: Candle[]) => [ema(c, 21)] },
  { id: "SMA20", label: "SMA 20",       colour: "#f59e0b", fn: (c: Candle[]) => [sma(c, 20)] },
  { id: "SMA50", label: "SMA 50",       colour: "#f472b6", fn: (c: Candle[]) => [sma(c, 50)] },
  { id: "BB20",  label: "Bollinger 20", colour: "#2dd4bf", fn: (c: Candle[]) => { const b = bollinger(c, 20, 2); return [b.upper, b.mid, b.lower]; } },
  { id: "VWAP",  label: "VWAP",         colour: "#facc15", fn: (c: Candle[]) => [vwap(c)] },
] as const;

/** Sub-pane studies. These have their own scale and cannot share the price axis. */
const STUDIES = [
  { id: "RSI14", label: "RSI 14", colour: "#e879f9" },
  { id: "MACD",  label: "MACD",   colour: "#38bdf8" },
  { id: "ATR14", label: "ATR 14", colour: "#fb923c" },
] as const;

const STORE = "oms_advchart";

export default function ChartPage() {
  const [secs, setSecs] = useState<Sec[]>([]);
  const [securityId, setSecurityId] = useState<number | null>(null);
  const [tf, setTf] = useState("5m");
  const [type, setType] = useState<(typeof TYPES)[number]["k"]>("candles");
  const [overlays, setOverlays] = useState<string[]>(["EMA9", "VWAP"]);
  const [study, setStudy] = useState<string | null>("RSI14");
  const [showMine, setShowMine] = useState(true);
  const [orders, setOrders] = useState<Order[]>([]);
  const [fills, setFills] = useState<Fill[]>([]);
  const [ohlc, setOhlc] = useState<Candle | null>(null);
  /** Working orders priced outside the drawn price range — see the fitOrders block below. */
  const [offscreen, setOffscreen] = useState<Order[]>([]);
  const [fitOrders, setFitOrders] = useState(false);
  const [bars, setBars] = useState(0);

  const priceRef = useRef<HTMLDivElement>(null);
  const studyRef = useRef<HTMLDivElement>(null);

  const sec = useMemo(() => secs.find((s) => s.securityId === securityId) || null, [secs, securityId]);
  const items: ComboItem[] = useMemo(
    () => secs.map((s) => ({ id: s.securityId, primary: s.symbol, secondary: s.name, extra: s.assetClass })),
    [secs],
  );

  // restore the working set — a trader's chart setup is a preference, not a session detail
  useEffect(() => {
    try {
      const raw = localStorage.getItem(STORE);
      if (raw) {
        const p = JSON.parse(raw);
        if (p.tf) setTf(p.tf);
        if (p.type) setType(p.type);
        if (Array.isArray(p.overlays)) setOverlays(p.overlays);
        if ("study" in p) setStudy(p.study);
        if (typeof p.showMine === "boolean") setShowMine(p.showMine);
        if (p.securityId) setSecurityId(p.securityId);
      }
    } catch { /* a corrupt preference must not block the chart */ }
  }, []);
  useEffect(() => {
    try { localStorage.setItem(STORE, JSON.stringify({ tf, type, overlays, study, showMine, securityId })); } catch {}
  }, [tf, type, overlays, study, showMine, securityId]);

  useEffect(() => {
    get<Sec[]>("/api/market/watch?exchange=DSE")
      .then((w) => {
        const list = (w || []).filter((s) => s.assetClass !== "INDEX");
        setSecs(list);
        setSecurityId((cur) => cur ?? (list[0]?.securityId ?? null));
      })
      .catch(() => {});
  }, []);

  /**
   * Our own orders and executions for this instrument.
   *
   * Both come from /api/orders. It is tempting to use /api/market/{id}/trades for the fill markers —
   * it is right there and it has prices and timestamps — but that endpoint is the PUBLIC TAPE: every
   * print in the instrument, most of them other firms'. Drawing those as "my fills" would put a
   * marker under a bar the trader never traded, which is worse than drawing nothing, because the
   * chart would be confidently wrong about the one thing only we can know.
   *
   * The blotter carries filledQty and avgFillPrice per order, so an order with filledQty > 0 is an
   * execution of ours and its average price is where it traded.
   */
  const loadMine = useCallback(async () => {
    if (!sec) { setOrders([]); setFills([]); return; }
    try {
      const all = (await get<Order[]>("/api/orders")) || [];
      const mine = all.filter((o) => o.symbol === sec.symbol);

      setOrders(mine.filter(
        (o) => !TERMINAL.has(o.status) && o.quantity - (o.filledQty || 0) > 0 && o.price > 0,
      ));

      setFills(mine
        .filter((o) => (o.filledQty || 0) > 0 && (o.avgFillPrice || o.price) > 0 && o.createdAt)
        .map((o) => ({
          id: o.id,
          side: o.side,
          price: o.avgFillPrice || o.price,
          quantity: o.filledQty as number,
          at: o.createdAt as string,
        })));
    } catch { setOrders([]); setFills([]); }
  }, [sec]);
  useEffect(() => { loadMine(); }, [loadMine]);

  // The tape can print many times a second. Refetching the blotter on every one of those would put a
  // request per trade on the backend and — because the redraw keys off this data — rebuild the chart
  // just as often. Coalesce to at most one refresh per REFRESH_MS.
  const REFRESH_MS = 3000;
  const lastLoad = useRef(0);
  const pending = useRef<ReturnType<typeof setTimeout> | null>(null);
  // The same stream drives the header connectivity badge — the page must pass `connected` through
  // to Shell or it shows "Offline" while the feed is plainly live.
  const { connected } = useLive((t) => {
    if (t !== "order" && t !== "trade") return;
    const since = Date.now() - lastLoad.current;
    if (since >= REFRESH_MS) { lastLoad.current = Date.now(); loadMine(); return; }
    if (pending.current) return;                    // one trailing refresh is enough
    pending.current = setTimeout(() => {
      pending.current = null;
      lastLoad.current = Date.now();
      loadMine();
    }, REFRESH_MS - since);
  });
  useEffect(() => () => { if (pending.current) clearTimeout(pending.current); }, []);

  /**
   * Redraw keys, not the arrays themselves.
   *
   * loadMine() builds fresh arrays every time it runs, so depending on `orders`/`fills` directly
   * would tear down and rebuild the whole chart — including refetching 400 candles — on every poll,
   * even when nothing about our orders had changed. These strings only change when something we
   * actually draw changes.
   */
  const ordersKey = orders.map((o) => `${o.id}:${o.side}:${o.price}:${o.quantity - (o.filledQty || 0)}`).join("|");
  const fillsKey = fills.map((f) => `${f.id}:${f.side}:${f.price}:${f.quantity}`).join("|");
  const ordersRef = useRef(orders); ordersRef.current = orders;
  const fillsRef = useRef(fills); fillsRef.current = fills;

  // ---------------------------------------------------------------- draw
  useEffect(() => {
    if (!securityId) return;
    let priceChart: any, studyChart: any, ro: ResizeObserver | null = null, dead = false;

    (async () => {
      const lib = await import("lightweight-charts");
      if (dead || !priceRef.current) return;

      let raw: Candle[] = [];
      try {
        raw = await (await fetch(`${API}/api/market/${securityId}/candles?tf=${tf}&limit=400`)).json();
      } catch { raw = []; }
      if (!Array.isArray(raw) || !raw.length) { setBars(0); return; }
      setBars(raw.length);

      // Heikin-Ashi is a transform of the bars, so indicators computed after it describe the
      // SMOOTHED series, not the market. Always compute studies from the real candles.
      const shown = type === "heikin" ? heikinAshi(raw) : raw;

      priceRef.current.innerHTML = "";
      priceChart = lib.createChart(priceRef.current, {
        ...chartBase(lib, tf !== "1d"),
        height: studyRef.current && study ? 420 : 560,
        crosshair: { mode: lib.CrosshairMode.Normal },
      });

      let main: any;
      if (type === "line" || type === "area") {
        main = type === "line"
          ? priceChart.addLineSeries({ color: cssRGB("--aurora-cyan"), lineWidth: 2 })
          : priceChart.addAreaSeries({
              lineColor: cssRGB("--aurora-cyan"), topColor: cssRGB("--aurora-cyan", 0.28),
              bottomColor: cssRGB("--aurora-cyan", 0.02), lineWidth: 2,
            });
        main.setData(shown.map((c) => ({ time: c.time, value: c.close })));
      } else {
        main = priceChart.addCandlestickSeries(candleColours());
        main.setData(shown.map((c) => ({ time: c.time, open: c.open, high: c.high, low: c.low, close: c.close })));
      }

      const vol = priceChart.addHistogramSeries({ priceFormat: { type: "volume" }, priceScaleId: "" });
      vol.priceScale().applyOptions({ scaleMargins: { top: 0.85, bottom: 0 } });
      vol.setData(shown.map((c) => ({
        time: c.time, value: c.volume ?? 0,
        color: c.close >= c.open ? cssRGB("--bull", 0.4) : cssRGB("--bear", 0.4),
      })));

      for (const o of OVERLAYS) {
        if (!overlays.includes(o.id)) continue;
        for (const series of o.fn(raw)) {
          if (!series.length) continue;
          const s = priceChart.addLineSeries({
            color: o.colour, lineWidth: o.id === "BB20" ? 1 : 2,
            priceLineVisible: false, lastValueVisible: false, crosshairMarkerVisible: false,
          });
          s.setData(series);
        }
      }

      // ---- your own orders and fills, the reason this screen exists
      if (!showMine) setOffscreen([]);
      if (showMine) {
        const lo = Math.min(...shown.map((c) => c.low));
        const hi = Math.max(...shown.map((c) => c.high));
        const away: Order[] = [];

        for (const o of ordersRef.current) {
          const remaining = o.quantity - (o.filledQty || 0);
          main.createPriceLine({
            price: o.price,
            color: o.side === "BUY" ? cssRGB("--bull") : cssRGB("--bear"),
            lineWidth: 1, lineStyle: 2, axisLabelVisible: true,
            title: `${o.side} ${nf(remaining, 0)}`,
          });
          if (o.price < lo || o.price > hi) away.push(o);
        }

        /**
         * A limit priced away from the market sits outside the axis, and createPriceLine does not
         * extend the scale — so the line is drawn, correctly, somewhere the trader cannot see. The
         * footer would then claim an order is on the chart while the chart shows nothing, which
         * reads as "my order never arrived".
         *
         * Say so, and offer to reach it. `fitOrders` anchors the scale with a transparent series
         * (a series participates in autoscaling; a price line does not) rather than silently
         * squashing the candles for everyone who never priced away from the touch.
         */
        setOffscreen(away);
        if (fitOrders && away.length) {
          const first = shown[0].time, last = shown[shown.length - 1].time;
          // One series per order: a single series carrying two points per order would repeat the
          // same timestamps, and lightweight-charts requires strictly ascending unique times.
          for (const o of away) {
            const anchor = priceChart.addLineSeries({
              color: "rgba(0,0,0,0)", lineWidth: 1,
              priceLineVisible: false, lastValueVisible: false, crosshairMarkerVisible: false,
            });
            anchor.setData([{ time: first, value: o.price }, { time: last, value: o.price }]);
          }
        }
        const myFills = fillsRef.current;
        if (myFills.length) {
          // Markers are anchored to the bar containing the order's timestamp. That is placement
          // time, not execution time — the blotter does not carry a per-execution clock — so on a
          // 1m chart an order that rested a while shows on the bar it was ENTERED in. At 5m and
          // above, which is how this screen is normally used, the two coincide.
          const step = tf === "1m" ? 60 : tf === "5m" ? 300 : tf === "15m" ? 900 : 86400;

          // Several of our fills can land in one bar. Aggregating rather than dropping keeps the
          // total honest: two buys of 500 read as one marker of 1,000, not as one buy of 500.
          const byBar = new Map<string, { time: number; side: string; qty: number; notional: number }>();
          for (const f of myFills) {
            const t = Math.floor(new Date(f.at).getTime() / 1000);
            if (!t || Number.isNaN(t)) continue;
            const bucket = Math.floor(t / step) * step;
            const key = `${bucket}:${f.side}`;
            const cur = byBar.get(key) || { time: bucket, side: f.side, qty: 0, notional: 0 };
            cur.qty += f.quantity;
            cur.notional += f.price * f.quantity;
            byBar.set(key, cur);
          }

          const markers = [...byBar.values()]
            .sort((a, b) => a.time - b.time)         // lightweight-charts requires ascending time
            .map((m) => {
              const isBuy = m.side === "BUY";
              return {
                time: m.time,
                position: isBuy ? "belowBar" : "aboveBar",
                color: isBuy ? cssRGB("--bull") : cssRGB("--bear"),
                shape: isBuy ? "arrowUp" : "arrowDown",
                text: `${isBuy ? "B" : "S"} ${nf(m.qty, 0)} @ ${nf(m.notional / m.qty)}`,
              };
            });
          if (markers.length) main.setMarkers(markers as any);
        }
      }

      priceChart.timeScale().fitContent();

      // OHLC readout follows the crosshair — the legend a trader reads instead of hovering blind
      priceChart.subscribeCrosshairMove((p: any) => {
        if (!p?.time) { setOhlc(null); return; }
        const d = p.seriesData?.get(main);
        if (!d) { setOhlc(null); return; }
        setOhlc("open" in d
          ? { time: p.time, open: d.open, high: d.high, low: d.low, close: d.close }
          : { time: p.time, open: d.value, high: d.value, low: d.value, close: d.value });
      });

      // ---- the study pane
      if (study && studyRef.current) {
        studyRef.current.innerHTML = "";
        studyChart = lib.createChart(studyRef.current, { ...chartBase(lib, tf !== "1d"), height: 150 });
        if (study === "RSI14") {
          const s = studyChart.addLineSeries({ color: "#e879f9", lineWidth: 2, lastValueVisible: true });
          s.setData(rsi(raw, 14));
          for (const lvl of [70, 30]) {
            s.createPriceLine({ price: lvl, color: cssRGB("--ink-600", 0.7), lineWidth: 1, lineStyle: 2, axisLabelVisible: true, title: String(lvl) });
          }
        } else if (study === "MACD") {
          const m = macd(raw);
          const h = studyChart.addHistogramSeries({ priceFormat: { type: "price" } });
          h.setData(m.histogram.map((p) => ({
            time: p.time, value: p.value,
            color: p.value >= 0 ? cssRGB("--bull", 0.5) : cssRGB("--bear", 0.5),
          })));
          studyChart.addLineSeries({ color: "#38bdf8", lineWidth: 2, lastValueVisible: false }).setData(m.macd);
          studyChart.addLineSeries({ color: "#f59e0b", lineWidth: 1, lastValueVisible: false }).setData(m.signal);
        } else if (study === "ATR14") {
          studyChart.addLineSeries({ color: "#fb923c", lineWidth: 2 }).setData(atr(raw, 14));
        }
        studyChart.timeScale().fitContent();
        // lock the two time axes together, or the crosshair means different things in each
        priceChart.timeScale().subscribeVisibleLogicalRangeChange((r: any) => r && studyChart?.timeScale().setVisibleLogicalRange(r));
        studyChart.timeScale().subscribeVisibleLogicalRangeChange((r: any) => r && priceChart?.timeScale().setVisibleLogicalRange(r));
      }

      const resize = () => {
        if (priceRef.current) priceChart.applyOptions({ width: priceRef.current.clientWidth });
        if (studyRef.current && studyChart) studyChart.applyOptions({ width: studyRef.current.clientWidth });
      };
      resize();
      ro = new ResizeObserver(resize);
      ro.observe(priceRef.current);
    })();

    return () => { dead = true; ro?.disconnect(); priceChart?.remove?.(); studyChart?.remove?.(); };
  }, [securityId, tf, type, overlays, study, showMine, fitOrders, ordersKey, fillsKey]);

  const toggle = (list: string[], id: string, set: (v: string[]) => void) =>
    set(list.includes(id) ? list.filter((x) => x !== id) : [...list, id]);

  const pill = (on: boolean) =>
    `rounded-lg px-2.5 py-1 text-[11px] font-semibold transition-colors ${
      on ? "bg-gradient-to-r from-aurora-violet to-aurora-indigo text-white" : "text-ink-300 hover:text-ink-100 hover:bg-white/5"}`;

  return (
    <Shell title="Advanced Chart" connected={connected}>
      <div className="flex h-full min-h-0 flex-col gap-2 p-3">
        {/* instrument + quote */}
        <div className="flex flex-wrap items-center gap-3 rounded-xl border border-line bg-obsidian-900/60 px-3 py-2">
          <div className="w-[230px]">
            <ComboBox items={items} value={securityId} placeholder="ticker or company…" onChange={setSecurityId} />
          </div>
          {sec && (
            <>
              <span className="text-[15px] font-semibold text-ink-100">{nf(sec.ltp)}</span>
              {typeof sec.changePct === "number" && (
                <span className={`text-[12px] font-semibold ${sec.changePct >= 0 ? "text-bull" : "text-bear"}`}>
                  {sec.changePct >= 0 ? "▲" : "▼"} {nf(Math.abs(sec.changePct), 2)}%
                </span>
              )}
              <span className="truncate text-[11px] text-ink-400">{sec.name}</span>
            </>
          )}
          {/* crosshair readout */}
          {ohlc && (
            <span className="ml-auto flex gap-3 text-[11px] tabular-nums text-ink-300">
              <span>O <span className="text-ink-100">{nf(ohlc.open)}</span></span>
              <span>H <span className="text-bull">{nf(ohlc.high)}</span></span>
              <span>L <span className="text-bear">{nf(ohlc.low)}</span></span>
              <span>C <span className="text-ink-100">{nf(ohlc.close)}</span></span>
            </span>
          )}
        </div>

        {/* controls */}
        <div className="flex flex-wrap items-center gap-2 rounded-xl border border-line bg-obsidian-900/60 px-3 py-2">
          <span className="text-[9px] font-bold uppercase tracking-[0.14em] text-aurora-cyan">Interval</span>
          <div className="flex rounded-lg border border-line p-0.5">
            {TFS.map((t) => <button key={t.k} onClick={() => setTf(t.k)} className={pill(tf === t.k)}>{t.label}</button>)}
          </div>

          <span className="ml-2 text-[9px] font-bold uppercase tracking-[0.14em] text-aurora-cyan">Type</span>
          <div className="flex rounded-lg border border-line p-0.5">
            {TYPES.map((t) => <button key={t.k} onClick={() => setType(t.k)} className={pill(type === t.k)}>{t.label}</button>)}
          </div>

          <span className="ml-2 text-[9px] font-bold uppercase tracking-[0.14em] text-aurora-cyan">Overlays</span>
          <div className="flex flex-wrap gap-1">
            {OVERLAYS.map((o) => (
              <button key={o.id} onClick={() => toggle(overlays, o.id, setOverlays)}
                className={`rounded-lg border px-2 py-1 text-[11px] font-semibold transition-colors ${
                  overlays.includes(o.id) ? "border-transparent" : "border-line text-ink-400 hover:text-ink-100"}`}
                style={overlays.includes(o.id) ? { color: o.colour, background: `${o.colour}22` } : undefined}>
                {o.label}
              </button>
            ))}
          </div>

          <span className="ml-2 text-[9px] font-bold uppercase tracking-[0.14em] text-aurora-cyan">Study</span>
          <div className="flex rounded-lg border border-line p-0.5">
            <button onClick={() => setStudy(null)} className={pill(study === null)}>None</button>
            {STUDIES.map((s) => <button key={s.id} onClick={() => setStudy(s.id)} className={pill(study === s.id)}>{s.label}</button>)}
          </div>

          <button
            onClick={() => setShowMine((v) => !v)}
            title="Draw your working orders as price lines and your own executions as markers. The exchange feed is anonymous — only the OMS knows which of this is yours."
            className={`ml-auto rounded-lg border px-3 py-1.5 text-[11px] font-semibold ${
              showMine ? "border-aurora-cyan/60 bg-aurora-cyan/15 text-aurora-cyan" : "border-line text-ink-300 hover:bg-white/5"}`}>
            My orders &amp; fills{orders.length ? ` (${orders.length})` : ""}
          </button>
        </div>

        {/* the chart */}
        <div className="min-h-0 flex-1 overflow-hidden rounded-xl border border-line bg-obsidian-900/40 p-2">
          {bars === 0 && (
            <div className="flex h-full items-center justify-center text-[12px] text-ink-400">
              No candles for this instrument and interval yet — it needs prints on the tape to build bars.
            </div>
          )}
          <div ref={priceRef} className="w-full" />
          {study && (
            <div className="mt-1 border-t border-line pt-1">
              <div className="mb-0.5 px-1 text-[10px] font-semibold uppercase tracking-wider"
                   style={{ color: STUDIES.find((s) => s.id === study)?.colour }}>
                {STUDIES.find((s) => s.id === study)?.label}
              </div>
              <div ref={studyRef} className="w-full" />
            </div>
          )}
        </div>

        <div className="flex flex-wrap items-center gap-4 rounded-xl border border-line bg-obsidian-900/60 px-3 py-1.5 text-[11px] text-ink-400">
          <span>{bars} bars · {TFS.find((t) => t.k === tf)?.label ?? tf}</span>
          {showMine && orders.length > 0 && (
            <span className="text-aurora-cyan">
              {orders.length} working order{orders.length === 1 ? "" : "s"} drawn as price lines
            </span>
          )}
          {showMine && offscreen.length > 0 && (
            <span className="flex items-center gap-2 text-amber-300">
              {offscreen.length} priced outside this range
              <span className="text-ink-400">
                ({offscreen.map((o) => `${o.side} ${nf(o.quantity - (o.filledQty || 0), 0)} @ ${nf(o.price)}`).join(", ")})
              </span>
              <button
                onClick={() => setFitOrders((v) => !v)}
                className="rounded border border-amber-300/50 px-2 py-0.5 text-[10px] font-semibold text-amber-300 hover:bg-amber-300/10">
                {fitOrders ? "Zoom back to price" : "Show on chart"}
              </button>
            </span>
          )}
          {showMine && fills.length > 0 && (
            <span className="text-ink-300">{fills.length} of your orders traded here</span>
          )}
          <span className="ml-auto">Built on TradingView Lightweight Charts</span>
        </div>
      </div>
    </Shell>
  );
}
