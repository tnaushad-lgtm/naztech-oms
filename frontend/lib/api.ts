"use client";

// In the browser the backend (:8090) is reached SAME-ORIGIN via a relative path — Next.js
// rewrites /api/* to the backend (see next.config.js). That means the whole app works behind
// one origin: localhost, a LAN IP (http://10.33.56.3:3060), or a single Cloudflare/ngrok tunnel,
// with no need to expose port 8090. The optional Python feed (:8091) is still reached directly on
// the same host (LAN/localhost only). An explicit NEXT_PUBLIC_OMS_API / _FEED env var overrides.
function base(port: number, env?: string): string {
  if (env) return env;
  if (typeof window !== "undefined") {
    if (port === 8090) return "";                                                     // backend: same-origin (proxied)
    return `${window.location.protocol}//${window.location.hostname}:${port}`;        // feed etc.: direct
  }
  return `http://localhost:${port}`;
}

export const API = base(8090, process.env.NEXT_PUBLIC_OMS_API);
export const FEED = base(8091, process.env.NEXT_PUBLIC_OMS_FEED);

// SSE (EventSource) must connect DIRECTLY to the backend on :8090. The Next.js rewrite proxy that
// serves same-origin REST buffers event-streams, which stalls live order/trade updates in the blotter.
// So the live stream bypasses the proxy and hits the backend host directly (LAN/localhost). An explicit
// NEXT_PUBLIC_OMS_API override still wins.
export const STREAM =
  process.env.NEXT_PUBLIC_OMS_API
    ? process.env.NEXT_PUBLIC_OMS_API
    : typeof window !== "undefined"
      ? `${window.location.protocol}//${window.location.hostname}:8090`
      : "http://localhost:8090";

function actor(): string {
  if (typeof window === "undefined") return "system";
  try {
    const s = JSON.parse(localStorage.getItem("oms_session") || "{}");
    return s.username || "system";
  } catch {
    return "system";
  }
}

export async function api<T = any>(path: string, opts: RequestInit = {}): Promise<T> {
  const res = await fetch(`${API}${path}`, {
    ...opts,
    headers: {
      "Content-Type": "application/json",
      "X-Actor": actor(),
      ...(opts.headers || {}),
    },
  });
  const text = await res.text();
  let data: any = null;
  if (text) { try { data = JSON.parse(text); } catch { data = null; } }  // tolerate non-JSON (e.g. HTML 5xx/gateway pages)
  if (!res.ok) throw new Error((data && data.error) || `HTTP ${res.status}`);
  return data as T;
}

export const get = <T = any>(p: string) => api<T>(p);
export const post = <T = any>(p: string, body: any) =>
  api<T>(p, { method: "POST", body: JSON.stringify(body) });
export const put = <T = any>(p: string, body: any) =>
  api<T>(p, { method: "PUT", body: JSON.stringify(body) });
export const del = <T = any>(p: string) => api<T>(p, { method: "DELETE" });

export async function feedForecast(symbol: string, exchange = "DSE", horizon = 5) {
  try {
    const res = await fetch(`${FEED}/ai/forecast/${symbol}?exchange=${exchange}&horizon=${horizon}`);
    if (!res.ok) return null;
    return await res.json();
  } catch {
    return null; // python service optional
  }
}
