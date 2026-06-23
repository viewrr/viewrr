---
status: accepted
---

# Split logical Title from physical Copy

Media identity is split into **Title** (a logical work — one movie, episode, or
track, carrying metadata) and **Copy** (a physical file on a specific Node:
`node, path, size, codecs`). One Title may have many Copies across Nodes. The
catalog lists Titles; stream resolution picks an *online* Copy. We did this so the
Hub can answer "does another Node have this same media?" — required for
availability fallback and re-acquisition when a Node goes offline.

## Considered options

- **Single `media_items` keyed by `(nodeId, path)`** (the obvious extension of the
  current schema) — rejected: a row *is* a copy, so the Hub has no way to recognise
  that two files on two Nodes are the same work, breaking other-Node fallback and
  dedup.

## Consequences

- Copy→Title matching needs a key: **tmdbId** primary for movie/TV, tags/MusicBrainz
  for music, content-hash fallback.
- A Title with zero online Copies is shown in the catalog but **disabled** (visible,
  not playable) and triggers re-acquisition — it never blocks the client.
- Hard to reverse: this is the schema keystone the availability, prefetch, and
  acquisition subsystems all build on.
