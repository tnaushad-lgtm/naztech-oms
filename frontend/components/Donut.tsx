"use client";

import { nf, compact } from "@/lib/format";

export type Slice = { label: string; value: number; pct: number };

const PALETTE = ["#8b5cf6", "#22d3ee", "#2dd4bf", "#6366f1", "#f59e0b", "#fb5b6b", "#a3e635", "#e879f9", "#38bdf8", "#94a3b8"];

export function Donut({ title, slices }: { title: string; slices: Slice[] }) {
  const total = slices.reduce((a, s) => a + s.value, 0) || 1;
  const R = 52, C = 2 * Math.PI * R;
  let offset = 0;

  return (
    <div className="glass p-4">
      <div className="panel-title mb-3">{title}</div>
      <div className="flex items-center gap-4">
        <svg viewBox="0 0 140 140" className="h-36 w-36 shrink-0 -rotate-90">
          <circle cx="70" cy="70" r={R} fill="none" stroke="rgba(255,255,255,0.05)" strokeWidth="16" />
          {slices.map((s, i) => {
            const len = (s.value / total) * C;
            const dash = `${len} ${C - len}`;
            const el = (
              <circle key={i} cx="70" cy="70" r={R} fill="none" stroke={PALETTE[i % PALETTE.length]}
                strokeWidth="16" strokeDasharray={dash} strokeDashoffset={-offset}
                style={{ transition: "stroke-dasharray 0.5s ease" }} />
            );
            offset += len;
            return el;
          })}
        </svg>
        <div className="min-w-0 flex-1 space-y-1.5">
          {slices.slice(0, 6).map((s, i) => (
            <div key={i} className="flex items-center gap-2 text-[12px]">
              <span className="h-2.5 w-2.5 shrink-0 rounded-sm" style={{ background: PALETTE[i % PALETTE.length] }} />
              <span className="min-w-0 flex-1 truncate text-ink-300">{s.label}</span>
              <span className="tnum text-ink-500">{compact(s.value)}</span>
              <span className="tnum w-12 text-right font-semibold text-ink-200">{nf(s.pct, 1)}%</span>
            </div>
          ))}
          {slices.length === 0 && <div className="text-[12px] text-ink-600">No holdings.</div>}
        </div>
      </div>
    </div>
  );
}
