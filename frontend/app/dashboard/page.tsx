"use client";

import "react-grid-layout/css/styles.css";
import "react-resizable/css/styles.css";

import { useEffect, useMemo, useRef, useState } from "react";
import GridLayout, { WidthProvider } from "react-grid-layout";
import { motion, AnimatePresence } from "framer-motion";
import { Shell } from "@/components/Shell";
import { WidgetChrome } from "@/components/widgets/WidgetChrome";
import { WIDGETS, WIDGET_MAP, DEFAULT_LAYOUT, PRESETS, layoutFromIds } from "@/components/widgets/registry";
import { Highlight } from "@/components/Highlight";
import { DashboardProvider, useDash } from "@/lib/dashboardData";
import { getSession } from "@/lib/session";

const Grid = WidthProvider(GridLayout);
type Item = { i: string; x: number; y: number; w: number; h: number; minW: number; minH: number; isResizable?: boolean };

function buildDefault(): Item[] {
  return DEFAULT_LAYOUT.map((d) => {
    const def = WIDGET_MAP[d.id];
    return { i: d.id, x: d.x, y: d.y, w: def.w, h: def.h, minW: def.minW, minH: def.minH };
  });
}

function Board() {
  const dash = useDash();
  const [layout, setLayout] = useState<Item[]>([]);
  const [collapsed, setCollapsed] = useState<Set<string>>(new Set());
  const [maximized, setMaximized] = useState<string | null>(null);
  const [menuOpen, setMenuOpen] = useState(false);
  const expandedH = useRef<Record<string, number>>({});
  const loaded = useRef(false);
  const [userPresets, setUserPresets] = useState<{ name: string; layout: Item[]; collapsed: string[] }[]>([]);

  const storageKey = () => `oms_dash_${getSession()?.username || "guest"}`;
  const presetsKey = () => `oms_dash_presets_${getSession()?.username || "guest"}`;
  const loadUserPresets = () => { try { setUserPresets(JSON.parse(localStorage.getItem(presetsKey()) || "[]")); } catch { setUserPresets([]); } };
  useEffect(() => { loadUserPresets(); }, []);

  const applyPresetIds = (ids: string[]) => { expandedH.current = {}; setCollapsed(new Set()); setLayout(layoutFromIds(ids) as Item[]); };
  const applyUserPreset = (p: { layout: Item[]; collapsed: string[] }) => { expandedH.current = {}; setCollapsed(new Set(p.collapsed || [])); setLayout(p.layout); };
  const onPresetPick = (val: string) => {
    if (val.startsWith("b:")) { const p = PRESETS.find((x) => x.name === val.slice(2)); if (p) applyPresetIds(p.widgets); }
    else if (val.startsWith("u:")) { const p = userPresets.find((x) => x.name === val.slice(2)); if (p) applyUserPreset(p); }
  };
  const savePresetAs = () => {
    const name = prompt("Save current dashboard as preset — name:");
    if (!name) return;
    const next = [...userPresets.filter((p) => p.name !== name), { name, layout, collapsed: [...collapsed] }];
    localStorage.setItem(presetsKey(), JSON.stringify(next));
    setUserPresets(next);
  };
  const deleteUserPreset = (name: string) => {
    const next = userPresets.filter((p) => p.name !== name);
    localStorage.setItem(presetsKey(), JSON.stringify(next));
    setUserPresets(next);
  };

  useEffect(() => {
    try {
      const saved = localStorage.getItem(storageKey());
      if (saved) {
        const p = JSON.parse(saved);
        setLayout(p.layout || buildDefault());
        setCollapsed(new Set(p.collapsed || []));
        expandedH.current = p.expandedH || {};
      } else setLayout(buildDefault());
    } catch { setLayout(buildDefault()); }
    loaded.current = true;
  }, []);

  // persist
  useEffect(() => {
    if (!loaded.current || !layout.length) return;
    localStorage.setItem(storageKey(), JSON.stringify({
      layout, collapsed: [...collapsed], expandedH: expandedH.current,
    }));
  }, [layout, collapsed]);

  const onLayoutChange = (l: any[]) => {
    if (!loaded.current) return;
    setLayout((prev) => l.map((it) => {
      const old = prev.find((p) => p.i === it.i);
      return { ...old, i: it.i, x: it.x, y: it.y, w: it.w, h: it.h } as Item;
    }));
  };

  const toggleCollapse = (id: string) => {
    setLayout((prev) => prev.map((it) => {
      if (it.i !== id) return it;
      if (collapsed.has(id)) {
        return { ...it, h: expandedH.current[id] || it.minH || 5, isResizable: true };
      }
      expandedH.current[id] = it.h;
      return { ...it, h: 1, isResizable: false };
    }));
    setCollapsed((prev) => { const n = new Set(prev); n.has(id) ? n.delete(id) : n.add(id); return n; });
  };

  const hide = (id: string) => {
    setLayout((prev) => prev.filter((it) => it.i !== id));
    setCollapsed((prev) => { const n = new Set(prev); n.delete(id); return n; });
    if (maximized === id) setMaximized(null);
  };

  const addWidget = (id: string) => {
    if (layout.some((it) => it.i === id)) return;
    const def = WIDGET_MAP[id];
    setLayout((prev) => [...prev, { i: id, x: 0, y: Infinity, w: def.w, h: def.h, minW: def.minW, minH: def.minH }]);
  };

  const reset = () => { expandedH.current = {}; setCollapsed(new Set()); setLayout(buildDefault()); };

  const activeIds = useMemo(() => new Set(layout.map((i) => i.i)), [layout]);

  /**
   * Library search. The catalogue has outgrown the eye: four columns of similarly-shaped buttons is
   * a list you scan, not a list you search, and finding one chart among two dozen takes longer than
   * placing it. Matching runs over title, subtitle, category AND keywords, so "graph", "pie" or
   * "pnl" all reach the right widget without knowing what we happened to name it.
   */
  const [wq, setWq] = useState("");
  const grouped = useMemo(() => {
    const q = wq.trim().toLowerCase();
    const hit = (w: (typeof WIDGETS)[number]) =>
      !q || `${w.title} ${w.subtitle || ""} ${w.category} ${w.keywords || ""}`.toLowerCase().includes(q);
    const g: Record<string, typeof WIDGETS> = {};
    WIDGETS.filter(hit).forEach((w) => { (g[w.category] = g[w.category] || []).push(w); });
    return g;
  }, [wq]);
  const matchCount = useMemo(() => Object.values(grouped).reduce((n, l) => n + l.length, 0), [grouped]);

  const header = (
    <div className="flex items-center gap-2">
      {dash.accounts.length > 0 && (
        <select className="field py-1.5 text-xs max-w-[200px]" value={dash.accountId ?? ""}
          onChange={(e) => dash.setAccountId(parseInt(e.target.value))}>
          {dash.accounts.map((a) => <option key={a.id} value={a.id} className="bg-obsidian-850">{a.name}</option>)}
        </select>
      )}
      <div className="hidden sm:flex rounded-xl border border-line/[0.12] bg-surface/[0.05] p-0.5">
        {["DSE", "CSE"].map((ex) => (
          <button key={ex} onClick={() => dash.setExchange(ex)}
            className={`rounded-lg px-2.5 py-1.5 text-xs font-semibold transition-all ${dash.exchange === ex ? "bg-gradient-to-r from-aurora-violet to-aurora-indigo text-white" : "text-ink-400 hover:text-ink-100"}`}>{ex}</button>
        ))}
      </div>
      <select className="field max-w-[170px] py-1.5 text-xs" value="" onChange={(e) => { onPresetPick(e.target.value); e.target.value = ""; }}>
        <option value="" className="bg-obsidian-850">⌗ Load preset…</option>
        <optgroup label="Built-in">
          {PRESETS.map((p) => <option key={p.name} value={`b:${p.name}`} className="bg-obsidian-850">{p.name}</option>)}
        </optgroup>
        {userPresets.length > 0 && (
          <optgroup label="My presets">
            {userPresets.map((p) => <option key={p.name} value={`u:${p.name}`} className="bg-obsidian-850">{p.name}</option>)}
          </optgroup>
        )}
      </select>
      <button onClick={savePresetAs} className="ghost-btn py-1.5 text-xs" title="Save current layout as a preset">💾 Save</button>
      <button onClick={reset} className="ghost-btn py-1.5 text-xs">Reset</button>
      <button onClick={() => setMenuOpen((o) => !o)} className="aurora-btn py-1.5 text-xs">+ Add widget</button>
    </div>
  );

  const maxDef = maximized ? WIDGET_MAP[maximized] : null;

  return (
    <Shell title="My Dashboard" connected={dash.connected} headerRight={header}>
      {/* add-widget panel */}
      <AnimatePresence>
        {menuOpen && (
          <motion.div initial={{ opacity: 0, y: -8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -8 }}
            className="glass mb-4 p-4">
            <div className="mb-3 flex flex-wrap items-center gap-2">
              <div className="panel-title">Widget Library — click to add</div>
              <div className="relative ml-2 min-w-[240px] flex-1 sm:max-w-[380px]">
                <input
                  autoFocus
                  value={wq}
                  onChange={(e) => setWq(e.target.value)}
                  onKeyDown={(e) => { if (e.key === "Escape") { e.stopPropagation(); setWq(""); } }}
                  placeholder="Search widgets — try chart, pnl, risk, table…"
                  className="w-full rounded-lg border border-line/[0.15] bg-surface/[0.06] px-3 py-1.5 pr-16 text-[12px] text-ink-100 outline-none placeholder:text-ink-500 focus:border-aurora-cyan/40"
                />
                <span className="pointer-events-none absolute right-2 top-1/2 -translate-y-1/2 text-[10px] text-ink-500">
                  {wq ? `${matchCount}` : WIDGETS.length}
                </span>
              </div>
              {wq && (
                <button onClick={() => setWq("")} className="ghost-btn py-1 text-xs" title="Clear search">Clear</button>
              )}
              <button onClick={() => setMenuOpen(false)} className="ghost-btn py-1 text-xs ml-auto">Done</button>
            </div>
            {userPresets.length > 0 && (
              <div className="mb-3 flex flex-wrap items-center gap-2">
                <span className="text-[11px] text-ink-500">My presets:</span>
                {userPresets.map((p) => (
                  <span key={p.name} className="chip flex items-center gap-1 bg-surface/[0.1] text-ink-300">
                    <button onClick={() => applyUserPreset(p)} className="hover:text-aurora-cyan">{p.name}</button>
                    <button onClick={() => deleteUserPreset(p.name)} className="text-ink-600 hover:text-bear">✕</button>
                  </span>
                ))}
              </div>
            )}
            {matchCount === 0 && (
              <div className="rounded-lg border border-line/[0.12] bg-surface/[0.04] px-3 py-6 text-center text-[12px] text-ink-400">
                Nothing matches “{wq}”. Try <span className="text-ink-200">chart</span>,{" "}
                <span className="text-ink-200">table</span>, <span className="text-ink-200">pnl</span>,{" "}
                <span className="text-ink-200">risk</span> or <span className="text-ink-200">sector</span>.
              </div>
            )}
            <div className="grid grid-cols-1 gap-4 md:grid-cols-2 lg:grid-cols-4">
              {Object.entries(grouped).map(([cat, items]) => (
                <div key={cat}>
                  <div className="mb-1.5 text-[11px] font-semibold uppercase tracking-wider text-aurora-cyan/80">{cat}</div>
                  <div className="space-y-1.5">
                    {items.map((w) => {
                      const on = activeIds.has(w.id);
                      return (
                        <button key={w.id} onClick={() => (on ? hide(w.id) : addWidget(w.id))}
                          className={`flex w-full items-center justify-between rounded-lg border px-3 py-2 text-left text-[12px] transition-colors
                            ${on ? "border-aurora-indigo/40 bg-aurora-indigo/10" : "border-line/[0.1] bg-surface/[0.05] hover:bg-surface/[0.1]"}`}>
                          <span className="min-w-0">
                            <span className="block truncate font-medium text-ink-100"><Highlight text={w.title} q={wq} /></span>
                            {w.subtitle && <span className="block truncate text-[10px] text-ink-400"><Highlight text={w.subtitle} q={wq} /></span>}
                          </span>
                          <span className={`chip shrink-0 ${on ? "bg-bull/15 text-bull" : "bg-surface/[0.1] text-ink-500"}`}>{on ? "Added" : "Add"}</span>
                        </button>
                      );
                    })}
                  </div>
                </div>
              ))}
            </div>
          </motion.div>
        )}
      </AnimatePresence>

      {layout.length === 0 ? (
        <div className="glass grid h-64 place-items-center text-ink-500">
          No widgets. Click <span className="aurora-text mx-1 font-semibold">+ Add widget</span> to build your board.
        </div>
      ) : (
        <Grid className="layout" layout={layout as any} cols={12} rowHeight={40} margin={[14, 14]}
          draggableHandle=".drag-handle" onLayoutChange={onLayoutChange} compactType="vertical" isBounded={false}>
          {layout.map((it) => {
            const def = WIDGET_MAP[it.i];
            if (!def) return <div key={it.i} />;
            const C = def.Component;
            const isCol = collapsed.has(it.i);
            return (
              <div key={it.i} className="overflow-hidden">
                <WidgetChrome title={def.title} subtitle={def.subtitle} collapsed={isCol}
                  onToggleCollapse={() => toggleCollapse(it.i)}
                  onMaximize={() => setMaximized(it.i)} onClose={() => hide(it.i)}>
                  <C />
                </WidgetChrome>
              </div>
            );
          })}
        </Grid>
      )}

      {/* maximized overlay */}
      <AnimatePresence>
        {maxDef && (
          <motion.div initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}
            className="fixed inset-0 z-50 flex items-center justify-center bg-obsidian-950/80 backdrop-blur-sm p-6"
            onClick={() => setMaximized(null)}>
            <motion.div initial={{ scale: 0.96 }} animate={{ scale: 1 }} exit={{ scale: 0.96 }}
              className="h-[85vh] w-[92vw] max-w-6xl" onClick={(e) => e.stopPropagation()}>
              <WidgetChrome title={maxDef.title} subtitle={maxDef.subtitle} maximized
                onMaximize={() => setMaximized(null)} onClose={() => setMaximized(null)}>
                <maxDef.Component />
              </WidgetChrome>
            </motion.div>
          </motion.div>
        )}
      </AnimatePresence>
    </Shell>
  );
}

export default function DashboardPage() {
  return (
    <DashboardProvider>
      <Board />
    </DashboardProvider>
  );
}
