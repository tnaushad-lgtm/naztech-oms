"use client";

import { getSession } from "./session";

export type Watchlist = { id: string; name: string; symbols: string[] };

const key = () => `oms_watchlists_${getSession()?.username || "guest"}`;

export function getLists(): Watchlist[] {
  if (typeof window === "undefined") return [];
  try { return JSON.parse(localStorage.getItem(key()) || "[]"); } catch { return []; }
}
export function saveLists(lists: Watchlist[]) {
  localStorage.setItem(key(), JSON.stringify(lists));
}
export function createList(name: string): Watchlist {
  const lists = getLists();
  const wl: Watchlist = { id: "wl_" + Date.now(), name, symbols: [] };
  saveLists([...lists, wl]);
  return wl;
}
export function deleteList(id: string) {
  saveLists(getLists().filter((l) => l.id !== id));
}
export function toggleSymbol(id: string, symbol: string) {
  const lists = getLists();
  const wl = lists.find((l) => l.id === id);
  if (!wl) return;
  wl.symbols = wl.symbols.includes(symbol) ? wl.symbols.filter((s) => s !== symbol) : [...wl.symbols, symbol];
  saveLists(lists);
}
