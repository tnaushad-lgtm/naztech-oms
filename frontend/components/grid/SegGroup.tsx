"use client";

/**
 * The one segmented-control primitive behind Side, Type, Window, Validity and Basis.
 *
 * It exists to delete dropdowns. Opening a <select>, choosing, and tabbing out costs three
 * interactions for a field with two possible values; a segmented control costs one, and — more
 * importantly on a screen holding twenty orders — the chosen value stays readable without opening
 * anything.
 *
 * Two deliberate refusals, both learned from stress-testing this design against a working dealer:
 *
 *  1. NO LETTER KEY EVER SELECTS A VALUE. "s" would mean SELL in one group, STOP in the next and
 *     SPOT in the one after that — a keystroke whose meaning depends on which cell has focus, on a
 *     screen where the wrong value is money. Selection is by arrow keys or by the ordinal digits
 *     1..n, which are unambiguous and are printed under each segment while the group holds focus.
 *  2. SPACE IS INERT. Space is what everyone presses to scroll a long list. Binding it to "toggle
 *     side" would turn a reflex into a mis-sent order.
 *
 * Focus lands on the GROUP, not on individual segments (roving tabindex), so a group is one tab stop
 * and the ring makes it obvious which field your fingers are in.
 */

import { useRef } from "react";

export type Seg = {
  value: string;
  /** The full word. Never a 3-letter stub: "stp" (STOP) vs "spt" (SPOT) is a transposed-letter
   *  confusable pair across two adjacent, legally distinct fields. */
  label: string;
  /** Tailwind colour token used when this value is selected AND is not the default. */
  hue?: "cyan" | "teal" | "violet" | "indigo" | "bull" | "bear";
};

const HUE: Record<string, string> = {
  cyan: "bg-aurora-cyan/[0.16] text-aurora-cyan ring-aurora-cyan/45",
  teal: "bg-aurora-teal/[0.16] text-aurora-teal ring-aurora-teal/45",
  violet: "bg-aurora-violet/[0.16] text-aurora-violet ring-aurora-violet/45",
  indigo: "bg-aurora-indigo/[0.16] text-aurora-indigo ring-aurora-indigo/45",
  bull: "bg-bull/[0.18] text-bull ring-bull/50",
  bear: "bg-bear/[0.18] text-bear ring-bear/50",
};

export function SegGroup({
  segs,
  value,
  onChange,
  label,
  /** The value that costs the trader no attention. Selected-default renders neutral, not hued. */
  defaultValue,
  /** Unconfirmed: the value was assumed, not chosen. Renders dashed amber and blocks Send. */
  unconfirmed = false,
  size = "sm",
  disabled = false,
  showDigits = false,
  className = "",
  ...nav
}: {
  segs: Seg[];
  value: string;
  onChange: (v: string) => void;
  label: string;
  defaultValue?: string;
  unconfirmed?: boolean;
  size?: "sm" | "md";
  disabled?: boolean;
  showDigits?: boolean;
  className?: string;
} & React.HTMLAttributes<HTMLDivElement>) {
  const ref = useRef<HTMLDivElement>(null);

  const onKeyDown = (e: React.KeyboardEvent<HTMLDivElement>) => {
    if (disabled) return;
    const i = segs.findIndex((s) => s.value === value);

    if (e.key === "ArrowRight" || e.key === "ArrowLeft") {
      e.preventDefault();
      const next = e.key === "ArrowRight" ? Math.min(i + 1, segs.length - 1) : Math.max(i - 1, 0);
      onChange(segs[next].value);
      return;
    }
    // Ordinal digits. Scoped to the focused group, printed under the segments — never letters.
    if (/^[1-9]$/.test(e.key)) {
      const n = parseInt(e.key, 10) - 1;
      if (n < segs.length) { e.preventDefault(); onChange(segs[n].value); }
      return;
    }
    // Space deliberately falls through to the browser so the list still page-scrolls.
    (nav as any).onKeyDown?.(e);
  };

  const base =
    size === "md"
      ? "h-6 px-2.5 text-[12px] leading-6 font-bold uppercase tracking-[0.06em] rounded-[4px]"
      : "h-5 px-2 text-[11px] leading-5 font-semibold uppercase tracking-[0.04em] rounded-[4px]";

  return (
    <div
      {...nav}
      ref={ref}
      role="radiogroup"
      aria-label={label}
      tabIndex={disabled ? -1 : 0}
      onKeyDown={onKeyDown}
      className={`group/seg relative inline-flex items-center gap-px rounded-[5px] p-px outline-none
        focus:ring-2 focus:ring-aurora-cyan focus:ring-offset-1 focus:ring-offset-obsidian-900
        ${unconfirmed ? "rounded-[5px] ring-1 ring-dashed ring-amber-400/70" : ""}
        ${disabled ? "opacity-40" : ""} ${className}`}
    >
      {segs.map((s, i) => {
        const sel = s.value === value;
        const isDefault = s.value === defaultValue;
        const cls = sel
          ? unconfirmed
            ? "bg-amber-400/10 text-amber-300 ring-1 ring-inset ring-amber-400/50"
            : isDefault || !s.hue
              ? "bg-white/[0.10] text-ink-100 ring-1 ring-inset ring-white/20"
              : `${HUE[s.hue]} ring-1 ring-inset`
          : "text-ink-400 font-medium bg-transparent hover:text-ink-100 hover:bg-white/[0.08]";

        return (
          <button
            key={s.value}
            type="button"
            role="radio"
            aria-checked={sel}
            tabIndex={-1}
            disabled={disabled}
            onClick={() => onChange(s.value)}
            className={`${base} ${cls} relative select-none transition-colors duration-75`}
          >
            {/* SELL carries a 45° hatch as well as hue: colour is never the only channel. */}
            {sel && s.hue === "bear" && (
              <span
                aria-hidden
                className="pointer-events-none absolute inset-0 rounded-[4px]"
                style={{
                  backgroundImage:
                    "repeating-linear-gradient(45deg, transparent 0 3px, rgb(var(--bear) / 0.16) 3px 6px)",
                }}
              />
            )}
            <span className="relative">{s.label}</span>
            {showDigits && (
              <span className="absolute -bottom-3 left-1/2 hidden -translate-x-1/2 text-[8px] text-aurora-cyan group-focus/seg:block">
                {i + 1}
              </span>
            )}
          </button>
        );
      })}
    </div>
  );
}
