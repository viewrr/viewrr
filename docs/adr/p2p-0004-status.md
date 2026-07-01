---
status: accepted
issue: 123
milestone: P2P / Self-Custody Re-architecture
---

# P2P-0004 status: ownership is permanent, subscription gates acquisition only

Tracks issue #123. Records that the P2P-ADR 0004 ownership invariant is **already
satisfied by the current codebase** — this is a guardrail note, not a code change.

## The invariant (P2P-ADR 0004)

Acquiring a title = **permanent ownership**. A subscription may gate *new*
acquisition, perks, or storage tier, but it MUST NEVER revoke an already-owned
title. Retention / inactivity cleanup is a separate concern from entitlement
revocation and must not be conflated with it.

## Current state — verified 2026-07-01

There is **no revocation-on-lapse logic anywhere**, because there is no
subscription/entitlement/billing concept in the code at all:

- Playback access (`media/PlaybackRoutes.kt` `GET /playback/{id}`) is gated on
  exactly three things: the catalog row exists (`MediaItems`), the title passes
  the caller's parental rating (`isVisible`), and a physical copy is online
  (`db.hasOnlineCopy` → `resolveCopy`). No user↔title entitlement is consulted.
- No `subscription` / `subscriber` / `plan` / `tier` / `billing` / `entitlement`
  table, column, or config key exists (schema `db/Tables.kt`, migrations
  `db/migration/`, `application.yaml`).
- The only `revoke` in the tree is the auth refresh-token endpoint and a Stremio
  API-key `DEL` — neither touches content ownership.

So the invariant holds vacuously: nothing can revoke a title because nothing gates
titles on subscription in the first place.

## Consequence / guardrail

If subscription or tiered billing is added later, it may gate **new acquisition,
perks, or storage tier only**. It MUST NOT introduce a per-title entitlement check
in the playback path or any job that deletes/hides owned titles on lapse. Retention
cleanup, if built, keys off storage/inactivity policy — never off subscription
state — and must leave the ownership record intact.
