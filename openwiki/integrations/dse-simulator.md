---
type: Playbook
title: Integrate with the DSE simulator (ITCH + FIX)
description: Practical integration playbook describing how to connect an OMS to the DSE simulator using ITCH over SoupBinTCP for market data and FIX 5.0 SP1 over QuickFIX/J for order entry.
tags: [integration, itch, fix, simulator, dse]
---

# Integrate with the DSE simulator (ITCH + FIX)

This page summarizes how to integrate with the bundled DSE simulator by following the protocol semantics in **[DSE Simulator — OMS Integration Guide](/openwiki/source/DSE-SIMULATOR-INTEGRATION-GUIDE.md)**.

## What you connect

- **Market data:** ITCH v2.2 over **SoupBinTCP (TCP)**
- **Order entry / cancels:** **FIX 5.0 SP1** over **QuickFIX/J (TCP)**
- **Optional control:** REST/UI control (for inspection only)

Source: [DSE simulator integration guide](/openwiki/source/DSE-SIMULATOR-INTEGRATION-GUIDE.md).

## Recovery and replay expectations

When the ITCH transport (SoupBinTCP) reconnects, the guide’s semantics are:
- first connect requests **full replay** from sequence `1`
- later reconnects resume from `lastAppliedSeq + 1`

Operationally, this allows the OMS to rebuild books and day totals deterministically.

Source: [DSE simulator integration guide](/openwiki/source/DSE-SIMULATOR-INTEGRATION-GUIDE.md).

## Why FIX ordering matters

The OMS ensures order routing is coupled to persistence by routing FIX messages only after the transaction commits.

This behavior is described in [Exchange connectivity](/openwiki/architecture/exchange-connectivity.md).

## Common integration pitfalls

- Price/quantity scaling: ITCH uses integer prices scaled by decimals from the ITCH directory message; FIX uses human decimal prices.
- Instrument mapping: symbol/order-book id mapping is learned from ITCH `R` (Order Book Directory) messages.

Source: [DSE simulator integration guide](/openwiki/source/DSE-SIMULATOR-INTEGRATION-GUIDE.md).
