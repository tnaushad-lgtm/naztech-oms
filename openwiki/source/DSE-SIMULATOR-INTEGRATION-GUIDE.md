---
type: Reference
title: DSE simulator integration guide
description: Wire-level protocol guide for connecting an OMS to the DSE simulator. Documents SoupBinTCP ITCH transport, FIX 5.0 SP1 order entry, replay semantics, and the message rules used by the live connectivity code.
tags: [reference, dse, simulator, fix, itch]
resource: /DSE-SIMULATOR-INTEGRATION-GUIDE.md
---

# DSE simulator integration guide

This guide is the repository's protocol reference for DSE simulator integration. It explains how an OMS consumes market data over ITCH/SoupBinTCP and sends orders over FIX 5.0 SP1, including the login handshake, sequence replay rules, and message catalog.

## Why it matters

The live connectivity code in the backend mirrors the guide's design:

- first connect requests a full replay from sequence 1
- reconnect resumes from the next expected sequence
- ITCH messages are decoded into order-book state, trades, index values, and news
- FIX is used for outbound order entry and execution reports

## Useful takeaways

- ITCH and FIX are separate connections with different recovery behavior.
- The exchange is treated as the source of truth for market data in live integration modes.
- The message rules in this guide explain why the backend keeps an order-number map, why reference prices are special, and how day replays reconstruct the book.
