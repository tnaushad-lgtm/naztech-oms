# nFIX — FIX acceptor drops every logon (for Jewel)

**From:** Tareq (Naztech Brokerage OMS)
**To:** Jewel (nFIX / DSE simulator)
**Date:** 16 July 2026
**Re:** Order-entry FIX session won't establish — ITCH market data is perfect

Jewel — I've wired our OMS to nFIX per your `DSE-SIMULATOR-INTEGRATION-GUIDE.md`. **The ITCH side is
flawless** (details at the bottom). But the **FIX order-entry acceptor on `:9014` drops every logon
without a single FIX message back**, so no orders can flow. I've eliminated our side as the cause;
this looks like an nFIX-side acceptor issue. Below is everything you need to reproduce and locate it.

---

## The symptom, exactly

1. We open a TCP connection to `10.33.1.23:9014` → **succeeds** (MINA/socket connects).
2. We send a FIXT.1.1 Logon (`35=A`).
3. nFIX **closes the socket immediately with no FIX reply** — QuickFIX/J logs it as
   `Disconnecting: Encountered END_OF_STREAM`.
4. Our initiator retries every 5 s; same result every time.

There is **no** Logout (`35=5`), **no** Reject (`35=3`), **no** Business Reject — just a silent TCP
close. Per your guide, a successful logon should return a **Position Report (`35=AP`)** with our firm
trading limit. We receive nothing.

### The exact bytes we send (from our QuickFIX/J log)

```
8=FIXT.1.1 | 9=74 | 35=A | 34=1 | 49=OMS | 52=20260716-06:41:09.814 | 56=DSE | 98=0 | 108=30 | 141=Y | 1137=8 | 10=146
```

- `49=OMS`, `56=DSE` — the CompIDs from your guide §3.1
- `1137=8` — DefaultApplVerID = **FIX.5.0SP1** (correct; 8 = SP1, 9 = SP2)
- `98=0` EncryptMethod none, `108=30` HeartBtInt, `141=Y` ResetSeqNumFlag
- Well-formed: BodyLength and CheckSum verified

### Our initiator config (matches your guide)

```
ConnectionType   = initiator
BeginString      = FIXT.1.1
SenderCompID     = OMS
TargetCompID     = DSE
DefaultApplVerID = FIX.5.0SP1
SocketConnectHost= 10.33.1.23
SocketConnectPort= 9014
ResetOnLogon     = Y
HeartBtInt       = 30
```

---

## What I ruled out (so you don't have to)

I tested logon against `:9014` with a raw socket client, trying **every** plausible variation. **All
five got the identical silent `END_OF_STREAM`, zero bytes back:**

| Attempt | SenderCompID → TargetCompID | ApplVerID | Result |
|---|---|---|---|
| 1 | `OMS → DSE` | FIX.5.0SP1 (8) | END_OF_STREAM |
| 2 | `OMS → DSE` | FIX.5.0SP2 (9) | END_OF_STREAM |
| 3 | `OMS → DSE` + Username(553) | FIX.5.0SP1 | END_OF_STREAM |
| 4 | `OMS → DSESIM01` | FIX.5.0SP1 | END_OF_STREAM |
| 5 | `BROKER → DSE` | FIX.5.0SP1 | END_OF_STREAM |

So it is **not** our CompIDs, **not** the app version, **not** missing credentials, and **not** our
message framing (a hand-rolled raw logon and our production QuickFIX/J initiator both get dropped
identically).

**And critically:** the ITCH SoupBinTCP feed on `:9012` — same host, same machine — **works perfectly**
(994k+ messages, zero gaps). So the network path, the firewall, and nFIX-the-process are all fine.
The problem is specific to the **FIX acceptor component**.

---

## UPDATE 2 (16 Jul, decisive) — it's the nFIX FIX acceptor, right now

I retract the "loopback bind" guess below — Anirban's `application.yaml` sets
`dse.fix.host: 10.33.1.23` (the server IP) and his `FixInitiatorConfig` overrides the cfg with it, so
his OMS connects to nFIX's FIX **remotely** and it works. Remote FIX is fine; the bind is not the issue.

So I ran the cleanest possible test: **stopped our OMS entirely** (CompID `OMS` free, no reconnect
churn), and sent **one** bare-minimum FIX.5.0SP1 logon from a raw socket:

```
>> 8=FIXT.1.1|9=70|35=A|49=OMS|56=DSE|34=1|52=20260716-07:19:48|98=0|108=30|141=Y|1137=8|10=204
<< (nothing — socket closed by peer, END_OF_STREAM)
```

That logon has nothing to do with our OMS — no engine, no store, no extra fields — and nFIX **still**
drops it. Combined with the line-by-line match to Anirban's proven config, this means:

> **Our side is correct. nFIX's FIX acceptor on `:9014` is currently refusing every logon.**

Most likely because you're **actively working on nFIX** (antigravity/claude code) and the FIX acceptor
is down / mid-restart / the trading session isn't started. The ITCH acceptor on `:9012` is up and
serving us perfectly the whole time, so nFIX-the-process is running — it's specifically the FIX
acceptor.

**What would confirm it in 10 seconds on your side:** from the nFIX box, run
`Test-NetConnection 127.0.0.1 -Port 9014` and then a local logon (or just watch the nFIX FIX event
log while I'm connecting — my OMS retries every 5s as `OMS→DSE`). If you see our `35=A` arrive and get
rejected, the log will say why; if you don't see it arrive at all, the acceptor isn't reading.

Our OMS is left running and **will connect automatically** the moment your acceptor accepts logons —
no change needed on our side.

---

## UPDATE 1 (16 Jul, after reviewing your `dse-oms-core` reference)

Thanks for the zip. I compared Anirban's proven OMS against ours **line by line**, and **our setups are
identical** — so this is not a config difference on our side. The strong new clue is
**localhost vs remote**.

| | Anirban's `dse-oms-core` (works) | Our OMS (dropped) |
|---|---|---|
| QuickFIX/J | 2.3.1 | 2.3.1 |
| CompIDs | `OMS → DSE` | `OMS → DSE` |
| DefaultApplVerID | FIX.5.0SP1 | FIX.5.0SP1 |
| ResetOnLogon | Y | Y |
| Symbol(55) | `String.valueOf(orderbook)` | `String.valueOf(orderbook)` |
| `toAdmin` on Logon | empty | adds nothing (username blank) |
| **SocketConnectHost** | **`127.0.0.1`** | **`10.33.1.23` (remote)** |

Anirban's committed `oms-fix.cfg` connects to **`127.0.0.1:9014`** — his OMS and nFIX ran on the
**same machine**. Ours runs on a laptop connecting across the LAN. **ITCH (9012) works remotely; FIX
(9014) does not.** And a bare-minimum raw-socket logon — no OMS, no store, nothing but the FIX bytes —
is dropped exactly the same way, so it is not our engine or our sequence state.

**This points squarely at the FIX acceptor not accepting REMOTE connections** — which your own guide's
troubleshooting table already flags: *"acceptor bound to loopback (`SocketAcceptAddress` must be
`0.0.0.0`)."*

### The one test that will confirm it in 2 minutes

Run **Anirban's own `dse-oms-core`** but point it at nFIX **over the network instead of localhost**:

```
DSE_FIX_HOST=10.33.1.23  DSE_FIX_PORT=9014   # (his FixInitiatorConfig reads these to override the cfg)
```

- If Anirban's OMS **also** gets `END_OF_STREAM` remotely → it's confirmed: nFIX's FIX acceptor only
  serves loopback. Fix: bind it to `0.0.0.0` (add `SocketAcceptAddress=0.0.0.0` to nFIX's acceptor
  settings, same as the ITCH SoupBin acceptor which already does this — that's why ITCH works remotely).
- If Anirban's OMS **connects** remotely but ours doesn't → then it genuinely is something in our
  message and I'll dig further; please send me the nFIX FIX event log from both attempts.

---

## Other things to check on the nFIX side (if the bind isn't it)

1. **Is the FIX acceptor actually started, and reading?**
   A silent `END_OF_STREAM` right after the socket opens is the classic signature of a socket that is
   `accept()`-ed but whose QuickFIX/J acceptor either isn't running or throws before replying. Check
   nFIX's startup log for the acceptor starting on 9014, and its FIX event/message logs for our
   inbound logon — **do you even see our `35=A` arriving?** If not, the acceptor isn't wired to the
   listening socket.

2. **Is there a session configured for our CompIDs?**
   From the acceptor's perspective the session is **`SenderCompID=DSE, TargetCompID=OMS`** (the reverse
   of ours). If nFIX's `acceptor.cfg` has no `[session]` block matching that pair, QuickFIX/J logs
   something like *"Unknown session"* / *"Session not found"* and drops the connection — exactly what we
   see. Please confirm the block exists, or tell me the CompIDs nFIX actually expects.

3. **`ResetOnLogon` / sequence state.**
   If nFIX's acceptor has `ResetOnLogon=N` and persisted a sequence for the `OMS` session from an
   earlier test, and it does **not** honour our `141=Y`, it may drop us on a sequence mismatch. Simplest
   fix: set `ResetOnLogon=Y` on nFIX's acceptor session too, or clear its `FileStorePath` for this
   session once.

4. **`DefaultApplVerID` on the acceptor.**
   It must be `FIX.5.0SP1` and it needs the `FIXT11.xml` + `FIX50SP1.xml` dictionaries on nFIX's
   classpath. A missing/mismatched app dictionary can make the acceptor fail to build the session.

5. **Does order entry require the market to be open?**
   Your guide's troubleshooting notes fills need the market open. If the acceptor refuses *logons*
   while the session is in pre-open/closed, that would explain it — please confirm whether the FIX
   acceptor accepts logons at all times, or only once a trading session is started.

The single most useful thing you can send back: **the nFIX FIX event log for the moment we connect** —
it will say precisely why QuickFIX/J closed the socket (unknown session, dictionary error, seq
mismatch, etc.).

---

## For reference — the ITCH side is done ✅

So you know the integration is otherwise complete and your guide is accurate:

- SoupBinTCP login on `:9012` accepted (session `DSESIM01`), full replay from seq 1.
- **994,000+ ITCH messages** consumed, **0 gaps, 0 lost**, book reconstruction healthy.
- All **300 securities** (order-book ids 1001–1300) discovered from the `R` directory; our security
  master is rebuilt from them so `security.id == order-book id`.
- Live depth verified: BRACBANK (1004) shows real bid/ask ladders from your matching engine.
- Your `R` directory message is **byte-identical** to our ITCH v2.2 codec (171 bytes incl. the bond
  maturity/yield fields) — nice, it means we share the same wire format end to end.

Once the FIX acceptor accepts our logon, **orders will flow with no further changes on our side** — the
NewOrderSingle build, the Symbol(55)=order-book-id mapping, and the ExecutionReport handling are all
done and tested. We just need the session to come up.

Thanks!
— Tareq
