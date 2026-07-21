"use client";

/**
 * The /order-grid route. The grid itself lives in components/grid/OrderGridBody so the identical
 * component also serves the floating window that can be opened over any other screen — one screen,
 * one set of validation rules, no chance of the two drifting apart.
 */

import { Shell } from "@/components/Shell";
import { OrderGridBody } from "@/components/grid/OrderGridBody";

export default function OrderGridPage() {
  return (
    <Shell title="Order Grid">
      <OrderGridBody />
    </Shell>
  );
}
