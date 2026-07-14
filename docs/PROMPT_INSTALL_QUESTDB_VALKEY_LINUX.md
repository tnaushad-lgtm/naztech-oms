# PROMPT — Install QuestDB + Valkey on the office Linux server (for the Naztech DSE OMS)

> Paste everything below the line into a fresh Claude Code session **on the Linux server** (or in a
> repo/folder from which you can SSH to it). Answer its questions when it asks.

---

You are setting up two data services on our **office Linux server** for the Naztech Exchange-Hosted
OMS (Dhaka Stock Exchange, broker: Dragon Security). We already run both on a Windows dev laptop; you
are now doing it properly on a real server, so it must be a **systemd-managed, secured, restart-proof
installation** — not a jar launched from a terminal.

## First, ask me these before you touch anything
1. Distro and version (`cat /etc/os-release`), CPU count, RAM, and free disk on the data mount.
2. Is this box internet-connected, or do we need offline/air-gapped install from downloaded artifacts?
3. Which host(s) will connect — the OMS backend on this same box, or a separate app server? (Decides
   whether we bind to `127.0.0.1` or a private interface, and what the firewall rules are.)
4. Is there an existing `redis`/`valkey` or Java runtime installed? Don't collide with it.

Do not assume. If something is unclear, ask rather than guess.

---

## Service 1 — QuestDB (the tick time-series)

**What it is for.** Every trade print from the exchange feed is appended to QuestDB. Candles
(1m/5m/15m/1h/1d) are read back out of it with a single `SAMPLE BY` query. MySQL stays the system of
record for orders/trades/positions; QuestDB holds only what can be rebuilt from MySQL and the feed.
Without it the OMS still runs — it just falls back to bucketing the MySQL trade table.

**Install it as a service:**
- Install a **JDK 21** runtime if not present.
- Get QuestDB (the no-Docker binary/tarball, or the official `.deb`/`.rpm` if it fits our distro).
  Pin the version and record it — we run **8.1.1** on the dev laptop; use that or newer, but tell me
  which you chose.
- Create a dedicated **unprivileged system user** (`questdb`), a data directory on the large/fast
  mount (NVMe if we have it), and a **systemd unit** with `Restart=always` so it survives reboots.
- Tune it for the box, don't leave defaults: JVM heap sized to the RAM we actually have, and set the
  `shared worker count` sensibly against the CPU count. Tell me what you set and why.

**Ports** (must match what the OMS expects):
- **9009** — ILP ingest (InfluxDB Line Protocol over TCP). This is how ticks are written.
- **9000** — HTTP / SQL / web console. This is how candles are queried, and how a human browses it.

**Security — this matters:**
- QuestDB open-source has **no authentication on the HTTP console or the ILP port**. Anyone who can
  reach :9000 can read and drop our tables.
- Therefore: **bind to `127.0.0.1`** if the OMS runs on this same box. If it does not, bind to the
  private interface only and lock both ports down with `firewalld`/`ufw` to the OMS host's IP only.
  **Never expose 9000 or 9009 to the office LAN generally, and absolutely never to the internet.**
- If we need the web console from a workstation, do it over an **SSH tunnel**, not by opening the port.

**Create the table the OMS writes to** (it auto-creates on first run, but do it explicitly so we
control the partitioning and can prove it exists):
```sql
CREATE TABLE IF NOT EXISTS market_tick (
  ts TIMESTAMP,
  security_id LONG,
  symbol SYMBOL,
  price DOUBLE,
  qty LONG
) TIMESTAMP(ts) PARTITION BY DAY WAL;
```

**Retention.** A trading day of DSE ticks is small, but this grows forever if nobody thinks about it.
Set up a **partition-drop policy** (e.g. keep 90 days, or whatever I tell you when you ask) and show
me the command/cron that enforces it. An unbounded time-series on a shared server is a future outage.

**Verify it, don't just start it:**
- Write a tick over ILP with `nc`/`socat` and read it back with a `SAMPLE BY` query over HTTP.
- Reboot the box (or `systemctl restart`) and prove the data and the service both come back.

---

## Service 2 — Valkey (the hot store) — **read the warning section, it is the important part**

**What it is for today.** The live market picture: latest quotes and depth ladders, served from memory
instead of hammering MySQL, and shared across OMS instances.

Key patterns the OMS already uses (keep these exact):
- `oms:quote:{securityId}` — a **hash**: `ltp`, `bid`, `ask`, `volume`, `trades`. No TTL.
- `oms:depth:{securityId}` — a **JSON string** with a **30-second TTL**, so a stale ladder expires
  rather than being served as if it were current.
- A pub/sub channel for fanning ticks out to every OMS instance.

**Install it as a service:**
- Install **Valkey** (not Redis — Valkey is the BSD-licensed fork we've standardised on) from the
  distro repo or the official build. Pin and record the version.
- Dedicated `valkey` system user, config in `/etc/valkey/`, **systemd unit**, `Restart=always`.
- Port **6379**. Same network rule as QuestDB: **bind to `127.0.0.1`** if the OMS is co-located,
  otherwise the private interface only, firewalled to the OMS host.
- **Set `requirepass`** with a strong generated password, and keep `protected-mode yes`. An unsecured
  Redis-protocol port on a network is one of the most reliably exploited things on the internet — and
  "it's only the office LAN" is how that story always starts.
- Rename or disable the dangerous admin commands (`FLUSHALL`, `FLUSHDB`, `CONFIG`, `KEYS`).
- Set `maxmemory` to a real number (not unlimited — it will eat the box), and decide the eviction
  policy deliberately. See the warning below, because **the right answer is not the same for both
  kinds of data we are going to put in here.**

---

## ⚠️ The part that actually needs thinking: Valkey as the limit / credit-control (CCD) cache

We are going to use Valkey for a **second, very different job**: caching reference/lookup data and
**client limit + credit-control data**, so the limit engine can decide whether an order may go to DSE
*before* it is sent — without a database round-trip on every order.

This is not the same as caching a quote, and treating it the same way will lose real money. Design for
these four things explicitly, and tell me how you have handled each:

**1. A cache miss must NEVER mean "no limit".**
The failure mode that ruins a broker is: the limit key was evicted or expired, the limit engine found
nothing, concluded there was no restriction, and let the order through. The rule is **fail-closed**: a
miss means *go and read MySQL*, and if MySQL is unreachable, **reject the order**. Absence of a limit
is never permission. Write this into the code as the default path, not as an error branch.

**2. Limit data must not be evictable.**
`allkeys-lru` is fine for quotes and depth — losing a quote costs nothing, it comes back on the next
tick. It is **wrong for limits**, because the eviction happens silently and exactly when the box is
busiest (i.e. when the market is busiest). Two acceptable designs — pick one and justify it:
   - a **separate Valkey instance/port** for limit data with `maxmemory-policy noeviction`, while
     market data lives in the LRU one; or
   - one instance with `noeviction`, and market-data keys carry TTLs so they clean themselves up.

   Do **not** simply put both in one `allkeys-lru` instance and hope.

**3. Decrementing available credit must be ATOMIC.**
Two orders arriving in the same millisecond must not both read "৳1,000,000 available", both pass, and
both go out. A read-then-write from the application is a race and it *will* fire under load — our
throughput harness does 300+ orders/sec today and we are targeting 8,000/sec.
Use a **Lua script** (Valkey executes it atomically) that checks-and-decrements in one shot, or
`WATCH`/`MULTI`/`EXEC`. Show me the script. This is the single most important line of code in the
limit engine.

**4. Persistence, because this is not just a cache.**
If Valkey holds *live consumed credit* (not merely a copy of a MySQL row), then losing it on restart
loses today's exposure and every client's limit resets to full. Decide and tell me:
   - Is MySQL the source of truth, with Valkey a **rebuildable** cache (then enable AOF anyway, but a
     cold start rebuilds from MySQL — and you must write and test that rebuild path), **or**
   - Is Valkey authoritative for intraday consumption (then `appendonly yes`, `appendfsync everysec`
     at minimum, and we need a documented recovery procedure).

   I want the first option unless you can argue me out of it — a limit engine whose state exists only
   in a cache is a limit engine that has no state.

---

## Deliverables

1. Both services installed, `systemctl enable`d, surviving a reboot — prove it by rebooting.
2. A short `README` in the repo (or `/opt/naztech/README.md` on the box) recording: versions, ports,
   bind addresses, firewall rules, config file locations, the Valkey password location (a secrets file
   with `chmod 600`, **never in git**), how to start/stop/inspect each, and the retention policy.
3. The OMS config block to hand back to me, matching our existing property names:
   ```properties
   tickstore.enabled=true
   tickstore.host=<host>
   tickstore.ilp-port=9009
   tickstore.http-port=9000

   hotstore.enabled=true
   hotstore.host=<host>
   hotstore.port=6379
   hotstore.password=<in the secrets file, not here>
   ```
4. A one-page note on how you resolved the four CCD questions above.

Both services must **degrade, not fail**: if QuestDB is down the OMS must still trade (it falls back
to MySQL for candles), and if Valkey is down the OMS must still trade (it falls back to MySQL for
quotes) — *except* for limit data, which must fail-closed as described. Confirm you have understood
that distinction, because it is the whole point.

Start by asking me the four questions at the top.
