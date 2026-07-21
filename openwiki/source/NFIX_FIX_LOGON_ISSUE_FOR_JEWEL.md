---
type: Reference
title: nFIX FIX logon issue note
description: Source note documenting a FIX acceptor logon problem in the nFIX integration path. Useful as evidence that FIX can fail independently of ITCH health when diagnosing exchange connectivity.
tags: [reference, nfix, fix, troubleshooting]
resource: /docs/NFIX_FIX_LOGON_ISSUE_FOR_JEWEL.md
---

# nFIX FIX logon issue note

This note records a FIX logon problem in the nFIX integration path. In the wiki it is useful mostly as diagnostic evidence: a healthy ITCH feed does not imply a healthy FIX session, and operator troubleshooting has to treat those as separate failure domains.

## Why it matters

The connectivity controller reports both FIX and ITCH state, and the recent transport changes make ITCH more resilient to long outages. This note helps explain why that separation exists and why the wiki treats order routing and market data as distinct operational concerns.
