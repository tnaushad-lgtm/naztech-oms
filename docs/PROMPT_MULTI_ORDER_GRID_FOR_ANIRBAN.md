# Prompt — multi-row order entry grid with live market depth

Give the whole block below to Claude Code as a single prompt. It is written to be stack-agnostic:
it says what to build and why, and tells the agent to discover your own components and endpoints
rather than assuming ours.

---

Build a **multi-row order entry grid** with a **live market-depth ladder below it**, as a new screen
in this OMS. Leave the existing single-order ticket exactly as it is — this is an additional way to
trade, not a replacement.

## First, explore before writing anything

Find and report, then build against what you actually find:

- The existing order-entry component and the exact request body it POSTs to place an order.
- The endpoint that lists client/BO accounts, and whether it returns a buying-power or limit field.
- The endpoint that lists tradable instruments, and whether it carries last-traded price.
- The market-depth source: REST snapshot, WebSocket or SSE, and the shape of a depth level
  (price / quantity / order-count per side).
- Any pre-trade risk or validation endpoint that can be called *before* submitting.
- The shared table/grid, theme tokens and number formatting helpers already in the project.

Do not invent endpoints. If something does not exist, say so and propose the smallest addition.

## The screen

One row per order. Columns, left to right:

| Column | Control | Notes |
|---|---|---|
| select | checkbox | for bulk actions |
| # | row number | |
| **BO / Client** | searchable combobox | see "Client picker" below |
| **Ticker** | searchable combobox | match on symbol *and* company name |
| **Side** | Buy/Sell, one click | never a dropdown you must open |
| **Type** | Limit / Market / Stop / Stop-Limit | |
| **Qty** | number | |
| **Price** | number | disabled for Market |
| **Order terms** | text | spells out type · market · validity |
| **Value** | computed | qty × price |
| **Risk** | live verdict | from the pre-trade check |
| actions | send · duplicate · remove | |

Rarely-changed fields (settlement market, validity, stop trigger) live on a second line that opens
for the row being edited, each control under a permanent label. Do not hide them behind hover.

## Market depth, below the grid — not beside it

Put the ladder **underneath** the grid at full width. We tried it on the right first; it took 300px
off the entry area and pushed the columns into a horizontal scroll, so the two things a trader needs
at the same moment were competing for the same space.

The ladder must:

1. **Follow the row being edited.** Move to another row, the book follows. This is the whole point:
   "where is the market" and "where do I want to be" answered in one glance.
2. Show **bid and ask side by side**, price aligned toward the spread, with proportional size bars
   and a **cumulative** column on each side. Cumulative is what says how far a market order would
   walk through the book.
3. Say **whether this order will rest or trade** — a buy at or above the best offer lifts it; a sell
   at or below the best bid hits it. Show that verdict *before* the trader presses send. Keep it
   silent until the side is chosen, because direction is what decides it.
4. **Mark the trader's own working orders** on their price level. An exchange feed is anonymous —
   it publishes aggregate size per level and never says "that part is yours" — but you know your own
   orders, so you can say it.
5. **Say when an order is outside the visible levels.** A limit priced away from the touch sits below
   the shown rows; printing nothing reads as "my order never arrived". Print
   "1 outside these 5 levels (BUY 10 @ 238.70)".
6. Be titled **"Market Depth"** with the instrument beside it, and show live bid / ask / spread.

Clicking a price level should set that row's price. The ladder is an input, not a poster.

## Six rules that are not cosmetic

These each came from a real defect. Please honour them.

1. **A default is not a decision.** Track every field as `unset | defaulted | confirmed`. Client and
   Side must be *chosen* — render them dashed-amber until they are, and block sending. This is what
   stops forty pasted rows becoming forty confident orders to one wrong account. Price is the
   exception: seeding it from the last traded price is what every terminal does, and warning on it
   every time only teaches people to click past warnings — show it as visibly inherited instead.

2. **Never guess Side on paste.** A blank or column-shifted Side must come through as *unset*, not
   as BUY. Forty confident buys and a flawless green batch is the worst thing this screen can
   produce.

3. **Send only what passed.** Check `risk.pass === true`, not `risk.pass !== false`. We shipped the
   second one: `undefined !== false` is `true`, so every row that had never been risk-checked was
   being sent.

4. **Inputs must look like inputs.** Give every editable cell a border and a fill; brighten on focus;
   remove the border entirely when disabled. Ours were transparent and users could not find the
   quantity field.

5. **Never `type="number"` for quantity or price.** Arrow keys decrement the value and the mouse
   wheel mutates it silently. Use `type="text"` with a decimal input mode.

6. **Keep sent rows live.** The response to a submit is the status *at that instant* — usually a
   transient "pending". Subscribe to updates (or poll) until each row reaches a terminal state,
   and colour by meaning: in-flight amber, working on the book cyan, filled green, rejected red.
   Show plain words, not internal enum names.

## Also expected

- **Paste from Excel** into the grid (`Ticker, Side, Type, Qty, Price`). Dealers keep instructions in
  spreadsheets; retyping thirty orders is how fat fingers happen.
- **Enter commits the row and opens the next**, with focus landing in the client field.
- **Duplicate row** — same client, same instrument, different size is the common shape of a list.
- **Send all** / **Send selected** / **Clear sent**, with counts on the buttons. Clear sent removes
  only rows that were sent; their status belongs in the blotter, not here.
- A **review mode** that compacts rows and states plainly how many will send, how many are held and
  why. Make it say something — ours compacted rows by 6px and looked broken until it did.
- Running **buy / sell / net** totals, and the chosen client's **buying power** beside the order
  value, turning red when the order exceeds it.

## Client picker — the part that will bite you

Check your account list for duplicate names before designing this. In our book, 501 of 506 accounts
share a name with another account — only 65 distinct names, and one name covers nine different BO
numbers. If yours is similar, a name-only dropdown offers several identical rows and no way to tell
them apart.

Show the name, and append a short disambiguator (last four BO digits) **only where the name is not
unique**. Keep the full identifier searchable and in the tooltip. Match on any substring of number or
name, rank exact → prefix → word-start → mid-string, and highlight the matched text.

Two more: **Tab must not accept the highlighted match** — Tab is the universal move-on key, and
binding it to accept-first-match hands a hurried trader an account they never chose. And Enter inside
an open picker must not also commit the row.

## Validation at entry

Reject combinations the venue would take but that mean nothing — market order with a good-till
validity, odd-lot on an instrument whose lot is 1, block-market below the exchange floor, yield basis
on an equity. Check your backend first: ours silently accepted all of these, so they went out on the
wire and came back as a venue rejection thirty seconds later, by which time the trader had lost their
place in the queue. Where a choice makes another field meaningless, correct it rather than complain.

## Finally

- Match the existing look — reuse the project's theme tokens, spacing and formatting helpers.
- Use the local number convention for money if the market needs one (South Asian grouping is
  10,00,000 and not 1,000,000; lakh and crore are what a desk actually says).
- Verify in a real browser before declaring it done: place an order end to end, confirm the depth
  panel follows the row, and confirm a sent row updates to its final status on its own.
