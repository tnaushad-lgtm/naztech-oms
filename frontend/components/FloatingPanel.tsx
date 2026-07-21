"use client";

/**
 * A draggable, resizable floating window that can be opened over any screen.
 *
 * Built for the order grid: a trader watching depth or the blotter should be able to pull entry over
 * the top without navigating away and losing what they were looking at. Deliberately a *floating*
 * window rather than a modal — a modal would block the very screen the trader is reading prices from,
 * which defeats the point of putting entry there at all.
 *
 * Position and size persist per `id`, so a trader arranges it once. Maximise is a toggle that
 * remembers the restore geometry, and the whole thing is clamped back into view on mount: a window
 * remembered at x=1800 on a second monitor must not be invisible on the laptop next morning.
 */

import { useCallback, useEffect, useRef, useState } from "react";

type Box = { x: number; y: number; w: number; h: number };

const clampToView = (b: Box): Box => {
  if (typeof window === "undefined") return b;
  const vw = window.innerWidth, vh = window.innerHeight;
  const w = Math.min(b.w, vw - 40);
  const h = Math.min(b.h, vh - 40);
  return {
    w, h,
    // keep at least a strip of the title bar reachable, never fully off-screen
    x: Math.min(Math.max(b.x, -w + 160), vw - 160),
    y: Math.min(Math.max(b.y, 0), vh - 48),
  };
};

export function FloatingPanel({
  id,
  title,
  subtitle,
  open,
  onClose,
  initial = { x: 120, y: 90, w: 1320, h: 560 },
  minW = 640,
  minH = 260,
  children,
}: {
  id: string;
  title: string;
  subtitle?: React.ReactNode;
  open: boolean;
  onClose: () => void;
  initial?: Box;
  minW?: number;
  minH?: number;
  children: React.ReactNode;
}) {
  const KEY = `oms_float_${id}`;
  const [box, setBox] = useState<Box>(initial);
  const [max, setMax] = useState(false);
  const restore = useRef<Box | null>(null);
  const drag = useRef<{ dx: number; dy: number } | null>(null);
  const resize = useRef<{ x: number; y: number; w: number; h: number } | null>(null);

  useEffect(() => {
    try {
      const raw = localStorage.getItem(KEY);
      if (raw) setBox(clampToView(JSON.parse(raw)));
      else setBox(clampToView(initial));
    } catch { setBox(clampToView(initial)); }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const persist = useCallback((b: Box) => {
    try { localStorage.setItem(KEY, JSON.stringify(b)); } catch {}
  }, [KEY]);

  // Pointer events (not mouse) so a pen or touch drags too. Capture on the window, because a fast
  // drag outruns the header and would otherwise drop the window mid-move.
  useEffect(() => {
    if (!open) return;
    const move = (e: PointerEvent) => {
      if (drag.current) {
        setBox((b) => {
          const nb = clampToView({ ...b, x: e.clientX - drag.current!.dx, y: e.clientY - drag.current!.dy });
          return nb;
        });
      } else if (resize.current) {
        const r = resize.current;
        setBox((b) => ({
          ...b,
          w: Math.max(minW, r.w + (e.clientX - r.x)),
          h: Math.max(minH, r.h + (e.clientY - r.y)),
        }));
      }
    };
    const up = () => {
      if (drag.current || resize.current) setBox((b) => { persist(b); return b; });
      drag.current = null; resize.current = null;
      document.body.style.userSelect = "";
    };
    window.addEventListener("pointermove", move);
    window.addEventListener("pointerup", up);
    return () => { window.removeEventListener("pointermove", move); window.removeEventListener("pointerup", up); };
  }, [open, minW, minH, persist]);

  // Esc closes — but only when nothing inside is being edited, otherwise a trader loses a half-typed
  // batch by reflexively dismissing a combobox.
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== "Escape") return;
      const el = document.activeElement as HTMLElement | null;
      if (el && (el.tagName === "INPUT" || el.tagName === "SELECT" || el.isContentEditable)) return;
      onClose();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open, onClose]);

  if (!open) return null;

  const geom = max
    ? { left: 8, top: 8, width: "calc(100vw - 16px)", height: "calc(100vh - 16px)" }
    : { left: box.x, top: box.y, width: box.w, height: box.h };

  const toggleMax = () => {
    if (max) { if (restore.current) setBox(restore.current); setMax(false); }
    else { restore.current = box; setMax(true); }
  };

  return (
    <div
      role="dialog"
      aria-label={title}
      style={geom}
      className="fixed z-[60] flex flex-col overflow-hidden rounded-xl border border-aurora-cyan/30 bg-obsidian-900/95 shadow-[0_24px_80px_rgba(0,0,0,0.6)] backdrop-blur-xl"
    >
      {/* title bar — the drag handle */}
      <div
        onPointerDown={(e) => {
          if ((e.target as HTMLElement).closest("button")) return;   // buttons are not a drag handle
          drag.current = { dx: e.clientX - box.x, dy: e.clientY - box.y };
          document.body.style.userSelect = "none";
        }}
        onDoubleClick={toggleMax}
        className={`flex shrink-0 items-center gap-3 border-b border-line bg-obsidian-850/90 px-3 py-2 ${max ? "" : "cursor-move"}`}
      >
        <span className="flex gap-1.5">
          <span className="h-2.5 w-2.5 rounded-full bg-aurora-cyan/70" />
          <span className="h-2.5 w-2.5 rounded-full bg-aurora-violet/60" />
        </span>
        <span className="text-[12px] font-semibold uppercase tracking-wider text-ink-100">{title}</span>
        {subtitle && <span className="truncate text-[11px] text-ink-400">{subtitle}</span>}

        <span className="ml-auto flex items-center gap-1">
          <button onClick={toggleMax} title={max ? "Restore" : "Maximise"}
            className="rounded px-2 py-0.5 text-[12px] text-ink-300 hover:bg-white/10">{max ? "❐" : "▢"}</button>
          <button onClick={onClose} title="Close (Esc)"
            className="rounded px-2 py-0.5 text-[12px] text-ink-300 hover:bg-bear/20 hover:text-bear">✕</button>
        </span>
      </div>

      <div className="min-h-0 flex-1 overflow-hidden">{children}</div>

      {!max && (
        <div
          onPointerDown={(e) => {
            resize.current = { x: e.clientX, y: e.clientY, w: box.w, h: box.h };
            document.body.style.userSelect = "none";
          }}
          title="Drag to resize"
          className="absolute bottom-0 right-0 h-4 w-4 cursor-nwse-resize"
          style={{
            background:
              "linear-gradient(135deg, transparent 0 50%, rgb(var(--ink-500) / 0.7) 50% 60%, transparent 60% 70%, rgb(var(--ink-500) / 0.7) 70% 80%, transparent 80%)",
          }}
        />
      )}
    </div>
  );
}
