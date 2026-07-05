export function BrandMark({ size = 30 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 48 48" fill="none" xmlns="http://www.w3.org/2000/svg">
      <defs>
        <linearGradient id="ng" x1="0" y1="0" x2="48" y2="48" gradientUnits="userSpaceOnUse">
          <stop stopColor="#8b5cf6" />
          <stop offset="0.5" stopColor="#6366f1" />
          <stop offset="1" stopColor="#22d3ee" />
        </linearGradient>
      </defs>
      <path d="M24 2 L43 13 V35 L24 46 L5 35 V13 Z" stroke="url(#ng)" strokeWidth="2" fill="rgba(99,102,241,0.08)" />
      {/* rising candlesticks */}
      <rect x="15" y="26" width="3.5" height="9" rx="1" fill="#22c55e" />
      <rect x="22.5" y="20" width="3.5" height="15" rx="1" fill="#22d3ee" />
      <rect x="30" y="14" width="3.5" height="21" rx="1" fill="#8b5cf6" />
      <path d="M14 24 L24 17 L34 11" stroke="#eef2ff" strokeWidth="1.5" strokeLinecap="round" opacity="0.7" />
    </svg>
  );
}

export function Brand({ small = false }: { small?: boolean }) {
  return (
    <div className="flex items-center gap-2.5">
      <BrandMark size={small ? 26 : 32} />
      <div className="leading-tight">
        <div className={`font-bold tracking-tight ${small ? "text-sm" : "text-base"}`}>
          <span className="aurora-text">NAZTECH</span> <span className="text-ink-100">OMS</span>
        </div>
        {!small && (
          <div className="text-[9.5px] uppercase tracking-[0.22em] text-ink-500">
            Exchange-Hosted · DSE / CSE
          </div>
        )}
      </div>
    </div>
  );
}
