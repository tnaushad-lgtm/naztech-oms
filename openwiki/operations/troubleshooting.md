---
type: Playbook
title: Troubleshooting runbook
description: Operator-focused troubleshooting checklist for FIX/ITCH connectivity and market-data replay issues.
tags: [operations, troubleshooting, fix, itch]
---

# Troubleshooting runbook

This initial runbook focuses on **connectivity seam failures** and **replay gap** behaviors.

## 1) Determine which side is unhealthy

- If **FIX is down** but ITCH is healthy: order routing will stall, but market-watch/depth can still update.
- If **ITCH is down**: the book can freeze even if order entry continues over FIX.

Cross-check with the admin connectivity surface in [Connectivity operations](exchange-connectivity.md).

## 2) Interpret ITCH health correctly

In this OMS, ITCH liveness is based on **heartbeats and recent packets**, not on market volume.

So treat “quiet market” as potentially healthy; treat “missing heartbeats / gaps detected” as unhealthy.

Source: [Connectivity operations](exchange-connectivity.md).

## 3) Replay / restart symptoms

Common symptoms and expected behavior:

- **Outage + reconnect:** the ITCH source resumes using sequence recovery semantics (resume from the next missing sequence) rather than requiring a JVM restart.
- **Full replay:** before a full-day replay, daily aggregates/counters are reset so volume and turnover do not inflate across restarts.

Source: [Exchange connectivity](../architecture/exchange-connectivity.md).

## 4) FIX logon issues (example evidence)

If FIX logon fails with silent disconnects, start by validating the FIX session dictionary compatibility and the remote bind/listen behavior.

Source evidence (specific incident):
- [nFIX logon issue note](/openwiki/source/NFIX_FIX_LOGON_ISSUE_FOR_JEWEL.md)

## 5) What to collect during an incident

When escalating or debugging:

- Admin connectivity status payload (FIX + ITCH sections)
- Whether ITCH showed gaps and whether gaps were recovered
- Tail of FIX logs from the OMS admin log endpoint described in [Connectivity operations](exchange-connectivity.md)

