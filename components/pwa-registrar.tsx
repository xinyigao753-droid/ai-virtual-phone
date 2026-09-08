"use client";

import { useEffect } from "react";

export function PWARegistrar() {
  useEffect(() => {
    if (navigator.userAgent.includes("FloatShell/")) {
      let synced = false;
      const syncShellPush = async () => {
        if (synced) return;
        const { ensureShellPushSubscription } = await import("@/lib/push-client");
        synced = (await ensureShellPushSubscription()).ok;
      };
      void syncShellPush();
      const timer = window.setInterval(() => void syncShellPush(), 15_000);
      return () => window.clearInterval(timer);
    }
    if (process.env.NODE_ENV !== "production") return;
    if (!("serviceWorker" in navigator)) return;

    let cancelled = false;
    const register = () => {
      if (cancelled) return;
      navigator.serviceWorker.register("/sw.js", { scope: "/" }).catch((error) => {
        console.warn("[PWA] Service worker registration failed:", error);
      });
    };

    if (document.readyState === "complete") {
      register();
      return () => {
        cancelled = true;
      };
    }

    window.addEventListener("load", register, { once: true });
    return () => {
      cancelled = true;
      window.removeEventListener("load", register);
    };
  }, []);

  return null;
}
