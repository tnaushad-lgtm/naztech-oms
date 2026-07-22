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
 *
 * Indicators are INSTANCES from lib/indicatorCatalogue, not fixed toggles — EMA(9) and EMA(21) are
 * two instances of one indicator, each with its own period and colour. See that file for why the
 * original registry could not express that.
 */

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Shell } from "@/components/Shell";
import { ComboBox, ComboItem } from "@/components/ComboBox";
import { IndicatorDialog } from "@/components/IndicatorDialog";
import { useLive } from "@/lib/useLive";
import { get, API } from "@/lib/api";
import { nf } from "@/lib/format";
import { chartBase, candleColours, cssRGB } from "@/lib/chartTheme";
import { Candle, heikinAshi } from "@/lib/indicators";
import {
  Applied, byId, describe, describeUnique, migrateLegacy, newApplied, reviveApplied,
} from "@/lib/indicatorCatalogue";

type Sec = { securityId: number; symbol: string; name: string; ltp: number; assetClass: string; changePct?: number };
type Order = {
  id: number; symbol: string; side: string; price: number; quantity: number;
  filledQty?: number; avgFillPrice?: number; status: string; createdAt?: string;
};

/** An order of ours that has traded. Not the public tape — see loadMine(). */
type Fill = { id: number; side: string; price: number; quantity: number; at: string };

/** What the pointer is over: the bar itself, plus every indicator's value at that bar. */
type Hover = {
  time: number | string;
  candle?: Candle;
  x: number;
  y: number;
  /** Which pane the pointer is in, so the readout can be placed there. */
  pane: "price" | string;
};

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

/**
 * One-click adds for what a desk reaches for constantly.
 *
 * The dialog is the complete answer, but making someone open a dialog, search and configure just to
 * put RSI on the screen would be a regression over the four buttons this replaced. These add a
 * default instance; the gear is for everything else and for tuning.
 */
const QUICK = [
  { id: "EMA", label: "EMA" },
  { id: "VWAP", label: "VWAP" },
  { id: "BB", label: "Bollinger" },
  { id: "RSI", label: "RSI" },
  { id: "MACD", label: "MACD" },
];

const STORE = "oms_advchart";
const SUB_HEIGHT = 138;
/** Shared price-axis width so every pane's plot area starts at the same x. */
const PRICE_SCALE_W = 74;

/** Resolve a series' declared tone against the instance colour and the active theme. */
function toneColour(tone: string | undefined, base: string): string {
  switch (tone) {
    case "muted": return cssRGB("--ink-500", 0.85);
    case "bull": return cssRGB("--bull");
    case "bear": return cssRGB("--bear");
    // A signal line must be told apart from the line it signals. Fading the same hue is not enough
    // on a 120px pane — %K and %D read as one thick line. Offsetting within the palette was no
    // better: it landed amber next to yellow. A neutral light tone contrasts against every hue in
    // the palette rather than against most of them, and follows the theme into daylight.
    case "secondary": return cssRGB("--ink-200", 0.9);
    default: return base;
  }
}

/** lightweight-charts cannot parse "#rrggbb80", so alpha has to be expressed as comma-joined rgba. */
function hexAlpha(hex: string, alpha: number): string {
  const m = /^#?([0-9a-f]{6})$/i.exec(hex.trim());
  if (!m) return hex;
  const n = parseInt(m[1], 16);
  return `rgba(${(n >> 16) & 255},${(n >> 8) & 255},${n & 255},${alpha})`;
}

export default function ChartPage() {
  const [secs, setSecs] = useState<Sec[]>([]);
  const [securityId, setSecurityId] = useState<number | null>(null);
  const [tf, setTf] = useState("5m");
  const [type, setType] = useState<(typeof TYPES)[number]["k"]>("candles");
  const [applied, setApplied] = useState<Applied[]>([]);
  const [dialog, setDialog] = useState(false);
  const [showMine, setShowMine] = useState(true);
  const [orders, setOrders] = useState<Order[]>([]);
  const [fills, setFills] = useState<Fill[]>([]);
  /** Working orders priced outside the drawn price range — see the fitOrders block below. */
  const [offscreen, setOffscreen] = useState<Order[]>([]);
  const [fitOrders, setFitOrders] = useState(false);
  const [bars, setBars] = useState(0);
  const [restored, setRestored] = useState(false);
  /** What the crosshair is currently over — drives the legend values and the floating readout. */
  const [hover, setHover] = useState<Hover>(null);

  const priceRef = useRef<HTMLDivElement>(null);
  const subRefs = useRef<Record<string, HTMLDivElement | null>>({});
  /**
   * Indicator values indexed by instance and bar time, built once per draw.
   *
   * The alternative is asking lightweight-charts for each series' data on every mouse move, which
   * means holding a handle to every series and doing a lookup per pointer event. Precomputing at
   * draw time makes the hover path a couple of Map reads — a chart with eight overlays still
   * tracks the pointer without dropping frames.
   */
  const valueIndex = useRef<Map<string, Map<string, { name: string; value: number }[]>>>(new Map());
  const candleIndex = useRef<Map<string, Candle>>(new Map());

  const sec = useMemo(() => secs.find((s) => s.securityId === securityId) || null, [secs, securityId]);
  const items: ComboItem[] = useMemo(
    () => secs.map((s) => ({ id: s.securityId, primary: s.symbol, secondary: s.name, extra: s.assetClass })),
    [secs],
  );

  const priceOverlays = useMemo(() => applied.filter((a) => !a.hidden && byId(a.id)?.pane === "price"), [applied]);
  const subStudies = useMemo(() => applied.filter((a) => !a.hidden && byId(a.id)?.pane === "sub"), [applied]);

  // restore the working set — a trader's chart setup is a preference, not a session detail
  useEffect(() => {
    try {
      const raw = localStorage.getItem(STORE);
      if (raw) {
        const p = JSON.parse(raw);
        if (p.tf) setTf(p.tf);
        if (p.type) setType(p.type);
        if (typeof p.showMine === "boolean") setShowMine(p.showMine);
        if (p.securityId) setSecurityId(p.securityId);

        // `indicators` is the current shape; overlays/study is what the first version wrote.
        if (Array.isArray(p.indicators)) setApplied(reviveApplied(p.indicators));
        else if (p.overlays || p.study) setApplied(migrateLegacy(p.overlays, p.study));
      }
    } catch { /* a corrupt preference must not block the chart */ }
    setRestored(true);
  }, []);

  useEffect(() => {
    // Do not write until the restore has run, or the empty initial state overwrites the saved setup.
    if (!restored) return;
    try {
      localStorage.setItem(STORE, JSON.stringify({ tf, type, showMine, securityId, indicators: applied }));
    } catch {}
  }, [restored, tf, type, showMine, securityId, applied]);

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
  const indicatorKey = applied
    .map((a) => `${a.uid}:${a.id}:${a.colour}:${a.hidden ? 1 : 0}:${Object.entries(a.params).sort().map(([k, v]) => `${k}=${v}`).join(",")}`)
    .join("|");
  const ordersRef = useRef(orders); ordersRef.current = orders;
  const fillsRef = useRef(fills); fillsRef.current = fills;
  const appliedRef = useRef(applied); appliedRef.current = applied;

  // ---------------------------------------------------------------- draw
  useEffect(() => {
    if (!securityId) return;
    let priceChart: any;
    const subCharts: any[] = [];
    let ro: ResizeObserver | null = null, dead = false;

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

      const subs = appliedRef.current.filter((a) => !a.hidden && byId(a.id)?.pane === "sub");
      const overlays = appliedRef.current.filter((a) => !a.hidden && byId(a.id)?.pane === "price");

      /**
       * Every pane must reserve the SAME price-scale width, or they do not line up.
       *
       * Each pane is its own chart and sizes its axis to its own labels — "4,575.00" on price
       * against "100.00" on a stochastic — so the plot areas start at different x and a dip in the
       * oscillator sits to the side of the bar that caused it. That silently misleads, which is
       * worse than being ugly. A fixed minimum makes the plots share an origin.
       */
      const scale = { minimumWidth: PRICE_SCALE_W };

      priceRef.current.innerHTML = "";
      priceChart = lib.createChart(priceRef.current, {
        ...chartBase(lib, tf !== "1d"),
        height: Math.max(240, 560 - subs.length * SUB_HEIGHT),
        crosshair: { mode: lib.CrosshairMode.Normal },
        rightPriceScale: { ...chartBase(lib, tf !== "1d").rightPriceScale, ...scale },
        // Only the bottom-most pane shows the time axis; repeating it under every pane is noise.
        timeScale: { ...chartBase(lib, tf !== "1d").timeScale, visible: subs.length === 0 },
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

      // Rebuilt each draw so a stale index can never outlive the series it describes.
      valueIndex.current = new Map();
      candleIndex.current = new Map(shown.map((c) => [String(c.time), c]));

      /** Index one indicator's outputs by bar time, for the crosshair readout. */
      const indexValues = (uid: string, out: { series: { name: string; points: any[] }[] }) => {
        const m = new Map<string, { name: string; value: number }[]>();
        for (const ser of out.series) {
          for (const pt of ser.points) {
            if (pt.value === undefined || pt.value === null) continue;   // whitespace gaps carry no value
            const k = String(pt.time);
            const arr = m.get(k);
            if (arr) arr.push({ name: ser.name, value: pt.value });
            else m.set(k, [{ name: ser.name, value: pt.value }]);
          }
        }
        valueIndex.current.set(uid, m);
      };

      // ---- price-pane overlays, one instance at a time
      for (const a of overlays) {
        const entry = byId(a.id);
        if (!entry) continue;
        let out;
        try { out = entry.compute(raw, a.params); }
        catch { continue; }         // one bad indicator must not take the whole chart down
        indexValues(a.uid, out);

        for (const s of out.series) {
          if (!s.points.length) continue;
          const line = priceChart.addLineSeries({
            color: toneColour(s.tone, a.colour),
            lineWidth: s.width ?? 2,
            lineStyle: s.dashed ? 2 : 0,
            priceLineVisible: false, lastValueVisible: false, crosshairMarkerVisible: false,
          });
          line.setData(s.points);
        }
        // Levels on the price axis (floor pivots) are horizontal by definition, so they are price
        // lines rather than series — they must not stretch the scale to reach a far-off level.
        for (const lvl of out.levels || []) {
          main.createPriceLine({
            price: lvl.value,
            color: lvl.muted ? hexAlpha(a.colour, 0.45) : a.colour,
            lineWidth: 1, lineStyle: 2, axisLabelVisible: true, title: lvl.label,
          });
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

      /**
       * Crosshair readout, and the crosshair itself carried to the other panes.
       *
       * `syncingCross` matters: setCrosshairPosition on another pane makes that pane fire its own
       * crosshair-move, which would call back into here and set the position on this one. Without
       * the guard the panes ping-pong on every mouse move.
       */
      let syncingCross = false;
      const anchor: { chart: any; series: any }[] = [];

      const onCross = (p: any, paneId: string) => {
        if (!p?.time || !p.point) {
          setHover(null);
          if (!syncingCross) {
            syncingCross = true;
            for (const t of anchor) t.chart.clearCrosshairPosition?.();
            priceChart.clearCrosshairPosition?.();
            syncingCross = false;
          }
          return;
        }
        setHover({ time: p.time, candle: candleIndex.current.get(String(p.time)), x: p.point.x, y: p.point.y, pane: paneId });

        if (syncingCross) return;
        syncingCross = true;
        for (const t of [{ chart: priceChart, series: main }, ...anchor]) {
          if (t.chart === (paneId === "price" ? priceChart : null)) continue;
          // setCrosshairPosition needs a price to place the horizontal line; the pane's own series
          // value at this time keeps it on the data rather than at an arbitrary height.
          const d = t.chart === priceChart ? candleIndex.current.get(String(p.time))?.close : undefined;
          try { t.chart.setCrosshairPosition(d ?? 0, p.time, t.series); } catch {}
        }
        syncingCross = false;
      };

      priceChart.subscribeCrosshairMove((p: any) => onCross(p, "price"));

      // ---- one pane per sub-study
      for (const a of subs) {
        const host = subRefs.current[a.uid];
        const entry = byId(a.id);
        if (!host || !entry) continue;
        let out;
        try { out = entry.compute(raw, a.params); }
        catch { continue; }
        indexValues(a.uid, out);

        host.innerHTML = "";
        const isLast = subs.indexOf(a) === subs.length - 1;
        const base = chartBase(lib, tf !== "1d");
        const chart = lib.createChart(host, {
          ...base,
          height: SUB_HEIGHT - 18,
          rightPriceScale: { ...base.rightPriceScale, ...scale },
          timeScale: { ...base.timeScale, visible: isLast },
        });
        subCharts.push(chart);

        let first: any = null;
        for (const s of out.series) {
          if (!s.points.length) continue;
          if (s.kind === "histogram") {
            const h = chart.addHistogramSeries({ priceFormat: { type: "price" } });
            h.setData(s.points.map((p: any) => ({
              time: p.time, value: p.value,
              // "signed" means the bar's own sign carries the meaning, so it overrides the
              // instance colour — a MACD histogram in one flat colour says nothing.
              color: s.tone === "signed"
                ? (p.value >= 0 ? cssRGB("--bull", 0.5) : cssRGB("--bear", 0.5))
                : hexAlpha(a.colour, 0.5),
            })));
            first = first || h;
          } else {
            const line = chart.addLineSeries({
              color: toneColour(s.tone, a.colour),
              lineWidth: s.width ?? 2,
              lineStyle: s.dashed ? 2 : 0,
              priceLineVisible: false,
            });
            line.setData(s.points);
            first = first || line;
          }
        }
        for (const lvl of out.levels || []) {
          if (!first) break;
          first.createPriceLine({
            price: lvl.value, color: cssRGB("--ink-600", 0.7),
            lineWidth: 1, lineStyle: 2, axisLabelVisible: true, title: lvl.label,
          });
        }
        if (out.range) {
          // A bounded oscillator should show its own bounds and no more — a 0..100 stochastic with
          // a 120 gridline invites the reading that 100 is not the ceiling. Tight margins keep the
          // 80/20 reference lines meaningful instead of floating in dead space.
          chart.priceScale("right").applyOptions({ autoScale: false, scaleMargins: { top: 0.04, bottom: 0.04 } });
          first?.applyOptions?.({
            autoscaleInfoProvider: () => ({ priceRange: { minValue: out.range!.min, maxValue: out.range!.max } }),
          });
        }
        chart.timeScale().fitContent();
        if (first) anchor.push({ chart, series: first });
        chart.subscribeCrosshairMove((p: any) => onCross(p, a.uid));
      }

      /**
       * Lock every pane to the same time axis.
       *
       * Without the `syncing` guard each pane's own change handler re-fires the others, which
       * re-fire it — the panes fight, the crosshair stutters and the range creeps. The flag makes
       * propagation one-way per gesture.
       */
      const all = [priceChart, ...subCharts];
      let syncing = false;
      for (const src of all) {
        src.timeScale().subscribeVisibleLogicalRangeChange((r: any) => {
          if (!r || syncing) return;
          syncing = true;
          for (const dst of all) if (dst !== src) dst.timeScale().setVisibleLogicalRange(r);
          syncing = false;
        });
      }

      const resize = () => {
        if (priceRef.current) priceChart.applyOptions({ width: priceRef.current.clientWidth });
        for (const a of subs) {
          const host = subRefs.current[a.uid];
          const idx = subs.indexOf(a);
          if (host && subCharts[idx]) subCharts[idx].applyOptions({ width: host.clientWidth });
        }
      };
      resize();
      ro = new ResizeObserver(resize);
      ro.observe(priceRef.current);
    })();

    return () => {
      dead = true;
      ro?.disconnect();
      priceChart?.remove?.();
      for (const c of subCharts) c?.remove?.();
    };
  }, [securityId, tf, type, showMine, fitOrders, ordersKey, fillsKey, indicatorKey]);

  /** Indicator values at the hovered bar. Empty when the pointer is off the chart. */
  const valuesAt = (uid: string): { name: string; value: number }[] => {
    if (!hover) return [];
    return valueIndex.current.get(uid)?.get(String(hover.time)) || [];
  };

  /** Bar timestamp for the readout. Candle times are unix seconds; 1D bars carry no useful clock. */
  const hoverTime = (): string => {
    if (!hover) return "";
    const t = typeof hover.time === "number" ? new Date(hover.time * 1000) : new Date(String(hover.time));
    if (Number.isNaN(t.getTime())) return String(hover.time);
    return tf === "1d"
      ? t.toLocaleDateString(undefined, { day: "2-digit", month: "short", year: "numeric" })
      : t.toLocaleString(undefined, { day: "2-digit", month: "short", hour: "2-digit", minute: "2-digit" });
  };

  const quickAdd = (id: string) => {
    const existing = applied.filter((a) => a.id === id);
    // A quick button toggles: press it again and the instances it stands for come off, so it can
    // never become a one-way door that silently stacks duplicates.
    if (existing.length) setApplied(applied.filter((a) => a.id !== id));
    else {
      const a = newApplied(id, applied.length);
      if (a) setApplied([...applied, a]);
    }
  };

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
          {hover?.candle && (
            <span className="ml-auto flex gap-3 text-[11px] tabular-nums text-ink-300">
              <span>O <span className="text-ink-100">{nf(hover.candle.open)}</span></span>
              <span>H <span className="text-bull">{nf(hover.candle.high)}</span></span>
              <span>L <span className="text-bear">{nf(hover.candle.low)}</span></span>
              <span>C <span className="text-ink-100">{nf(hover.candle.close)}</span></span>
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

          <span className="ml-2 text-[9px] font-bold uppercase tracking-[0.14em] text-aurora-cyan">Quick</span>
          <div className="flex flex-wrap gap-1">
            {QUICK.map((q) => {
              const on = applied.some((a) => a.id === q.id);
              return (
                <button key={q.id} onClick={() => quickAdd(q.id)}
                  title={byId(q.id)?.hint}
                  className={`rounded-lg border px-2 py-1 text-[11px] font-semibold transition-colors ${
                    on ? "border-aurora-cyan/50 bg-aurora-cyan/15 text-aurora-cyan" : "border-line text-ink-300 hover:bg-white/5 hover:text-ink-100"}`}>
                  {q.label}
                </button>
              );
            })}
          </div>

          {/* the gear */}
          <button onClick={() => setDialog(true)}
            title="Add, tune and remove indicators"
            className={`ml-2 flex items-center gap-1.5 rounded-lg border px-2.5 py-1.5 text-[11px] font-semibold transition-colors ${
              applied.length ? "border-aurora-indigo/50 bg-aurora-indigo/15 text-ink-100" : "border-line text-ink-300 hover:bg-white/5"}`}>
            <svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" strokeWidth="1.8"
                 strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
              <circle cx="12" cy="12" r="3" />
              <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06A1.65 1.65 0 0 0 4.6 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06A1.65 1.65 0 0 0 9 4.6a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9c.14.63.68 1.09 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z" />
            </svg>
            Indicators{applied.length ? ` (${applied.length})` : ""}
          </button>

          <button
            onClick={() => setShowMine((v) => !v)}
            title="Draw your working orders as price lines and your own executions as markers. The exchange feed is anonymous — only the OMS knows which of this is yours."
            className={`ml-auto rounded-lg border px-3 py-1.5 text-[11px] font-semibold ${
              showMine ? "border-aurora-cyan/60 bg-aurora-cyan/15 text-aurora-cyan" : "border-line text-ink-300 hover:bg-white/5"}`}>
            My orders &amp; fills{orders.length ? ` (${orders.length})` : ""}
          </button>
        </div>

        {/* the chart */}
        <div className="min-h-0 flex-1 overflow-y-auto rounded-xl border border-line bg-obsidian-900/40 p-2">
          {bars === 0 && (
            <div className="flex h-full items-center justify-center text-[12px] text-ink-400">
              No candles for this instrument and interval yet — it needs prints on the tape to build bars.
            </div>
          )}

          {/* overlay legend — which line is which, without hovering */}
          {priceOverlays.length > 0 && (
            <div className="mb-1 flex flex-wrap gap-1.5 px-1">
              {priceOverlays.map((a) => (
                <span key={a.uid} title={byId(a.id)?.hint}
                  className="flex items-center gap-1 rounded px-1.5 py-0.5 text-[10px] font-semibold"
                  style={{ color: a.colour, background: hexAlpha(a.colour, 0.12) }}>
                  <span className="inline-block h-[2px] w-3 rounded" style={{ background: a.colour }} />
                  {describeUnique(a, priceOverlays)}
                  {/* the value under the crosshair, in the chip itself — the TradingView reading */}
                  {valuesAt(a.uid).map((v) => (
                    <span key={v.name} className="tabular-nums text-ink-100">{nf(v.value)}</span>
                  ))}
                </span>
              ))}
            </div>
          )}

          <div className="relative w-full">
            <div ref={priceRef} className="w-full" />

            {/*
              The floating readout. Positioned beside the pointer and flipped when it would run off
              the right edge, because a tooltip that leaves the viewport is worse than none. It is
              pointer-events-none so it can never intercept a click meant for the chart.
            */}
            {hover?.candle && hover.pane === "price" && (
              <div
                className="pointer-events-none absolute z-20 rounded-lg border border-line/[0.14] bg-obsidian-950/92 px-2.5 py-1.5 shadow-2xl backdrop-blur-sm"
                style={{
                  left: hover.x > (priceRef.current?.clientWidth ?? 0) - 200 ? hover.x - 186 : hover.x + 16,
                  top: Math.max(4, hover.y - 8),
                }}>
                <div className="mb-1 text-[10px] font-semibold uppercase tracking-wider text-ink-400">{hoverTime()}</div>
                <div className="grid grid-cols-2 gap-x-3 gap-y-0.5 text-[11px] tabular-nums">
                  <span className="text-ink-500">Open</span><span className="text-right text-ink-100">{nf(hover.candle.open)}</span>
                  <span className="text-ink-500">High</span><span className="text-right text-bull">{nf(hover.candle.high)}</span>
                  <span className="text-ink-500">Low</span><span className="text-right text-bear">{nf(hover.candle.low)}</span>
                  <span className="text-ink-500">Close</span>
                  <span className={`text-right font-semibold ${hover.candle.close >= hover.candle.open ? "text-bull" : "text-bear"}`}>
                    {nf(hover.candle.close)}
                  </span>
                  {(() => {
                    const chg = hover.candle.open ? ((hover.candle.close - hover.candle.open) / hover.candle.open) * 100 : 0;
                    return (
                      <>
                        <span className="text-ink-500">Change</span>
                        <span className={`text-right ${chg >= 0 ? "text-bull" : "text-bear"}`}>
                          {chg >= 0 ? "+" : ""}{nf(chg, 2)}%
                        </span>
                      </>
                    );
                  })()}
                  {typeof hover.candle.volume === "number" && (
                    <>
                      <span className="text-ink-500">Volume</span>
                      <span className="text-right text-ink-200">{nf(hover.candle.volume, 0)}</span>
                    </>
                  )}
                </div>

                {priceOverlays.some((a) => valuesAt(a.uid).length > 0) && (
                  <div className="mt-1.5 border-t border-line/[0.1] pt-1.5">
                    {priceOverlays.map((a) => {
                      const vals = valuesAt(a.uid);
                      if (!vals.length) return null;
                      return (
                        <div key={a.uid} className="flex items-baseline gap-2 text-[11px]">
                          <span className="inline-block h-[2px] w-3 shrink-0 rounded" style={{ background: a.colour }} />
                          <span className="flex-1 truncate" style={{ color: a.colour }}>{describeUnique(a, priceOverlays)}</span>
                          <span className="tabular-nums text-ink-100">
                            {vals.map((v) => nf(v.value)).join(" / ")}
                          </span>
                        </div>
                      );
                    })}
                  </div>
                )}
              </div>
            )}
          </div>

          {subStudies.map((a) => (
            <div key={a.uid} className="mt-1 border-t border-line pt-1">
              <div className="mb-0.5 flex items-center gap-2 px-1">
                <span className="text-[10px] font-semibold uppercase tracking-wider" style={{ color: a.colour }}>
                  {describeUnique(a, subStudies)}
                </span>
                {valuesAt(a.uid).map((v) => (
                  <span key={v.name} className="text-[10px] tabular-nums text-ink-100">
                    <span className="text-ink-500">{v.name}</span> {nf(v.value)}
                  </span>
                ))}
                {!hover && <span className="truncate text-[10px] text-ink-500">{byId(a.id)?.hint}</span>}
                <button onClick={() => setApplied(applied.filter((x) => x.uid !== a.uid))}
                  title="Remove this study"
                  className="ml-auto rounded px-1.5 text-[12px] leading-none text-ink-500 hover:bg-bear/15 hover:text-bear">
                  ×
                </button>
              </div>
              <div ref={(el) => { subRefs.current[a.uid] = el; }} className="w-full" />
            </div>
          ))}
        </div>

        <div className="flex flex-wrap items-center gap-4 rounded-xl border border-line bg-obsidian-900/60 px-3 py-1.5 text-[11px] text-ink-400">
          <span>{bars} bars · {TFS.find((t) => t.k === tf)?.label ?? tf}</span>
          {applied.length > 0 && (
            <span className="text-ink-300">
              {priceOverlays.length} overlay{priceOverlays.length === 1 ? "" : "s"} · {subStudies.length} pane{subStudies.length === 1 ? "" : "s"}
            </span>
          )}
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

      <IndicatorDialog open={dialog} applied={applied} onClose={() => setDialog(false)} onChange={setApplied} />
    </Shell>
  );
}
