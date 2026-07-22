"use client";

/**
 * The indicator picker — the gear on the Advanced Chart.
 *
 * Two panes, because choosing and tuning are different jobs. The left is the catalogue: search it,
 * click to add. The right is what is currently on the chart, each instance with its own periods,
 * its own colour and its own remove button. Adding EMA twice is the normal case, not an edge case.
 *
 * The idiom follows the dashboard's widget library (search + grouped catalogue + live match count)
 * rather than PriceChart's flat checklist dropdown, because the catalogue has outgrown a checklist:
 * twenty indicators in one scrolling column is a list you scan, not one you search.
 */

import { useEffect, useMemo, useRef, useState } from "react";
import { AnimatePresence, motion } from "framer-motion";
import { createPortal } from "react-dom";
import {
  Applied, CatalogEntry, CATALOGUE, Category, PALETTE,
  byId, describeUnique, newApplied,
} from "@/lib/indicatorCatalogue";

const ORDER: Category[] = ["Moving averages", "Bands & channels", "Momentum", "Volatility", "Volume", "Levels"];

/** Rank a catalogue entry against the query. Lower is better; -1 means no match. */
function rank(e: CatalogEntry, q: string): number {
  if (!q) return 50;
  const id = e.id.toLowerCase();
  const label = e.label.toLowerCase();
  const kw = (e.keywords || "").toLowerCase();
  const cat = e.category.toLowerCase();

  if (id === q) return 0;
  if (label === q) return 1;
  if (id.startsWith(q)) return 2;
  if (label.startsWith(q)) return 3;
  if (label.split(/[\s/&]+/).some((w) => w.startsWith(q))) return 4;
  if (kw.split(/\s+/).some((w) => w.startsWith(q))) return 5;   // "stoch" -> Stochastic
  if (label.includes(q) || id.includes(q)) return 6;
  if (kw.includes(q)) return 7;
  if (cat.includes(q)) return 8;                                // "momentum" lists the whole group
  if (e.hint.toLowerCase().includes(q)) return 9;
  return -1;
}

function Highlighted({ text, q }: { text: string; q: string }) {
  if (!q) return <>{text}</>;
  const i = text.toLowerCase().indexOf(q);
  if (i < 0) return <>{text}</>;
  return (
    <>
      {text.slice(0, i)}
      <mark className="bg-aurora-cyan/25 text-aurora-cyan">{text.slice(i, i + q.length)}</mark>
      {text.slice(i + q.length)}
    </>
  );
}

export function IndicatorDialog({
  open, applied, onClose, onChange,
}: {
  open: boolean;
  applied: Applied[];
  onClose: () => void;
  onChange: (next: Applied[]) => void;
}) {
  const [q, setQ] = useState("");
  const [mounted, setMounted] = useState(false);
  const searchRef = useRef<HTMLInputElement>(null);

  useEffect(() => setMounted(true), []);

  // Escape closes. Every other modal in this app omits this and each one is worse for it — a
  // dialog you cannot dismiss from the keyboard interrupts a keyboard-driven workflow.
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") { e.stopPropagation(); onClose(); } };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open, onClose]);

  useEffect(() => {
    if (open) setTimeout(() => searchRef.current?.focus(), 60);
    else setQ("");
  }, [open]);

  const query = q.trim().toLowerCase();
  const groups = useMemo(() => {
    const scored = CATALOGUE
      .map((e) => ({ e, score: rank(e, query) }))
      .filter((x) => x.score >= 0)
      .sort((a, b) => a.score - b.score || a.e.label.localeCompare(b.e.label));
    const out: { category: Category; items: CatalogEntry[] }[] = [];
    for (const cat of ORDER) {
      const items = scored.filter((x) => x.e.category === cat).map((x) => x.e);
      if (items.length) out.push({ category: cat, items });
    }
    return out;
  }, [query]);

  const matchCount = groups.reduce((n, g) => n + g.items.length, 0);
  const countOf = (id: string) => applied.filter((a) => a.id === id).length;

  const add = (id: string) => {
    const a = newApplied(id, applied.length);
    if (a) onChange([...applied, a]);
  };
  const remove = (uid: string) => onChange(applied.filter((a) => a.uid !== uid));
  const patch = (uid: string, fn: (a: Applied) => Applied) =>
    onChange(applied.map((a) => (a.uid === uid ? fn(a) : a)));

  if (!mounted) return null;

  return createPortal(
    <AnimatePresence>
      {open && (
        <motion.div
          initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}
          onMouseDown={(e) => { if (e.target === e.currentTarget) onClose(); }}
          className="fixed inset-0 z-[70] flex items-center justify-center bg-obsidian-950/70 p-4 backdrop-blur-sm">
          <motion.div
            initial={{ opacity: 0, y: 8, scale: 0.99 }}
            animate={{ opacity: 1, y: 0, scale: 1 }}
            exit={{ opacity: 0, y: 8, scale: 0.99 }}
            role="dialog" aria-modal="true" aria-label="Indicators"
            className="flex h-[min(82vh,680px)] w-[min(94vw,980px)] flex-col overflow-hidden rounded-2xl border border-line/[0.12] bg-obsidian-850 shadow-2xl">

            {/* header */}
            <div className="flex items-center gap-3 border-b border-line/[0.1] px-4 py-3">
              <span className="text-[11px] font-semibold uppercase tracking-[0.16em] text-ink-400">Indicators</span>
              <input
                ref={searchRef} value={q} onChange={(e) => setQ(e.target.value)}
                placeholder="search — name, or what it does…"
                className="min-w-0 flex-1 rounded-lg border border-line/[0.12] bg-surface/[0.05] px-3 py-1.5 text-[12.5px] text-ink-100 placeholder:text-ink-500 focus:border-aurora-indigo focus:outline-none focus:ring-2 focus:ring-aurora-indigo/25"
              />
              <span className="shrink-0 text-[11px] tabular-nums text-ink-400">
                {query ? `${matchCount} of ${CATALOGUE.length}` : `${CATALOGUE.length} available`}
              </span>
              <button onClick={onClose} aria-label="Close"
                className="rounded-lg border border-line/[0.12] px-2.5 py-1 text-[11px] font-semibold text-ink-300 hover:bg-white/5">
                Esc
              </button>
            </div>

            <div className="flex min-h-0 flex-1">
              {/* ---------------------------------------------------------- catalogue */}
              <div className="min-h-0 flex-1 overflow-y-auto p-3">
                {matchCount === 0 && (
                  <div className="px-2 py-8 text-center text-[12px] text-ink-400">
                    Nothing matches “{q}”.
                    <div className="mt-1 text-[11px] text-ink-500">
                      Try a category — momentum, volatility, volume — or an abbreviation like “stoch”.
                    </div>
                  </div>
                )}
                {groups.map((g) => (
                  <div key={g.category} className="mb-4">
                    <div className="mb-1.5 px-1 text-[9.5px] font-bold uppercase tracking-[0.16em] text-aurora-cyan">
                      {g.category}
                    </div>
                    <div className="grid grid-cols-1 gap-1 sm:grid-cols-2">
                      {g.items.map((e) => {
                        const n = countOf(e.id);
                        return (
                          <button
                            key={e.id} onClick={() => add(e.id)} title={e.hint}
                            className="group flex flex-col items-start gap-0.5 rounded-lg border border-line/[0.1] bg-surface/[0.03] px-2.5 py-2 text-left transition-colors hover:border-aurora-indigo/50 hover:bg-aurora-indigo/10">
                            <span className="flex w-full items-center gap-2">
                              <span className="flex-1 text-[12.5px] font-semibold text-ink-100">
                                <Highlighted text={e.label} q={query} />
                              </span>
                              {n > 0 && (
                                <span className="rounded-full bg-aurora-cyan/20 px-1.5 text-[10px] font-bold text-aurora-cyan">
                                  {n} on
                                </span>
                              )}
                              <span className="text-[15px] leading-none text-ink-500 group-hover:text-aurora-indigo">+</span>
                            </span>
                            <span className="text-[10.5px] leading-snug text-ink-400">{e.hint}</span>
                          </button>
                        );
                      })}
                    </div>
                  </div>
                ))}
              </div>

              {/* ---------------------------------------------------------- applied */}
              <div className="flex min-h-0 w-[352px] shrink-0 flex-col border-l border-line/[0.1] bg-obsidian-900/50">
                <div className="flex items-center gap-2 border-b border-line/[0.1] px-3 py-2">
                  <span className="text-[9.5px] font-bold uppercase tracking-[0.16em] text-ink-400">On this chart</span>
                  <span className="text-[11px] tabular-nums text-ink-500">{applied.length}</span>
                  {applied.length > 0 && (
                    <button onClick={() => onChange([])}
                      className="ml-auto rounded px-2 py-0.5 text-[11px] text-ink-400 hover:bg-white/5 hover:text-bear">
                      Remove all
                    </button>
                  )}
                </div>

                <div className="min-h-0 flex-1 overflow-y-auto p-2">
                  {applied.length === 0 && (
                    <div className="px-3 py-10 text-center text-[12px] text-ink-400">
                      No indicators yet.
                      <div className="mt-1 text-[11px] text-ink-500">Pick one from the left to put it on the chart.</div>
                    </div>
                  )}

                  {applied.map((a) => {
                    const e = byId(a.id);
                    if (!e) return null;
                    return (
                      <div key={a.uid} className="mb-1.5 rounded-lg border border-line/[0.1] bg-surface/[0.04] p-2">
                        <div className="flex items-center gap-2">
                          <span className="inline-block h-[3px] w-4 shrink-0 rounded"
                                style={{ background: a.hidden ? "rgb(var(--ink-600))" : a.colour }} />
                          <span className={`flex-1 truncate text-[12px] font-semibold ${a.hidden ? "text-ink-500 line-through" : "text-ink-100"}`}>
                            {describeUnique(a, applied)}
                          </span>
                          <span className="shrink-0 rounded px-1 text-[9px] font-bold uppercase tracking-wider text-ink-500">
                            {e.pane === "sub" ? "pane" : "price"}
                          </span>
                          <button onClick={() => patch(a.uid, (x) => ({ ...x, hidden: !x.hidden }))}
                            title={a.hidden ? "Show" : "Hide without removing"}
                            className="shrink-0 rounded px-1.5 py-0.5 text-[10px] font-semibold text-ink-400 hover:bg-white/5 hover:text-ink-100">
                            {a.hidden ? "show" : "hide"}
                          </button>
                          <button onClick={() => remove(a.uid)} title="Remove"
                            className="shrink-0 rounded px-1.5 py-0.5 text-[13px] leading-none text-ink-400 hover:bg-bear/15 hover:text-bear">
                            ×
                          </button>
                        </div>

                        {/* per-instance settings — the whole point of the rebuild */}
                        {(e.params.length > 0 || true) && (
                          <div className="mt-2 flex flex-wrap items-end gap-2 border-t border-line/[0.08] pt-2">
                            {e.params.map((p) => (
                              <label key={p.key} className="flex flex-col gap-0.5">
                                <span className="text-[9.5px] font-semibold uppercase tracking-wider text-ink-400">{p.label}</span>
                                <input
                                  type="text" inputMode="decimal"
                                  value={String(a.params[p.key] ?? p.def)}
                                  onChange={(ev) => {
                                    const raw = ev.target.value.trim();
                                    // Keep an in-progress edit ("1" on the way to "12") usable: only
                                    // commit a parsed number, and clamp on blur rather than per keystroke.
                                    const v = Number(raw);
                                    if (raw === "" || Number.isNaN(v)) return;
                                    patch(a.uid, (x) => ({ ...x, params: { ...x.params, [p.key]: v } }));
                                  }}
                                  onBlur={(ev) => {
                                    const v = Number(ev.target.value);
                                    const clamped = Number.isFinite(v) ? Math.min(p.max, Math.max(p.min, v)) : p.def;
                                    patch(a.uid, (x) => ({ ...x, params: { ...x.params, [p.key]: clamped } }));
                                  }}
                                  className="w-[68px] rounded border border-line/[0.14] bg-obsidian-950/60 px-1.5 py-1 text-[12px] tabular-nums text-ink-100 focus:border-aurora-indigo focus:outline-none"
                                />
                              </label>
                            ))}

                            <div className="flex flex-col gap-0.5">
                              <span className="text-[9.5px] font-semibold uppercase tracking-wider text-ink-400">Colour</span>
                              <div className="flex gap-1">
                                {PALETTE.slice(0, 6).map((c) => (
                                  <button key={c} onClick={() => patch(a.uid, (x) => ({ ...x, colour: c }))}
                                    aria-label={`colour ${c}`}
                                    className={`h-4 w-4 rounded-full border transition-transform ${
                                      a.colour === c ? "scale-110 border-ink-100" : "border-transparent hover:scale-110"}`}
                                    style={{ background: c }} />
                                ))}
                              </div>
                            </div>

                            {e.params.length > 0 && (
                              <button
                                onClick={() => patch(a.uid, (x) => ({
                                  ...x, params: Object.fromEntries(e.params.map((p) => [p.key, p.def])),
                                }))}
                                className="ml-auto rounded px-1.5 py-1 text-[10px] text-ink-500 hover:bg-white/5 hover:text-ink-200">
                                defaults
                              </button>
                            )}
                          </div>
                        )}
                      </div>
                    );
                  })}
                </div>

                <div className="border-t border-line/[0.1] px-3 py-2 text-[10.5px] leading-snug text-ink-500">
                  Sub-pane studies each get their own row under the price. Overlays share the price axis.
                </div>
              </div>
            </div>
          </motion.div>
        </motion.div>
      )}
    </AnimatePresence>,
    document.body,
  );
}
