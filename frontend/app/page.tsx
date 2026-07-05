"use client";
import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { getSession } from "@/lib/session";

export default function Home() {
  const router = useRouter();
  useEffect(() => {
    router.replace(getSession() ? "/dashboard" : "/login");
  }, [router]);
  return (
    <div className="flex h-screen items-center justify-center text-ink-400">
      <div className="animate-pulseDot">Loading Naztech OMS…</div>
    </div>
  );
}
