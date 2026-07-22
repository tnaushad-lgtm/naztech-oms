# Prompt — restyle the OMS UI (glassmorphism, compact density, themeable)

Give the whole block below to Claude Code as a single prompt. It contains the actual design tokens,
so the result is reproducible rather than a matter of taste.

---

Restyle this OMS's user interface. The functionality is fine — this is a **visual and density**
change. The brief from the product owner is: glassmorphism, more compact, a coherent theme, and a
distinct visual identity rather than looking like every other terminal.

Work incrementally: build the token layer first, convert one screen, show it, then roll outward.
Do not rewrite features while restyling.

## 1. Make every colour a CSS variable first

Nothing may hard-code a hex value. This is the load-bearing step: it is what makes theming possible
at all, and retrofitting it later means touching every component twice.

Channels are stored space-separated so an alpha can be applied per use:

```css
:root, [data-theme="midnight"] {
  color-scheme: dark;
  --obsidian-950: 6 7 13;      --obsidian-900: 10 12 22;    --obsidian-850: 14 17 30;
  --obsidian-800: 19 23 38;    --obsidian-700: 27 33 51;    --obsidian-600: 37 44 66;
  --ink-100: 238 242 255;      --ink-200: 215 221 240;      --ink-300: 194 201 224;
  --ink-400: 154 163 196;      --ink-500: 107 116 148;      --ink-600: 74 82 110;
  --aurora-violet: 139 92 246; --aurora-indigo: 99 102 241;
  --aurora-cyan: 34 211 238;   --aurora-teal: 45 212 191;
  --bull: 34 197 94;           --bear: 251 91 107;
  --line: 255 255 255;         --surface: 255 255 255;
  --bg-image:
    radial-gradient(1100px 600px at 12% -8%, rgba(139,92,246,0.16), transparent 60%),
    radial-gradient(1000px 560px at 100% 0%, rgba(34,211,238,0.12), transparent 55%),
    radial-gradient(900px 700px at 50% 120%, rgba(45,212,191,0.10), transparent 60%);
}
```

`obsidian` = surfaces, darkest to lightest. `ink` = text, brightest to dimmest. `aurora` = accents.
`bull`/`bear` = up/down. If you use Tailwind, expose them alpha-aware:

```js
obsidian: { 850: "rgb(var(--obsidian-850) / <alpha-value>)" }   // and so on
```

Add a light theme (`[data-theme="daylight"]`) by overriding the same names — invert obsidian and ink,
darken the accents, set `--line: 15 23 42`. Then a theme switcher writing
`document.documentElement.setAttribute("data-theme", id)` and persisting to localStorage restyles the
entire app with no component changes. Restore the saved theme in a blocking inline script before
first paint, or the app flashes the wrong theme on every load.

## 2. Panel primitives

Define these once and use them everywhere. Consistency is most of the effect.

```css
.glass       { background: rgb(var(--obsidian-850) / 0.70);
               backdrop-filter: blur(24px);
               border: 1px solid rgb(var(--line) / 0.10);
               border-radius: 1rem;
               box-shadow: 0 1px 0 0 rgb(255 255 255 / 0.04) inset,
                           0 20px 50px -30px rgb(0 0 0 / 0.9); }

.glass-soft  { background: rgb(var(--surface) / 0.04);
               backdrop-filter: blur(12px);
               border: 1px solid rgb(var(--line) / 0.08);
               border-radius: 0.75rem; }

.panel-title { font-size: 11px; font-weight: 600; text-transform: uppercase;
               letter-spacing: 0.16em; color: rgb(var(--ink-400)); }

.chip        { display: inline-flex; align-items: center; gap: 4px;
               border-radius: 9999px; padding: 2px 8px;
               font-size: 10px; font-weight: 600; }
```

Buttons: a primary with a violet→indigo gradient and a soft glow on hover
(`0 0 0 1px rgba(139,92,246,0.18), 0 8px 40px -12px rgba(99,102,241,0.45)`), and a ghost button that
is just a bordered translucent surface. Inputs: rounded, translucent dark fill, 1px border, and on
focus an indigo border plus a soft ring.

The glassmorphism only reads if the page behind it has something to blur — put the `--bg-image`
radial gradients on the body. Blur over flat black looks like flat black.

## 3. Compactness

The current UI is roughly twice as tall as it needs to be. Aim for a dealer being able to see the
book, the chart and the blotter without scrolling.

- Table rows ~28px, cells `px-3 py-1.5`. Data tables are for reading, not for breathing room.
- Panel padding 12–16px, not 24px.
- Type scale: 10px for column headings (uppercase, letterspaced), 11–12px for data, 12–13px for
  labels, 18px+ only for the one number a panel exists to show.
- Numbers in a tabular/monospaced figure setting so columns align down the page.
- Sticky table headers; scroll the body, never the header.

**But do not confuse compact with faint.** Nothing readable should sit below the `ink-400` level. We
made column headers and default values very dim for elegance and had to undo it — on a trading screen
a value that must be squinted at is a value that will be misread. Dim is for what does not matter;
everything else is bright.

## 4. Visual identity — the part the product owner actually asked for

Right now this looks like a generic terminal. Three things carry identity cheaply:

1. **The aurora accent range.** One gradient family (violet → indigo → cyan) used for the primary
   action, the active nav item, focus rings and section titles. Not a different accent per screen.
2. **Rounded, floating panels over a coloured-glass background** — the radial gradients above, panels
   translucent on top of them, generous corner radius (12–16px). This is what distinguishes it from
   the boxy grey-on-grey look.
3. **Semantic colour discipline.** Green and red mean up and down, and *nothing else*. Do not use red
   for a neutral close button or green for a generic OK. On a trading screen those two colours are
   information.

## 5. Layout

- One persistent sidebar with the screen list; active item filled with a soft aurora gradient.
- One header per screen: title left; status pills (market open/closed, connection, session) centre or
  right; clock far right.
- Prefer a couple of dense, information-rich screens over many thin tabbed ones. Your current tab
  strip has seventeen tabs; most of those are panels, not destinations. Consider a dockable workspace
  where a trader arranges panels, plus a widget dashboard they compose themselves.

## 6. Accessibility that also makes it look better

- Never encode meaning in colour alone. Buy/Sell should differ by **position, fill and label**, not
  just green/red — a filled left pill vs a filled right pill reads instantly and survives colour
  blindness. Adding a 45° hatch to the sell state costs nothing and helps.
- Focus rings must be visible: a 2px cyan ring is both usable and on-brand.
- Check the light theme genuinely works. Ours had one component with hard-coded hex that stayed dark
  on the light theme and looked broken — grep for `#` in style values when you think you are done.

## 7. Charts

Charting libraries take concrete colour strings, not `var(--x)`, so read the variables at draw time:

```js
const raw = getComputedStyle(document.documentElement).getPropertyValue('--ink-400').trim();
const colour = `rgb(${raw.split(/\s+/).join(',')})`;   // commas — see below
```

Two traps we hit: charts were the only components the theme switcher could not reach because their
colours were hard-coded; and `lightweight-charts` **rejects** modern CSS colour syntax — it cannot
parse `rgb(251 91 107)` and needs `rgb(251,91,107)`. Join the channels with commas.

## 8. Numbers, for this market

- Group the South Asian way: `10,00,000`, not `1,000,000`. `toLocaleString("en-IN")` does it.
- Offer lakh/crore for large figures — "10.00 L", "1.25 Cr" — which is how a Dhaka desk speaks.
- Prices to the instrument's tick precision; quantities as integers.

## 9. How to verify

Open it in a real browser and look, at 1366×768 as well as a wide monitor. Check both themes. Confirm
no page scrolls horizontally, that a data table shows noticeably more rows than before, and that
every interactive element has a visible hover and focus state. Screenshots before and after, side by
side, are the honest test of whether it is actually more compact or just differently spaced.
