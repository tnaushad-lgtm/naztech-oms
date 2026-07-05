// Headless screenshot capture for the Naztech OMS user manual.
// Drives the user's installed Chrome via puppeteer-core against the running app.
const puppeteer = require("puppeteer-core");
const fs = require("fs");
const path = require("path");

const CHROME = "C:/Program Files/Google/Chrome/Application/chrome.exe";
const BASE = "http://localhost:3060";
const API = "http://localhost:8090";
const OUT = path.join(__dirname, "img");
const VIEW = { width: 1440, height: 900, deviceScaleFactor: 1.5 };

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// Each shot: who logs in, where to go, output name, extra wait, optional in-page action
const SHOTS = [
  { user: null,         route: "/login",     name: "01_login",              wait: 2500, forceVisible: true },
  { user: "dealer1",    route: "/dashboard", name: "02_dashboard",          wait: 4000, preset: "Market Overview" },
  { user: "dealer1",    route: "/terminal",  name: "03_terminal",           wait: 4000 },
  { user: "dealer1",    route: "/workspace", name: "04_workspace",          wait: 4500 },
  { user: "dealer1",    route: "/screener",  name: "05_screener",           wait: 5000 },
  { user: "dealer1",    route: "/portfolio", name: "06_portfolio",          wait: 3500 },
  { user: "dealer1",    route: "/reports",   name: "07_reports",            wait: 6000 },
  { user: "rms",        route: "/rms",       name: "08_rms",                wait: 6000 },
  { user: "exchadmin",  route: "/admin",     name: "09_admin",              wait: 6000 },
  { user: "investor1",  route: "/dashboard", name: "10_investor_dashboard", wait: 3800, preset: "Portfolio & P&L" },
  { user: "investor1",  route: "/portfolio", name: "11_investor_portfolio", wait: 3500 },
  { user: "investor1",  route: "/dashboard", name: "12_ai_advisor",         wait: 3500, action: "advisor", preset: "Portfolio & P&L" },
  { user: "dealer1",    route: "/terminal",  name: "13_amend",              wait: 4000, action: "amend" },
  { user: "dealer1",    route: "/terminal",  name: "15_depth",              wait: 4500, action: "depth", vh: 1360 },
];

async function login(page, user) {
  // hit the auth API from the page origin, store the session exactly like the app does
  const res = await page.evaluate(async (api, u) => {
    const r = await fetch(api + "/api/auth/login", {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username: u, password: "demo123" }),
    });
    if (!r.ok) return { error: r.status };
    const j = await r.json();
    localStorage.setItem("oms_session", JSON.stringify(j));
    return j;
  }, API, user);
  if (res.error) throw new Error(`login ${user} failed: ${res.error}`);
  return res;
}

(async () => {
  if (!fs.existsSync(OUT)) fs.mkdirSync(OUT, { recursive: true });
  const browser = await puppeteer.launch({
    executablePath: CHROME, headless: "new",
    defaultViewport: VIEW, args: ["--no-sandbox", "--hide-scrollbars"],
  });
  const page = await browser.newPage();
  // land on the origin once so localStorage is writable
  await page.goto(BASE + "/login", { waitUntil: "domcontentloaded" });

  const filter = process.argv[2]; // optional substring to re-capture only some shots
  let lastUser = "__none__";
  for (const s of SHOTS) {
    if (filter && !s.name.includes(filter)) continue;
    try {
      if (s.user && s.user !== lastUser) {
        const sess = await login(page, s.user);
        lastUser = s.user;
        console.log(`  logged in ${s.user} -> ${sess.role} (${sess.displayName})`);
      }
      if (!s.user) { await page.evaluate(() => localStorage.removeItem("oms_session")); lastUser = "__none__"; }

      await page.setViewport(s.vh ? { width: 1440, height: s.vh, deviceScaleFactor: 1.5 } : VIEW);
      await page.goto(BASE + s.route, { waitUntil: "networkidle2", timeout: 45000 }).catch(() => {});
      await sleep(s.wait);
      if (s.action === "depth") {
        // select a liquid equity so the order book has rich bid/ask levels
        await page.evaluate(() => {
          const b = [...document.querySelectorAll("button")].find((x) => {
            const t = (x.textContent || "").trim();
            return /^BEXIMCO\b/.test(t) && t.length < 60;
          });
          if (b) b.click();
        });
        await sleep(2500);
      }
      if (s.preset) {
        // apply a built-in dashboard preset via the "Load preset…" <select> (React-controlled)
        await page.evaluate((presetName) => {
          const sel = [...document.querySelectorAll("select")].find((x) =>
            [...x.options].some((o) => o.value.startsWith("b:")));
          if (!sel) return;
          const val = "b:" + presetName;
          const setter = Object.getOwnPropertyDescriptor(window.HTMLSelectElement.prototype, "value").set;
          setter.call(sel, val);
          sel.dispatchEvent(new Event("change", { bubbles: true }));
        }, s.preset);
        await sleep(3500); // let widgets + charts render
      }
      if (s.action === "advisor") {
        await page.evaluate(() => {
          const btn = [...document.querySelectorAll("button")].find((b) => /AI\s*Advisor/i.test(b.textContent || ""));
          if (btn) btn.click();
        });
        await sleep(2500); // drawer open animation
      }
      if (s.action === "amend") {
        const handle = await page.$('button[title*="Amend"]'); // real mouse click → fires React onClick reliably
        if (handle) { await handle.click().catch(() => {}); }
        await sleep(800);
        await page.evaluate(() => window.scrollTo(0, 0)); // modal is fixed/centered → show it over the terminal top
        await sleep(900);
        const modal = await page.evaluate(() => /amend order/i.test(document.body.innerText));
        console.log(`    amend button: ${!!handle}, modal open: ${modal}`);
      }
      if (s.forceVisible) {
        // kill entrance animations so the captured frame is fully painted (fixes mid-fade/black)
        await page.addStyleTag({ content:
          '*{animation:none!important;transition:none!important}' +
          '[style*="opacity"]{opacity:1!important}[style*="transform"]{transform:none!important}' });
        await sleep(500);
      }
      const file = path.join(OUT, s.name + ".png");
      await page.screenshot({ path: file, fullPage: false });
      console.log(`✓ ${s.name}  (${s.route})`);
    } catch (e) {
      console.log(`✗ ${s.name}: ${e.message}`);
    }
  }
  await browser.close();
  console.log("DONE");
})();
