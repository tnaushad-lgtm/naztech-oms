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

import { useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { Highlight } from "./Highlight";

export type ComboItem = {
  id: number;
  /** Bold primary label — the ticker, or the client's BO number. */
  primary: string;
  /** Secondary label — the company name, or the client's name. */
  secondary?: string;
  /** Extra text that should match but need not be displayed. */
  extra?: string;
  /**
   * What the CLOSED cell shows, when that must be shorter than what the open list shows.
   *
   * These are different jobs. The list is where a wrong pick happens, so it must disambiguate —
   * 501 of 506 client accounts share a name. The closed cell is already bound to one id, so it can
   * afford to be narrow and leave the rest to the tooltip.
   */
  display?: string;
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
  onPicked,
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
  /** Called after an explicit pick, so a caller can move focus onward. */
  onPicked?: (id: number) => void;
}) {
  const [open, setOpen] = useState(false);
  const [q, setQ] = useState("");
  const [active, setActive] = useState(0);
  /** False until the user types into an open box — see `shown` below. */
  const [typed, setTyped] = useState(false);
  const wrap = useRef<HTMLDivElement>(null);
  const listRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  /**
   * Where to paint the dropdown, in viewport coordinates.
   *
   * The list used to be absolutely positioned inside the cell, which put it inside the grid's
   * overflow-auto scroller — so it was CLIPPED to the visible rows. On the second row of a short
   * panel a trader searching 500 accounts could see one of them. Painting it into document.body
   * escapes every ancestor's overflow; the trade is that the position must be computed and kept in
   * step with scrolling and resizing.
   */
  const [rect, setRect] = useState<{ left: number; top: number; width: number; flip: boolean } | null>(null);

  const measure = () => {
    const el = inputRef.current;
    if (!el) return;
    const r = el.getBoundingClientRect();
    const LIST_H = 264;
    const below = window.innerHeight - r.bottom;
    // Open upward when there is not room below — otherwise the list runs off the bottom of a panel
    // sitting low on the screen and is unreachable.
    const flip = below < LIST_H && r.top > below;
    setRect({ left: r.left, top: flip ? r.top : r.bottom, width: Math.max(r.width, 240), flip });
  };

  useLayoutEffect(() => {
    if (!open) { setRect(null); return; }
    measure();
    const onMove = () => measure();
    window.addEventListener("scroll", onMove, true);   // capture: any ancestor scroll moves the cell
    window.addEventListener("resize", onMove);
    return () => {
      window.removeEventListener("scroll", onMove, true);
      window.removeEventListener("resize", onMove);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  const selected = useMemo(() => items.find((i) => i.id === value) || null, [items, value]);

  const results: Scored[] = useMemo(() => {
    const query = (typed ? q : "").trim().toLowerCase();
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
  }, [items, q, typed, maxResults]);

  // Close on outside click. Committing is explicit (Enter/click), so an outside click abandons.
  useEffect(() => {
    if (!open) return;
    const onDoc = (e: MouseEvent) => {
      const t = e.target as Node;
      const inAnchor = wrap.current?.contains(t);
      const inList = listRef.current?.contains(t);     // the list is portalled, so it is not inside wrap
      if (!inAnchor && !inList) { setOpen(false); setQ(""); setTyped(false); }
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
    if (pick && !pick.disabled) {
      onChange(pick.id);
      // Fired only on a real pick, never on an abandon — auto-advance must follow a decision.
      onPicked?.(pick.id);
    }
    setOpen(false);
    setQ("");
    setTyped(false);
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
      /*
       * Open with NO match: swallow it. Falling through handed the grid an Enter, which committed
       * the row and opened a new one — so typing a client name that does not exist silently
       * appended a row, moved focus into it, and left the unmatched text stranded above looking
       * exactly like a committed value. Enter on nothing must do nothing.
       */
      if (open) { e.preventDefault(); e.stopPropagation(); return; }
      // closed: fall through so the grid's Enter-adds-a-row handler still works
    }
    if (e.key === "Escape") { e.preventDefault(); setOpen(false); setQ(""); setTyped(false); return; }
    // TAB DELIBERATELY DOES NOT PICK. Tab is the universal move-on key; binding it to
    // accept-the-highlighted-match hands a hurried trader a wrong BO account that they never chose
    // and that renders afterwards exactly like a deliberate one. Acceptance is always an explicit
    // Enter or click; tabbing out of an open list leaves the cell unset and the row un-sendable.
    if (e.key === "Tab") { setOpen(false); setQ(""); setTyped(false); }
    inputProps.onKeyDown?.(e);
  };

  /** What the closed cell reads: the short display label when one is given. */
  const settled = selected
    ? selected.display ?? selected.primary + (selected.secondary ? ` · ${selected.secondary}` : "")
    : "";

  /**
   * An OPEN box shows what you are typing — but only once you have typed.
   *
   * It used to show `q` unconditionally, so merely focusing a cell that already held a value blanked
   * it on screen: auto-advancing into a filled ticker made "GP" disappear while securityId was still
   * set, and the row read as empty when it was in fact ready to send. A field that erases itself
   * when you look at it is worse than one that is hard to edit.
   *
   * Until the first keystroke the box keeps showing its value (selected, so typing still replaces it
   * wholesale). `typed` resets on every open, commit and abandon.
   */
  const shown = open ? (typed ? q : settled) : settled;

  return (
    <div ref={wrap} className={`relative ${className}`}>
      <input
        ref={inputRef}
        {...inputProps}
        disabled={disabled}
        value={shown}
        placeholder={placeholder}
        autoComplete="off"
        spellCheck={false}
        onFocus={(e) => {
          setOpen(true); setActive(0); setTyped(false);
          /*
           * Select SYNCHRONOUSLY. A deferred select() (rAF/setTimeout) lands after the first
           * keystroke of a fast typist and selects it, so the second character REPLACES the first:
           * typing "GP" left "P", the list rebuilt from "P", and Enter committed PADMALIFE. Typing a
           * ticker could buy a different company.
           *
           * No defer is needed anyway — while closed the box already displays the settled text, so
           * it is in the DOM right now and there is nothing to wait for.
           */
          e.currentTarget.select();
        }}
        // Clicking a cell that already has focus must reopen the list. onFocus does not fire when the
        // input is already focused, so after committing a value in place the box became unopenable
        // by clicking it — the only way back was to focus something else first.
        onMouseDown={() => { if (!open) { setOpen(true); setActive(0); setTyped(false); } }}
        onChange={(e) => { setQ(e.target.value); setTyped(true); setOpen(true); setActive(0); }}
        /*
         * Close when focus leaves. Only an outside MOUSEDOWN closed it before, so moving on by
         * keyboard left the list painted over unrelated rows — row 1's client list still hanging
         * beside rows 2 and 3, long after its value was committed. Clicking an option does not
         * fire this: the option's onMouseDown preventDefaults, so focus never leaves the input.
         */
        onBlur={() => { setOpen(false); setQ(""); setTyped(false); }}
        onKeyDown={onKeyDown}
        className="w-full rounded border border-line/70 bg-white/[0.05] px-2 py-0.5 text-[12px] text-ink-100
          hover:border-aurora-cyan/40 focus:border-aurora-cyan focus:bg-white/[0.09] focus:outline-none
          disabled:border-transparent disabled:bg-transparent disabled:text-ink-500"
      />

      {open && rect && createPortal(
        <div
          ref={listRef}
          style={{
            position: "fixed",
            left: Math.min(rect.left, Math.max(8, window.innerWidth - 340)),
            top: rect.flip ? undefined : rect.top + 2,
            bottom: rect.flip ? window.innerHeight - rect.top + 2 : undefined,
            width: 320,
          }}
          className="z-[100] max-h-64 overflow-auto rounded-lg border border-line bg-obsidian-850 shadow-2xl"
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
        </div>,
        document.body,
      )}
    </div>
  );
}
