import type { Config } from "tailwindcss";

const config: Config = {
  content: ["./app/**/*.{ts,tsx}", "./components/**/*.{ts,tsx}"],
  theme: {
    extend: {
      colors: {
        // All colours are CSS-variable driven so the active theme can re-skin
        // every existing utility class without touching component code.
        obsidian: {
          950: "rgb(var(--obsidian-950) / <alpha-value>)",
          900: "rgb(var(--obsidian-900) / <alpha-value>)",
          850: "rgb(var(--obsidian-850) / <alpha-value>)",
          800: "rgb(var(--obsidian-800) / <alpha-value>)",
          700: "rgb(var(--obsidian-700) / <alpha-value>)",
          600: "rgb(var(--obsidian-600) / <alpha-value>)",
        },
        aurora: {
          violet: "rgb(var(--aurora-violet) / <alpha-value>)",
          indigo: "rgb(var(--aurora-indigo) / <alpha-value>)",
          cyan: "rgb(var(--aurora-cyan) / <alpha-value>)",
          teal: "rgb(var(--aurora-teal) / <alpha-value>)",
        },
        bull: "rgb(var(--bull) / <alpha-value>)",
        bear: "rgb(var(--bear) / <alpha-value>)",
        ink: {
          100: "rgb(var(--ink-100) / <alpha-value>)",
          200: "rgb(var(--ink-200) / <alpha-value>)",
          300: "rgb(var(--ink-300) / <alpha-value>)",
          400: "rgb(var(--ink-400) / <alpha-value>)",
          500: "rgb(var(--ink-500) / <alpha-value>)",
          600: "rgb(var(--ink-600) / <alpha-value>)",
        },
        line: "rgb(var(--line) / <alpha-value>)",
        surface: "rgb(var(--surface) / <alpha-value>)",
      },
      fontFamily: {
        sans: ['"Segoe UI"', "ui-sans-serif", "system-ui", "Roboto", '"Helvetica Neue"', "Arial", "sans-serif"],
      },
      boxShadow: {
        glow: "0 0 0 1px rgba(139,92,246,0.18), 0 8px 40px -12px rgba(99,102,241,0.45)",
        "glow-cyan": "0 0 0 1px rgba(34,211,238,0.20), 0 8px 40px -12px rgba(34,211,238,0.40)",
        panel: "0 1px 0 0 rgba(255,255,255,0.04) inset, 0 20px 50px -30px rgba(0,0,0,0.9)",
      },
      backdropBlur: { xs: "2px" },
      keyframes: {
        marquee: { "0%": { transform: "translateX(0)" }, "100%": { transform: "translateX(-50%)" } },
        flashUp: { "0%": { backgroundColor: "rgba(34,197,94,0.35)" }, "100%": { backgroundColor: "transparent" } },
        flashDown: { "0%": { backgroundColor: "rgba(251,91,107,0.35)" }, "100%": { backgroundColor: "transparent" } },
        pulseDot: { "0%,100%": { opacity: "1" }, "50%": { opacity: "0.3" } },
        shimmer: { "100%": { transform: "translateX(100%)" } },
      },
      animation: {
        marquee: "marquee 40s linear infinite",
        flashUp: "flashUp 0.7s ease-out",
        flashDown: "flashDown 0.7s ease-out",
        pulseDot: "pulseDot 1.6s ease-in-out infinite",
      },
    },
  },
  plugins: [],
};
export default config;
