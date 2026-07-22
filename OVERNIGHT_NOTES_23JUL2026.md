# Overnight notes — 23 Jul 2026

Everything below is committed and running. Frontend was rebuilt and restarted; open the Trader
Terminal and press **Order Grid**.

## Your two reports

**1. "Ticker went blank after I picked a client."**
The ticker was never lost — screenshot 3 showed `✓ pass 55` and `Ready 1`, so the row was complete
and would have sent correctly. It was a display fault: an open combobox rendered the text you were
*typing* (nothing), so focusing a filled cell blanked it on screen while the value stood. Arguably
worse than losing it, because the row looked empty but was armed.

Fixed two ways: a box keeps showing its value until you actually type, and auto-advance now skips
fields that are already filled instead of walking you through them.

**2. "At minimum width the right-side action items are not visible."**
Three separate faults, all below ~745px:

- The action buttons scrolled off the edge → they are now **pinned right**; the row scrolls
  underneath them, so SEND is reachable at any width.
- The column header sat *outside* the scroll container and had no `shrink-0`, so its labels
  compressed while the cells did not — drifting up to 90px out of line — and it could not scroll
  with them. It now lives inside the scroller, stuck to the top.
- Nothing folded, so cells simply overflowed. **Validity** folds away below 690px and **Type** below
  560px, into the row band and the row tooltip. No *value* is lost when a *control* folds — that is
  a standing rule in this file. Minimum width is now 440px (was 600).

Measured at 520px: header and cells share a left edge to the pixel, SEND visible, no horizontal
scroll.

## What the QA sweep found

Eight Playwright agents drove every entry method and order type; a second agent independently
reproduced each finding before I acted on it. 13 defects fixed. The two that mattered most could
each have **sent a wrong order to the exchange**:

**The price cell swallowed the decimal point.** Bound to a parsed number, typing `301.` re-rendered
as `301` and the next digit gave `3014`. `301.40` became `30140`, passed the pre-trade check —
because it is a perfectly valid number — and was transmitted. Verified fixed end to end: the blotter
now shows `GP BUY 20 @ 301.40 FILLED`.

**The comboboxes dropped the first character — and that one was mine.** My earlier fix deferred
`select()` to an animation frame, which lands *after* the first keystroke of a fast typist, so the
second character replaced it. Typing `GP` left `P`, the list rebuilt from `P`, and Enter committed
**PADMALIFE**. Now synchronous.

Also fixed: the client dropdown was clipped by the grid's own overflow (one of 500 accounts visible
on row 2 — now portalled, and it flips upward when there is no room below); Enter on a no-match
client appended a row and stranded the text looking committed; the list stayed painted over
unrelated rows after focus moved; a focused cell could not be reopened by clicking it; the command
box failed silently on a cold panel and concatenated your next keystrokes into the command; the
footer counted a row with no chosen side as a **BUY**, overstating exposure in a direction nobody
picked; "Sent 1 · held back 1" counted an already-sent row as held back; the risk column was a hard
70px so RMS reasons truncated to `✕ A market …` even maximised; the toast covered the Sell/Net
figures and never dismissed; `@ -5` was told "must be a number" when −5 *is* one.

## New: Ctrl+Enter

A dealer could fill a row entirely by keyboard and then had no way to send it — the only routes were
the mouse or seven Tabs across filled fields. **Ctrl+Enter sends the lit row**, guarded by exactly
the same `sendable()` the button uses, so it can never send anything the button would refuse. When it
refuses it says why. Plain Enter still just commits the row and opens the next.

(Focusing the SEND button alone cannot work: it is disabled until the async risk check returns, and
a disabled button cannot take focus.)

## Two decisions for Naz bhai

These are his to overrule, not mine:

**1. The BO number is gone from the cell but kept in the dropdown.** 501 of the 506 accounts share a
name with another account — "Imran Ahmed" alone is nine different BO numbers. A bare-name dropdown
would offer nine identical rows at exactly the moment a wrong account gets picked. Once picked, the
row is bound to an id, so the cell shows just `Arif Haque` with the BO in the tooltip. He gets the
width; the pick stays safe.

**2. The command box fills a row — it does not send it.** The grammar (`B 100 GP @ 120`) carries no
client, so sending on Enter would attach the order to whichever account happened to be selected.
That is precisely the accident this grid's provenance rules were built after. Everything you type is
marked as your decision; the account stays yours to choose. If he wants Enter to place directly, say
so and I will build it.

## Late addition — two more defects, both mine

The adversarial verifiers finished after I wrote the section above and caught two faults in work I
had committed an hour earlier. **Both were scored PASS by my own test**, which is the more useful
lesson.

**Ctrl+Enter did not actually send.** The per-row Enter handler also caught the modified chord, so
Ctrl+Enter committed the row and opened a *new blank one* first; the send handler then judged that
empty row and refused with "Row is incomplete." — printed beside a row plainly reading `✓ pass 45`.
It worked only when focus was outside a row, which is what made it look intermittent. My test had
asserted "sends **or** explains" and accepted the toast as the explain branch. A test that accepts
either outcome tests nothing. It now requires an order id to appear and no "incomplete" message.

**Clicking into the price still prepended.** Select-on-focus was correct, but the mouseup that
completes the focusing click collapses the selection back to a caret. My test pressed `Ctrl+A`
first — which no trader does — so it never saw it. Only that one mouseup is now suppressed; a second
click still positions the caret normally.

Both fixed and verified by clicking rather than keyboard-shortcutting: Ctrl+Enter from inside a row
sends (order #70892), spawns no blank row, no false message; typing over a seeded price replaces it.

**13/13 regression checks pass, no page errors.**

## Still open

- **Intermittent "Loading order grid…"** — one QA agent hit a panel that never resolved, with no
  4xx/5xx and no page error. I could not reproduce it in three later runs (the panel loads with all
  7 cells), and the machine was running eight browsers at the time, so it looks like a load
  artifact. Worth watching; if you see it, tell me.
- The Python market-data service on **:8091 is not running**, so `/ai/forecast` calls fail in the
  console. Per CLAUDE.md the OMS runs without it — start it with `start-marketdata.bat` if you want
  the AI outlook chip back.
- The chart work from earlier (advanced chart, indicator picker, crosshair readout) is committed and
  unaffected.
- `backend/.../MarketDataRepo.java` is still your uncommitted edit — untouched all session.

## Commits tonight

```
6b722e4  Order grid: fix 13 defects found by a browser QA sweep, and survive any width
76aa7ab  Order grid: narrow, keyboard-first, command entry
c2527cd  Chart: crosshair readout with indicator values, and tell duplicate instances apart
56a18b0  Chart: a real indicator picker, and indicators you can actually configure
9d354d1  Advanced Chart: a full-screen chart that knows which orders are yours
```
