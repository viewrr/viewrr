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

## Slice 5a — self-custody clear-key decrypt (#121 / #122)

`clearkey.mjs` decrypts media segments IN the worklet. The content key never leaves — `setContentKey`
loads it, `decryptSegment` returns plaintext only. A compromised Hub sees what's actively watched,
never the key.

**FROZEN contract** (ingest + #142/web reproduce byte-for-byte):
- cipher = **XChaCha20-Poly1305-IETF** (`sodium-universal`, bare-native, authenticated) — a
  deliberate upgrade from #122's sketched AES-128 (unauthenticated). 32-byte key, 16-byte tag.
- `nonce(segIndex)` = `baseNonce[16] ‖ u32LE(segIndex) ‖ 0x00*4` (24 bytes).
- GOLDEN: key `07*32`, baseNonce `00*16`, seg 0, `"segment-0-plaintext"` →
  `430589af13434a1f3d4bd7497f4c833429a2b04431762d8b3ba50c1dc7d9a874c76ff4`.

`ping.mjs` adds `setContentKey {keyHex, baseNonceHex}` (key held, never echoed) and
`decryptSegment {segIndex, cipherHex}` → `{plaintextHex}`. Server `ClearKeyDecryptor` drives them
(Koin lazy, **no route consumes it** — playback wiring is 5c). ponytail: `setContentKey` takes the
raw key for now; opening a pubkey-**sealed** blob with the worklet secretKey (`crypto_box_seal`, so
the key never travels in the clear) is increment 5b.

Crypto golden is JS-verified (`sodium-universal` runs under node too); CI covers the JVM decryptor
RPC shape. Actual segment transfer over the swarm connection is 5c.

### 5b — pubkey-sealed key (the key never travels in the clear)

`seal.mjs`: the content key is delivered **sealed to the user's identity pubkey** (libsodium
anonymous sealed box over the ed25519→curve25519 identity key) and opened only inside the worklet
with that user's secret key. Replaces 5a's raw `setContentKey`.

- `sealKeyTo(contentKey, ed25519Pub)` (owner/ingest + tests) → 80-byte blob for a 32-byte key.
- `openSealedKey(sealedHex, pub, sk)` (worklet) — round-trip is deterministic (`open(seal(k))===k`)
  even though the blob is not (ephemeral sender key); wrong key / tamper → throws.
- `ping.mjs`: `loadIdentity {seedHex}` (persists the keypair; returns only the public key) then
  `openContentKey {sealedHex, baseNonceHex}` (opens with the identity secret key, sets the content
  key — never echoed). `ClearKeyDecryptor` drives both.

Now neither the content key nor the identity secret key ever crosses the RPC seam. ponytail: the
seed still arrives over the seam in `loadIdentity` (bootstrap); generating it in-worklet so even the
seed never crosses is the identity-custody increment. Segment transfer over the swarm is 5c.
