"use client";

import { useState } from "react";
import { AnimatePresence, motion } from "framer-motion";
import { useLive } from "@/lib/useLive";
import { nf } from "@/lib/format";

type Toast = { id: number; symbol: string; direction: string; target: number; ltp: number };

/** Global price-alert notifications: pops a toast whenever the backend fires an `alert` SSE event. */
export function AlertToaster() {
  const [toasts, setToasts] = useState<Toast[]>([]);
  useLive((type, data) => {
    if (type !== "alert" || !data) return;
    const t: Toast = { id: Date.now() + Math.random(), symbol: data.symbol, direction: data.direction, target: +data.target, ltp: +data.ltp };
    setToasts((cur) => [...cur, t].slice(-4));
    setTimeout(() => setToasts((cur) => cur.filter((x) => x.id !== t.id)), 8000);
  });

  return (
    <div className="pointer-events-none fixed bottom-4 right-4 z-[60] flex flex-col gap-2">
      <AnimatePresence>
        {toasts.map((t) => (
          <motion.div key={t.id} initial={{ opacity: 0, x: 24 }} animate={{ opacity: 1, x: 0 }} exit={{ opacity: 0, x: 24 }}
            className="glass pointer-events-auto flex items-center gap-3 rounded-xl border border-aurora-cyan/30 px-4 py-3 shadow-glow">
            <span className="text-lg">🔔</span>
            <div>
              <div className="text-sm font-semibold text-ink-100">{t.symbol} price alert</div>
              <div className="text-[11px] text-ink-400">
                crossed {t.direction === "ABOVE" ? "above" : "below"} <span className="tnum text-ink-200">{nf(t.target)}</span> · now <span className={`tnum ${t.direction === "ABOVE" ? "text-bull" : "text-bear"}`}>{nf(t.ltp)}</span>
              </div>
            </div>
          </motion.div>
        ))}
      </AnimatePresence>
    </div>
  );
}
