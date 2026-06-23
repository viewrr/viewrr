---
status: accepted
---

# Distributed Hub/Node architecture with lazy stream-source transcoding

viewrr moves from a single box (local libraries, eager transcode-everything-on-scan)
to a **Hub + N Nodes** topology: Nodes store raw bytes and run a stateless Agent;
the Hub holds the database, transcodes, and serves. Media is transcoded **lazily**
(on first request, cached) and the Hub's ffmpeg reads the source **directly from the
Node over HTTP** rather than the Node copying the whole file to the Hub first.

## Considered options

- **Copy-then-transcode (A)** — Agent ships the whole file to Hub disk, then ffmpeg
  reads it locally. Rejected: full-copy latency before first frame, and double
  storage (raw + HLS) on the Hub.
- **Transcode on the Node (C)** — rejected: Nodes (e.g. a NAS) are weak on CPU and
  the design keeps them as stateless byte stores.
- **Eager transcode** (current behaviour) — rejected at scale: pulling and
  transcoding an entire NAS up front before anyone watches is untenable.

## Consequences

- The Agent's raw endpoint must support **HTTP range** (MP4 moov seek / direct-play
  remux).
- Time-to-first-frame is bounded by transcode start, not by a full copy.
- Hub stores only HLS output, not raw copies; bounded by an LRU size cap
  (see ADR-0003 / cache decisions).
- Reliability assumes a reasonably stable LAN link to the Node; a mid-stream Node
  drop fails the transcode (acceptable on a trusted LAN).
