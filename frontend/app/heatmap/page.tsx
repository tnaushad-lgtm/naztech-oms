"use client";

import React, { useEffect, useMemo, useRef, useState } from "react";
import { Shell } from "@/components/Shell";
import { MarketRow } from "@/components/MarketWatch";
import { get } from "@/lib/api";
import { useLive } from "@/lib/useLive";
import { nf, pct } from "@/lib/format";

type Mode = "sectors" | "winners" | "losers" | "type";
const MODES: { k: Mode; label: string }[] = [
  { k: "sectors", label: "By Sector" },
  { k: "winners", label: "Winners" },
  { k: "losers", label: "Losers" },
  { k: "type", label: "By Type" },
];

function assetBucket(ac: string): string {
  const a = (ac || "").toUpperCase();
  if (a.includes("BOND") || a === "SUKUK") return "Bonds & Sukuk";
  if (a === "MUTUAL_FUND") return "Mutual Funds";
  if (a === "ETF") return "ETFs";
  if (a.startsWith("EQUITY")) return "Stocks";
  return "Other";
}

// ---------- diverging colour scale: slate (flat) → green (up) / red (down) ----------
type RGB = [number, number, number];
const FLAT: RGB = [51, 62, 82];
const UP: RGB = [22, 171, 96];
const DOWN: RGB = [214, 52, 62];
function lerp(a: RGB, b: RGB, t: number) {
  return `rgb(${Math.round(a[0] + (b[0] - a[0]) * t)},${Math.round(a[1] + (b[1] - a[1]) * t)},${Math.round(a[2] + (b[2] - a[2]) * t)})`;
}
function colorFor(chg: number): string {
  const t = Math.min(1, Math.abs(chg || 0) / 3);
  return lerp(FLAT, (chg || 0) >= 0 ? UP : DOWN, t);
}

// ---------- squarified treemap (Bruls, Huizing & van Wijk) ----------
type Box = { x: number; y: number; w: number; h: number };
type Leaf = { name: string; value: number; chg: number; sector: string };
type Sector = { name: string; value: number; chg: number; leaves: Leaf[] };

function worst(areas: number[], len: number, s: number): number {
  if (areas.length === 0 || s <= 0 || len <= 0) return Infinity;
  let mx = -Infinity, mn = Infinity;
  for (const a of areas) { if (a > mx) mx = a; if (a < mn) mn = a; }
  const s2 = s * s, len2 = len * len;
  return Math.max((len2 * mx) / s2, s2 / (len2 * mn));
}

/** Pack `items` (sized by `value`) into `box`, returning each item with an {x,y,w,h}. */
function squarify<T extends { value: number }>(items: T[], box: Box): (T & Box)[] {
  const out: (T & Box)[] = [];
  if (items.length === 0 || box.w <= 0 || box.h <= 0) return out;
  const total = items.reduce((s, it) => s + Math.max(1e-6, it.value), 0);
  const scale = (box.w * box.h) / total;
  const nodes = items.map((it) => ({ it, area: Math.max(1e-6, it.value) * scale }));
  let { x, y, w, h } = box;
  let i = 0;
  const n = nodes.length;
  while (i < n) {
    const len = Math.min(w, h);
    let rowSum = 0;
    const rowAreas: number[] = [];
    let j = i;
    while (j < n) {
      const cur = worst(rowAreas, len, rowSum);
      const nxt = worst([...rowAreas, nodes[j].area], len, rowSum + nodes[j].area);
      if (rowAreas.length === 0 || nxt <= cur) { rowAreas.push(nodes[j].area); rowSum += nodes[j].area; j++; }
      else break;
    }
    if (w >= h) {
      const stripW = h > 0 ? rowSum / h : 0;
      let cy = y;
      for (let k = i; k < j; k++) {
        const cellH = stripW > 0 ? nodes[k].area / stripW : 0;
        out.push({ ...(nodes[k].it as any), x, y: cy, w: stripW, h: cellH });
        cy += cellH;
      }
      x += stripW; w -= stripW;
    } else {
      const stripH = w > 0 ? rowSum / w : 0;
      let cx = x;
      for (let k = i; k < j; k++) {
        const cellW = stripH > 0 ? nodes[k].area / stripH : 0;
        out.push({ ...(nodes[k].it as any), x: cx, y, w: cellW, h: stripH });
        cx += cellW;
      }
      y += stripH; h -= stripH;
    }
    i = j;
  }
  return out;
}

/** Trim a label to roughly the pixels available (px per char depends on font size). */
function clip(s: string, px: number, charPx = 6.4): string {
  const max = Math.max(1, Math.floor(px / charPx));
  return s.length <= max ? s : s.slice(0, Math.max(1, max - 1)) + "…";
}

// ---------- leaf cell (memoised so hover never re-renders 400 cells) ----------
const LeafCell = React.memo(function LeafCell({
  lf, onEnter, onLeave,
}: { lf: Leaf & Box; onEnter: (l: Leaf & Box) => void; onLeave: () => void }) {
  const { x, y, w, h, name, chg } = lf;
  if (w < 1.5 || h < 1.5) return null;
  const fitFs = (w - 6) / Math.max(3, name.length) / 0.62;
  // only label when the ticker genuinely fits (≥7.2 keeps the 7px floor from forcing overflow)
  const showTicker = w > 30 && h > 15 && fitFs >= 7.2;
  const fs = Math.max(7, Math.min(17, Math.min(w, h) / 3.6, fitFs));
  const pctStr = pct(chg);
  const pctFs = Math.max(8, fs * 0.72);
  const showPct = showTicker && w > 44 && h > 33 && pctStr.length * pctFs * 0.6 <= w - 4;
  return (
    <g onMouseEnter={() => onEnter(lf)} onMouseLeave={onLeave} style={{ cursor: "default" }}>
      <rect x={x} y={y} width={w} height={h} fill={colorFor(chg)} stroke="rgba(6,10,20,0.9)" strokeWidth={1} />
      {showTicker && (
        <text x={x + w / 2} y={y + h / 2 + (showPct ? -2 : fs * 0.34)} textAnchor="middle"
          fill="#ffffff" fontSize={fs} fontWeight={700}
          stroke="rgba(0,0,0,0.5)" strokeWidth={fs > 11 ? 0.8 : 0.5} paintOrder="stroke">{name}</text>
      )}
      {showPct && (
        <text x={x + w / 2} y={y + h / 2 + fs * 0.92} textAnchor="middle"
          fill="rgba(255,255,255,0.95)" fontSize={pctFs}
          stroke="rgba(0,0,0,0.45)" strokeWidth={0.4} paintOrder="stroke">{pctStr}</text>
      )}
    </g>
  );
});

const HEAD = 19; // full header-strip height, like TradingView's "Sector >" bar

export default function HeatmapPage() {
  const [exchange, setExchange] = useState("DSE");
  const [mode, setMode] = useState<Mode>("sectors");
  const [rows, setRows] = useState<MarketRow[]>([]);
  const [loaded, setLoaded] = useState(false);
  const [size, setSize] = useState({ w: 0, h: 0 });
  const [hover, setHover] = useState<(Leaf & Box) | null>(null);
  const wrapRef = useRef<HTMLDivElement>(null);
  const tipRef = useRef<HTMLDivElement>(null);
  const seqRef = useRef(0);      // monotonic request id
  const appliedRef = useRef(0);  // last request id whose response was applied
  const { connected } = useLive(() => {});

  // apply only the newest response — kills out-of-order polls and DSE↔CSE clobber
  const load = async (ex: string) => {
    const seq = ++seqRef.current;
    try {
      const d = await get<MarketRow[]>(`/api/market/watch?exchange=${ex}`);
      if (seq >= appliedRef.current) { appliedRef.current = seq; setRows(d || []); setLoaded(true); }
    } catch {}
  };
  useEffect(() => { load(exchange); const t = setInterval(() => load(exchange), 5000); return () => clearInterval(t); }, [exchange]);

  useEffect(() => {
    const el = wrapRef.current;
    if (!el || typeof ResizeObserver === "undefined") return;
    const measure = () => setSize({ w: el.clientWidth, h: el.clientHeight });
    const ro = new ResizeObserver(measure);
    ro.observe(el); measure();
    return () => ro.disconnect();
  }, []);

  // stable hover callbacks so memoised leaves never re-render on hover
  const onEnter = React.useCallback((l: Leaf & Box) => setHover(l), []);
  const onLeave = React.useCallback(() => setHover(null), []);
  // tooltip is permanently mounted (opacity-toggled) so it never flashes at (0,0)
  const onMove = (e: React.MouseEvent) => {
    const t = tipRef.current;
    if (t) { t.style.left = e.clientX + 14 + "px"; t.style.top = e.clientY + 14 + "px"; }
  };

  const sectors = useMemo<Sector[]>(() => {
    let items = rows.filter((r) => r.assetClass !== "INDEX" && (r.valueMn || 0) > 0);
    if (mode === "winners") items = items.filter((r) => Number(r.changePct) > 0);
    if (mode === "losers") items = items.filter((r) => Number(r.changePct) < 0);
    const groupOf = (r: MarketRow) => (mode === "type" ? assetBucket(r.assetClass) : (r.sector || "Other"));
    const groups: Record<string, Leaf[]> = {};
    items.forEach((r) => (groups[groupOf(r)] ||= []).push({
      name: r.symbol, value: Math.max(0.01, r.valueMn), chg: Number(r.changePct) || 0, sector: r.sector || "—",
    }));
    return Object.entries(groups).map(([name, leaves]) => {
      const value = leaves.reduce((s, l) => s + l.value, 0);
      const chg = leaves.reduce((s, l) => s + l.chg * l.value, 0) / Math.max(1e-6, value);
      return { name, value, chg, leaves: leaves.sort((a, b) => b.value - a.value) };
    });
  }, [rows, mode]);

  // two-level layout: place sectors, reserve a header strip, then pack each sector's leaves
  const layout = useMemo(() => {
    if (size.w < 40 || size.h < 40 || sectors.length === 0) return [];
    const boxes = squarify(sectors, { x: 0, y: 0, w: size.w, h: size.h });
    return boxes.map((sb) => {
      // full header where it fits; a compact one in narrower/shorter boxes so a sector
      // is *always* named on-canvas down to ~40px wide (the reported "which sector?" gap)
      const head = sb.h > 40 && sb.w > 58 ? HEAD : (sb.h > 24 && sb.w > 40 ? 15 : 0);
      const inner: Box = { x: sb.x + 1, y: sb.y + head, w: Math.max(0, sb.w - 2), h: Math.max(0, sb.h - head - 1) };
      const leaves = squarify(sb.leaves, inner);
      return { name: sb.name, chg: sb.chg, x: sb.x, y: sb.y, w: sb.w, h: sb.h, head, count: sb.leaves.length, leaves };
    });
  }, [sectors, size]);

  const count = layout.reduce((n, s) => n + s.count, 0);
  const groupWord = mode === "type" ? "type" : "sector";

  const header = (
    <div className="hidden sm:flex rounded-xl border border-line/[0.12] bg-surface/[0.05] p-0.5">
      {["DSE", "CSE"].map((ex) => (
        <button key={ex} onClick={() => setExchange(ex)}
          className={`rounded-lg px-3 py-1.5 text-xs font-semibold transition-all ${
            exchange === ex ? "bg-gradient-to-r from-aurora-violet to-aurora-indigo text-white shadow-glow" : "text-ink-400 hover:text-ink-100"}`}>
          {ex}
        </button>
      ))}
    </div>
  );

  return (
    <Shell title="Market Heatmap" connected={connected} headerRight={header}>
      <div className="flex h-full flex-col gap-3">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <div className="glass-soft flex rounded-xl p-0.5">
            {MODES.map((m) => (
              <button key={m.k} onClick={() => setMode(m.k)}
                className={`rounded-lg px-3 py-1.5 text-xs font-semibold transition-all ${
                  mode === m.k ? "bg-gradient-to-r from-aurora-violet to-aurora-indigo text-white" : "text-ink-400 hover:text-ink-100"}`}>
                {m.label}
              </button>
            ))}
          </div>
          <div className="flex items-center gap-2 text-[11px] text-ink-400">
            <span>−3%</span>
            {[-3, -1.5, 0, 1.5, 3].map((v) => (
              <span key={v} className="inline-block h-3.5 w-6 rounded-[2px]" style={{ background: colorFor(v) }} />
            ))}
            <span>+3%</span>
          </div>
        </div>
        <div className="text-[12px] text-ink-500">
          Box size = <span className="text-ink-300">turnover</span> · colour = <span className="text-bull">gain</span> / <span className="text-bear">loss</span> · each block is a <span className="text-ink-300">{groupWord}</span> (name in its header bar)
          {mode === "winners" && <span className="text-bull"> · winners only (price up)</span>}
          {mode === "losers" && <span className="text-bear"> · losers only (price down)</span>}
          {" "}· {count} instruments
        </div>
        <div ref={wrapRef} onMouseMove={onMove}
          className="relative min-h-0 flex-1 overflow-hidden rounded-xl border border-line/[0.1] bg-obsidian-950/40">
          {size.w > 0 && layout.length > 0 ? (
            <svg width={size.w} height={size.h} style={{ display: "block" }}>
              {layout.map((s, si) => (
                <g key={s.name + si}>
                  <rect x={s.x} y={s.y} width={s.w} height={s.h} fill="rgba(8,12,22,0.55)" />
                  {s.leaves.map((lf, li) => <LeafCell key={lf.name + li} lf={lf} onEnter={onEnter} onLeave={onLeave} />)}
                  <rect x={s.x} y={s.y} width={s.w} height={s.h} fill="none" stroke="rgba(150,168,205,0.38)" strokeWidth={1.25} />
                  {s.head > 0 && (() => {
                    const small = s.head < 19;
                    const fsz = small ? 9 : 11;
                    const charPx = small ? 5.4 : 6.4;
                    const showSecPct = s.w > 132;
                    const avail = s.w - 13 - (showSecPct ? 52 : 0);
                    return (
                      <g style={{ pointerEvents: "none" }}>
                        <rect x={s.x} y={s.y} width={s.w} height={s.head} fill="rgba(6,10,20,0.86)" />
                        <rect x={s.x} y={s.y} width={3} height={s.head} fill={colorFor(s.chg)} />
                        <text x={s.x + 9} y={s.y + s.head / 2 + (small ? 3.2 : 3.6)} fill="#e9eeff" fontSize={fsz}
                          fontWeight={700} letterSpacing={0.3} className="uppercase">{clip(s.name, avail, charPx)}</text>
                        {showSecPct && (
                          <text x={s.x + s.w - 8} y={s.y + s.head / 2 + 3.6} textAnchor="end"
                            fill={s.chg >= 0 ? "#4ade80" : "#f87171"} fontSize={10} fontWeight={600}>{pct(s.chg)}</text>
                        )}
                      </g>
                    );
                  })()}
                </g>
              ))}
            </svg>
          ) : (
            <div className="grid h-full place-items-center px-6 text-center text-ink-500">
              {!loaded || size.w === 0 ? "Loading market heatmap…"
                : mode === "winners" ? "No gainers right now."
                : mode === "losers" ? "No losers right now."
                : "No instruments with turnover to display right now."}
            </div>
          )}
          {/* permanently mounted, opacity-toggled → never flashes at (0,0) */}
          <div ref={tipRef} className="pointer-events-none fixed z-50 rounded-lg border border-line/[0.2] bg-obsidian-950/95 px-3 py-2 shadow-xl"
            style={{ left: 0, top: 0, opacity: hover ? 1 : 0, transition: "opacity 80ms" }}>
            {hover && (
              <>
                <div className="text-[13px] font-bold text-ink-100">{hover.name}</div>
                <div className={`text-[12px] font-semibold tnum ${hover.chg >= 0 ? "text-bull" : "text-bear"}`}>{pct(hover.chg)}</div>
                <div className="mt-0.5 text-[11px] text-ink-500">{hover.sector}</div>
                <div className="text-[11px] tnum text-ink-500">Turnover ৳{nf(hover.value)}M</div>
              </>
            )}
          </div>
        </div>
      </div>
    </Shell>
  );
}
