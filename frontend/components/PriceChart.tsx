"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { API, feedForecast } from "@/lib/api";
import { nf } from "@/lib/format";
import { INDICATORS, IndicatorId, compute, Candle } from "@/lib/indicators";
import { cssRGB, INDICATOR_STORE } from "@/lib/chartTheme";

const TFS = [
  { k: "1m", label: "1m" }, { k: "5m", label: "5m" }, { k: "15m", label: "15m" }, { k: "1d", label: "1D" },
];

export function PriceChart({
  securityId, symbol, exchange, ltp, height = 300,
}: { securityId: number; symbol: string; exchange: string; ltp: number; height?: number }) {
  const ref = useRef<HTMLDivElement>(null);
  const rsiRef = useRef<HTMLDivElement>(null);
  const [tf, setTf] = useState("5m");
  const [outlook, setOutlook] = useState<{ trend: string; r2: number } | null>(null);
  const [picker, setPicker] = useState(false);

  // Indicator choice is a trader's working preference, so it persists across sessions like the
  // theme and the dashboard layout do.
  const [on, setOn] = useState<IndicatorId[]>([]);
  useEffect(() => {
    try {
      const raw = localStorage.getItem(INDICATOR_STORE);
      if (raw) setOn(JSON.parse(raw));
    } catch { /* a corrupt preference is not worth blocking the chart for */ }
  }, []);
  const toggle = (id: IndicatorId) =>
    setOn((prev) => {
      const next = prev.includes(id) ? prev.filter((x) => x !== id) : [...prev, id];
      try { localStorage.setItem(INDICATOR_STORE, JSON.stringify(next)); } catch {}
      return next;
    });

  const showRsi = on.includes("RSI14");

  useEffect(() => {
    let alive = true;
    feedForecast(symbol, exchange, 5).then((f) => {
      if (alive && f) setOutlook({ trend: f.trend || "FLAT", r2: f.confidenceR2 ?? 0 });
      else if (alive) setOutlook(null);
    });
    return () => { alive = false; };
  }, [symbol, exchange]);

  useEffect(() => {
    let chart: any, rsiChart: any, ro: ResizeObserver | null = null, disposed = false;
    (async () => {
      const lib = await import("lightweight-charts");
      if (disposed || !ref.current) return;

      let candles: Candle[] = [];
      try {
        const res = await fetch(`${API}/api/market/${securityId}/candles?tf=${tf}&limit=250`);
        candles = await res.json();
      } catch { candles = []; }
      if (!Array.isArray(candles) || !candles.length) return;

      const ink = cssRGB("--ink-400");
      const line = cssRGB("--ink-600", 0.25);
      const bull = cssRGB("--bull");
      const bear = cssRGB("--bear");
      const base = {
        layout: { background: { type: lib.ColorType.Solid, color: "transparent" }, textColor: ink, fontSize: 11 },
        grid: { vertLines: { color: line }, horzLines: { color: line } },
        rightPriceScale: { borderColor: line },
        timeScale: { borderColor: line, timeVisible: tf !== "1d", secondsVisible: false },
        crosshair: { mode: lib.CrosshairMode.Normal },
      };

      ref.current.innerHTML = "";
      chart = lib.createChart(ref.current, { ...base, height: showRsi ? height - 90 : height });

      const cs = chart.addCandlestickSeries({
        upColor: bull, downColor: bear, borderVisible: false, wickUpColor: bull, wickDownColor: bear,
      });
      cs.setData(candles.map((c) => ({ time: c.time, open: c.open, high: c.high, low: c.low, close: c.close })));

      const vs = chart.addHistogramSeries({ priceFormat: { type: "volume" }, priceScaleId: "" });
      vs.priceScale().applyOptions({ scaleMargins: { top: 0.82, bottom: 0 } });
      vs.setData(candles.map((c) => ({
        time: c.time, value: c.volume ?? 0,
        color: c.close >= c.open ? cssRGB("--bull", 0.45) : cssRGB("--bear", 0.45),
      })));

      // price-pane overlays
      for (const ind of INDICATORS) {
        if (ind.pane !== "price" || !on.includes(ind.id)) continue;
        for (const series of compute(ind.id, candles)) {
          if (!series.length) continue;                 // not enough bars to warm up: draw nothing
          const ls = chart.addLineSeries({
            color: ind.colour, lineWidth: ind.id === "BB20" ? 1 : 2,
            priceLineVisible: false, lastValueVisible: false, crosshairMarkerVisible: false,
          });
          ls.setData(series);
        }
      }
      chart.timeScale().fitContent();

      // RSI lives in its own pane: it is 0..100 and would flatten a price axis into a line.
      if (showRsi && rsiRef.current) {
        rsiRef.current.innerHTML = "";
        rsiChart = lib.createChart(rsiRef.current, {
          ...base, height: 84,
          rightPriceScale: { borderColor: line, scaleMargins: { top: 0.1, bottom: 0.1 } },
        });
        const meta = INDICATORS.find((i) => i.id === "RSI14")!;
        const series = compute("RSI14", candles)[0] || [];
        const rs = rsiChart.addLineSeries({ color: meta.colour, lineWidth: 2, priceLineVisible: false, lastValueVisible: true });
        rs.setData(series);
        // 70/30 bands — the reference lines that make an RSI readable at a glance
        for (const lvl of [70, 30]) {
          rs.createPriceLine({
            price: lvl, color: cssRGB("--ink-600", 0.6), lineWidth: 1, lineStyle: 2,
            axisLabelVisible: true, title: String(lvl),
          });
        }
        rsiChart.timeScale().fitContent();
        // keep the two panes horizontally locked, so the crosshair means the same thing in both
        chart.timeScale().subscribeVisibleLogicalRangeChange((r: any) => {
          if (r) rsiChart?.timeScale().setVisibleLogicalRange(r);
        });
      }

      const resize = () => {
        if (ref.current) chart.applyOptions({ width: ref.current.clientWidth });
        if (rsiRef.current && rsiChart) rsiChart.applyOptions({ width: rsiRef.current.clientWidth });
      };
      resize();
      ro = new ResizeObserver(resize);
      ro.observe(ref.current);
    })();
    return () => { disposed = true; ro?.disconnect(); chart?.remove?.(); rsiChart?.remove?.(); };
  }, [securityId, tf, on, showRsi, height]);

  const activeLabels = useMemo(
    () => INDICATORS.filter((i) => on.includes(i.id)),
    [on],
  );

  return (
    <div className="glass p-4">
      <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
        <div className="flex flex-wrap items-center gap-2">
          <div className="panel-title">Price · Volume</div>
          {outlook && (
            <span className={`chip ${outlook.trend === "UP" ? "bg-bull/15 text-bull" : outlook.trend === "DOWN" ? "bg-bear/15 text-bear" : "bg-surface/[0.1] text-ink-400"}`}>
              AI {outlook.trend === "UP" ? "▲" : outlook.trend === "DOWN" ? "▼" : "▬"} {outlook.trend} · {nf((outlook.r2 || 0) * 100, 0)}%
            </span>
          )}
          {/* active indicators read back as a legend, colour-matched to their lines */}
          {activeLabels.map((i) => (
            <span key={i.id} title={i.hint}
              className="flex items-center gap-1 rounded px-1.5 py-0.5 text-[10px] font-semibold"
              style={{ color: i.colour, background: `${i.colour}1f` }}>
              <span className="inline-block h-[2px] w-3 rounded" style={{ background: i.colour }} />
              {i.short}
            </span>
          ))}
        </div>

        <div className="flex items-center gap-2">
          <div className="relative">
            <button onClick={() => setPicker((p) => !p)}
              className={`rounded-lg border px-2.5 py-1 text-[11px] font-semibold transition-colors ${
                picker || on.length ? "border-aurora-cyan/50 bg-aurora-cyan/15 text-aurora-cyan" : "border-line text-ink-300 hover:bg-white/5"}`}>
              Indicators{on.length ? ` (${on.length})` : ""}
            </button>
            {picker && (
              <div className="absolute right-0 z-50 mt-1 w-[248px] rounded-lg border border-line bg-obsidian-850 p-1 shadow-2xl">
                {INDICATORS.map((i) => (
                  <button key={i.id} onClick={() => toggle(i.id)} title={i.hint}
                    className={`flex w-full items-center gap-2 rounded px-2 py-1.5 text-left text-[11.5px] ${
                      on.includes(i.id) ? "bg-white/[0.07] text-ink-100" : "text-ink-300 hover:bg-white/5"}`}>
                    <span className="inline-block h-[3px] w-4 rounded" style={{ background: i.colour }} />
                    <span className="flex-1">{i.label}</span>
                    {on.includes(i.id) && <span className="text-aurora-cyan">✓</span>}
                  </button>
                ))}
                {on.length > 0 && (
                  <button onClick={() => { setOn([]); try { localStorage.setItem(INDICATOR_STORE, "[]"); } catch {} }}
                    className="mt-1 w-full rounded px-2 py-1 text-left text-[11px] text-ink-400 hover:bg-white/5">
                    Clear all
                  </button>
                )}
              </div>
            )}
          </div>

          <div className="flex rounded-lg border border-line/[0.12] bg-surface/[0.05] p-0.5">
            {TFS.map((x) => (
              <button key={x.k} onClick={() => setTf(x.k)}
                className={`rounded-md px-2.5 py-1 text-[11px] font-semibold transition-all ${tf === x.k ? "bg-gradient-to-r from-aurora-violet to-aurora-indigo text-white" : "text-ink-400 hover:text-ink-100"}`}>
                {x.label}
              </button>
            ))}
          </div>
        </div>
      </div>

      <div ref={ref} className="w-full" style={{ height: showRsi ? height - 90 : height }} />
      {showRsi && (
        <div className="mt-1">
          <div className="mb-0.5 text-[10px] font-semibold uppercase tracking-wider" style={{ color: INDICATORS.find((i) => i.id === "RSI14")!.colour }}>
            RSI 14
          </div>
          <div ref={rsiRef} className="w-full" style={{ height: 84 }} />
        </div>
      )}
      <div className="mt-1 text-[10px] text-ink-400">
        Candles &amp; volume aggregated from the live matching-engine tape · {tf.toUpperCase()} buckets
      </div>
    </div>
  );
}
