---
type: Reference
title: Repository README
description: Source-backed summary of the repository overview and run instructions. Explains the exchange-hosted OMS scope, runtime stack, demo roles, and the main product claims that anchor the generated wiki.
tags: [reference, README, oms, product]
resource: /README.md
---

# README

The repository README describes the product as a demonstrable exchange-hosted OMS for DSE and CSE. It positions the system as a vertical-slice bid foundation with a pluggable matching gateway, real-time market data, on-prem AI features, and a Next.js/Spring Boot/MySQL architecture.

## Main claims in the README

- The backend is a Spring Boot OMS with order lifecycle, risk, matching simulation, and semantic search.
- The market-data / AI service is a separate Python FastAPI process.
- The frontend is a Next.js terminal-style UI.
- The database is MySQL.
- The demo includes multiple user roles, a trading terminal, RMS controls, and an exchange admin view.

## Why it matters to the wiki

The README is the highest-level product source for the repository. The wiki uses it as the canonical evidence for:

- what the product is trying to demonstrate
- how the three runtime pieces are split
- which demo roles and user-facing flows exist
- which capabilities are intentionally in scope versus roadmap items

## What changed in later source evidence

The README explains the simulator-style architecture and the market-data ingestion story, but the recent source changes add stronger live-venue semantics around ITCH and FIX. For current behavior, prefer the architecture and operations pages in this wiki when the question is about connectivity health, replay recovery, or live-venue routing.
