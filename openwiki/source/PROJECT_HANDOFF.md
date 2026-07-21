---
type: Reference
title: Project handoff
description: Continuation guide for the OMS repository. Summarizes setup, runtime, demo roles, constraints, and operational gotchas for a new maintainer or future agent.
tags: [reference, handoff, setup, oms]
resource: /PROJECT_HANDOFF.md
---

# Project handoff

This handoff document is the repository's secondary high-level guide. It captures the runtime layout, first-time setup, demo logins, major UI areas, and known constraints for continuing development.

## What the wiki should take from it

- The system is split into backend, market-data/AI, frontend, and database components.
- The demo focuses on trader, RMS, admin, and investor experiences.
- The implementation intentionally distinguishes real-vs-simulated behavior in market data.
- Several roadmap items are explicitly deferred and should not be confused with current product behavior.

## Relationship to the README

Use this page as a practical continuation note and the README as the top-level product statement. When the two differ, prefer current source evidence from the backend pages for operational behavior and treat the handoff as a maintainer-facing summary.
