"use client";

export type Session = {
  token: string;
  username: string;
  displayName: string;
  role: string;
  brokerId: number | null;
  brokerName: string | null;
  defaultAccountId: number | null;
};

const KEY = "oms_session";

export function saveSession(s: Session) {
  localStorage.setItem(KEY, JSON.stringify(s));
}
export function getSession(): Session | null {
  if (typeof window === "undefined") return null;
  try {
    const raw = localStorage.getItem(KEY);
    return raw ? (JSON.parse(raw) as Session) : null;
  } catch {
    return null;
  }
}
export function clearSession() {
  localStorage.removeItem(KEY);
}
