"use client";

export function WidgetChrome({
  title, subtitle, collapsed, onToggleCollapse, onMaximize, onClose, children, maximized,
}: {
  title: string; subtitle?: string; collapsed?: boolean;
  onToggleCollapse?: () => void; onMaximize?: () => void; onClose?: () => void;
  children: React.ReactNode; maximized?: boolean;
}) {
  const btn = "grid h-6 w-6 place-items-center rounded-md text-ink-500 hover:bg-surface/[0.14] hover:text-ink-100 transition-colors";
  const stop = (e: React.MouseEvent) => { e.stopPropagation(); };

  return (
    <div className="glass flex h-full flex-col overflow-hidden">
      <div className="drag-handle flex cursor-grab items-center gap-2 border-b border-line/[0.1] px-3 py-2 active:cursor-grabbing">
        <span className="h-1.5 w-1.5 rounded-full bg-aurora-cyan/70" />
        <div className="min-w-0">
          <div className="truncate text-[12px] font-semibold text-ink-200">{title}</div>
          {subtitle && <div className="truncate text-[10px] text-ink-600">{subtitle}</div>}
        </div>
        <div className="ml-auto flex items-center gap-0.5" onMouseDown={stop}>
          {onToggleCollapse && (
            <button className={btn} onClick={onToggleCollapse} title={collapsed ? "Expand" : "Minimize"}>
              {collapsed
                ? <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><path d="M5 12h14M12 5v14" strokeLinecap="round" /></svg>
                : <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><path d="M5 12h14" strokeLinecap="round" /></svg>}
            </button>
          )}
          {onMaximize && (
            <button className={btn} onClick={onMaximize} title={maximized ? "Restore" : "Maximize"}>
              <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                <path d="M4 9V4h5M20 15v5h-5M15 4h5v5M9 20H4v-5" strokeLinecap="round" strokeLinejoin="round" />
              </svg>
            </button>
          )}
          {onClose && (
            <button className={btn + " hover:bg-bear/15 hover:text-bear"} onClick={onClose} title="Hide">
              <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><path d="M6 6l12 12M18 6L6 18" strokeLinecap="round" /></svg>
            </button>
          )}
        </div>
      </div>
      {!collapsed && <div className="min-h-0 flex-1 overflow-auto p-3">{children}</div>}
    </div>
  );
}
