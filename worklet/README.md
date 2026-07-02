# worklet

Shared home for the P2P worklet JavaScript (P2P-ADR 0003, #121). The same source is meant to run
in two places: as a `bare` subprocess supervised by the desktop/server JVM (spoken to over
newline-delimited JSON-RPC on stdin/stdout — see `server/src/main/kotlin/worklet/`), and in-process
via bare-kit on mobile. Slice 1 ships only `ping.mjs`, a runtime-agnostic smoke worklet behind the
default-OFF `WORKLET_ENABLED` flag; it carries no P2P/Hyper\*/swarm logic yet. Versioning and
cross-repo distribution (a versioned package so every platform pins the same build — a mismatched
worklet is a partitioned swarm) are deliberately deferred and will be formalized in a later slice
per the #121 plan.

## Slice 2 — identity (#121)

`identity.mjs` derives an Ed25519 self-custody keypair from a 32-byte seed via `hypercore-crypto`
(the stack HyperDHT keys on) and signs the Hub's `REGISTER_MESSAGE`. Signatures made here verify
under the Hub's pure-JDK `Ed25519Verifier` (#135) — proven by
`server/src/test/kotlin/worklet/WorkletIdentityParityTest.kt`. So one mnemonic = one identity across
app auth and the P2P swarm.

- `ping.mjs` now also answers `{"method":"identity","params":{"seed":"<32-byte hex>"}}` →
  `{ publicKey, signature }` (lowercase hex).
- `bare derive.mjs [seedHex]` regenerates the golden fixture pinned in the parity test.

**Frozen contract** (#142 mobile + viewrr-web reproduce byte-for-byte): keyPair seed = the first 32
bytes of the BIP39-512 seed; `hypercore-crypto.keyPair(seed)`; `REGISTER_MESSAGE = "viewrr:register"`.
Golden (all-zero seed) → pubkey `3b6a27bc…59da29`.

Deps are pinned in `package.json`; run `bun install` in this dir before invoking. `node_modules/` is
gitignored. The worklet is Bare-native: stdin/stdout go through `bare-pipe` (fd 0/1) and argv/exit
come off Bare's built-in `Bare` global via the shared `stdio.mjs` shim (no `bare-process` dep), so
run it under `bare`, not `node`.

## Slice 3 — announce (#121)

`topic.mjs` derives the swarm topic a node joins to advertise a piece of content:
`swarmTopic(contentUuid) = hypercore-crypto.hash(16 raw uuid bytes)` → 32-byte topic. FROZEN
(#142/web reproduce): golden `bc592db3-…-681997` → `a4f704e6…df1643`.

`ping.mjs` adds:
- `announce {contentUuids:[hex]}` → joins `hash(content_uuid)` as provider for each (idempotent),
  returns `{ joined }`.
- `swarmStatus` → `{ topics, peers }` debug view.

Server side (`server/.../worklet/`): `AnnounceRepository` lists content_uuids this deployment holds
a copy of; `WorkletAnnouncer` pushes them via the `announce` RPC on start + every
`WORKLET_ANNOUNCE_INTERVAL_MS`. All behind `WORKLET_ENABLED`.

The live Hyperswarm join is **integration-only** — CI covers the topic golden (JS) plus the repo
query and announcer loop (JVM, no network). Announce-only: serving bytes on a connection is slice 5.
