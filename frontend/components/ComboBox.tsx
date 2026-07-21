"use client";

/**
 * A searchable combobox for large option sets — built because a native <select> of 506 client
 * accounts is not a picker, it is a wall. Typing "imran" in a native select jumps to the first option
 * beginning with "i" and stops; there are 25 Imrans in the book and none of them are findable that way.
 *
 * Matching is substring over EVERY searchable field (BO number and client name, or ticker and company
 * name), because a trader holds an instruction in whichever form the client gave it. Ranking puts
 * exact and prefix matches above mid-string ones, so typing "GP" reaches Grameenphone rather than
 * every company with a "gp" buried in it.
 *
 * Deliberately dependency-free and keyboard-complete: ↑/↓ to move, Enter to commit, Esc to abandon.
 * Tab moves on WITHOUT committing — see the note on the Tab handler below.
 */

import { useEffect, useMemo, useRef, useState } from "react";
import { Highlight } from "./Highlight";

export type ComboItem = {
  id: number;
  /** Bold primary label — the ticker, or the client's BO number. */
  primary: string;
  /** Secondary label — the company name, or the client's name. */
  secondary?: string;
  /** Extra text that should match but need not be displayed. */
  extra?: string;
  disabled?: boolean;
};

type Scored = { item: ComboItem; score: number; hits: string };

/**
 * Rank a candidate against the query. Lower score is better; -1 means no match.
 * The tiers exist so "imran" surfaces "Imran Ahmed" ahead of "Md. Imranul Haque", and "GP" surfaces
 * the GP ticker ahead of any company merely containing those letters.
 */
function rank(item: ComboItem, q: string): number {
  if (!q) return 50;
  const p = item.primary.toLowerCase();
  const s = (item.secondary || "").toLowerCase();
  const e = (item.extra || "").toLowerCase();

  if (p === q) return 0;                                  // exact ticker / exact BO
  if (p.startsWith(q)) return 1;                          // ticker prefix
  if (s.startsWith(q)) return 2;                          // name starts with it — "imran" -> "Imran Ahmed"
  // any word in the name starting with the query: "ahmed" -> "Imran Ahmed"
  if (s.split(/\s+/).some((w) => w.startsWith(q))) return 3;
  if (p.includes(q)) return 4;                            // mid-ticker / mid-BO (BO numbers are long)
  if (s.includes(q)) return 5;                            // mid-name
  if (e.includes(q)) return 6;                            // sector / asset class / anything else
  return -1;
}

export function ComboBox({
  items,
  value,
  onChange,
  placeholder,
  disabled,
  className = "",
  inputProps = {},
  emptyLabel = "no match",
  maxResults = 60,
}: {
  items: ComboItem[];
  value: number | null;
  onChange: (id: number | null) => void;
  placeholder?: string;
  disabled?: boolean;
  className?: string;
  /** Passed to the <input> — used here to carry data-row / data-col for grid keyboard navigation. */
  inputProps?: React.InputHTMLAttributes<HTMLInputElement>;
  emptyLabel?: string;
  maxResults?: number;
}) {
  const [open, setOpen] = useState(false);
  const [q, setQ] = useState("");
  const [active, setActive] = useState(0);
  const wrap = useRef<HTMLDivElement>(null);
  const listRef = useRef<HTMLDivElement>(null);

  const selected = useMemo(() => items.find((i) => i.id === value) || null, [items, value]);

  const results: Scored[] = useMemo(() => {
    const query = q.trim().toLowerCase();
    const out: Scored[] = [];
    for (const item of items) {
      const score = rank(item, query);
      if (score < 0) continue;
      out.push({ item, score, hits: query });
      // Bail out early on a blank query: showing all 506 is pointless and janky.
      if (!query && out.length >= maxResults) break;
    }
    out.sort((a, b) => a.score - b.score || a.item.primary.localeCompare(b.item.primary));
    return out.slice(0, maxResults);
  }, [items, q, maxResults]);

  // Close on outside click. Committing is explicit (Enter/click), so an outside click abandons.
  useEffect(() => {
    if (!open) return;
    const onDoc = (e: MouseEvent) => {
      if (wrap.current && !wrap.current.contains(e.target as Node)) { setOpen(false); setQ(""); }
    };
    document.addEventListener("mousedown", onDoc);
    return () => document.removeEventListener("mousedown", onDoc);
  }, [open]);

  // Keep the active option in view while arrowing.
  useEffect(() => {
    if (!open || !listRef.current) return;
    const el = listRef.current.querySelector<HTMLElement>(`[data-idx="${active}"]`);
    el?.scrollIntoView({ block: "nearest" });
  }, [active, open]);

  const commit = (item?: ComboItem) => {
    const pick = item ?? results[active]?.item;
    if (pick && !pick.disabled) onChange(pick.id);
    setOpen(false);
    setQ("");
  };

  const onKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === "ArrowDown") {
      e.preventDefault();
      if (!open) { setOpen(true); setActive(0); return; }
      setActive((a) => Math.min(a + 1, results.length - 1));
      return;
    }
    if (e.key === "ArrowUp") {
      e.preventDefault();
      setActive((a) => Math.max(a - 1, 0));
      return;
    }
    if (e.key === "Enter") {
      if (open && results.length) { e.preventDefault(); e.stopPropagation(); commit(); return; }
      // closed: fall through so the grid's Enter-adds-a-row handler still works
    }
    if (e.key === "Escape") { e.preventDefault(); setOpen(false); setQ(""); return; }
    // TAB DELIBERATELY DOES NOT PICK. Tab is the universal move-on key; binding it to
    // accept-the-highlighted-match hands a hurried trader a wrong BO account that they never chose
    // and that renders afterwards exactly like a deliberate one. Acceptance is always an explicit
    // Enter or click; tabbing out of an open list leaves the cell unset and the row un-sendable.
    if (e.key === "Tab") { setOpen(false); setQ(""); }
    inputProps.onKeyDown?.(e);
  };

  // Closed state shows the chosen value; focused state shows what you are typing.
  const shown = open ? q : selected ? selected.primary + (selected.secondary ? ` · ${selected.secondary}` : "") : "";

  return (
    <div ref={wrap} className={`relative ${className}`}>
      <input
        {...inputProps}
        disabled={disabled}
        value={shown}
        placeholder={placeholder}
        autoComplete="off"
        spellCheck={false}
        onFocus={(e) => { setOpen(true); setActive(0); e.currentTarget.select(); }}
        onChange={(e) => { setQ(e.target.value); setOpen(true); setActive(0); }}
        onKeyDown={onKeyDown}
        className="w-full rounded bg-transparent px-2 py-1 text-[12px] text-ink-100 outline-none focus:bg-white/[0.06] disabled:text-ink-600"
      />

      {open && (
        <div
          ref={listRef}
          className="absolute left-0 z-50 mt-1 max-h-64 w-[320px] overflow-auto rounded-lg border border-line bg-obsidian-850 shadow-2xl"
        >
          {results.length === 0 ? (
            <div className="px-3 py-2 text-[11px] text-ink-300">{emptyLabel}</div>
          ) : (
            results.map((r, i) => (
              <button
                key={r.item.id}
                data-idx={i}
                type="button"
                disabled={r.item.disabled}
                onMouseEnter={() => setActive(i)}
                onMouseDown={(e) => e.preventDefault()}   // keep focus in the input so blur does not fire first
                onClick={() => commit(r.item)}
                className={`flex w-full items-baseline gap-2 px-3 py-1.5 text-left text-[12px] ${
                  i === active ? "bg-aurora-indigo/25 text-ink-100" : "text-ink-200 hover:bg-white/5"
                } disabled:opacity-40`}
              >
                <span className="font-semibold tnum"><Highlight text={r.item.primary} q={r.hits} /></span>
                {r.item.secondary && (
                  <span className="truncate text-[11.5px] text-ink-300"><Highlight text={r.item.secondary} q={r.hits} /></span>
                )}
              </button>
            ))
          )}
          {results.length >= maxResults && (
            <div className="border-t border-line px-3 py-1 text-[10px] text-ink-400">
              showing first {maxResults} — keep typing to narrow
            </div>
          )}
        </div>
      )}
    </div>
  );
}
