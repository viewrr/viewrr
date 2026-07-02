# #121 — Bare/Holepunch worklet decomposition (P2P-ADR 0003)

Status: **plan**. No code lands from this doc; it defines the increment ladder so the
worklet epic ships in additive, default-OFF slices instead of one rip-out.

## The constraint

#121 introduces a **second transport architecture** beside the working Hub/Agent HTTP
core (`cluster/AgentHlsRoutes`, `AgentRawRoutes`, `NodeRegistry`, `AgentBootstrap`) +
Headscale mesh (#70, #79). Ponytail rule for the whole epic:

> Every slice is additive and behind a flag. The HTTP/Headscale serving path stays the
> default and is **not deleted** until the swarm path proves byte-for-byte parity on a real
> deployment. No slice may make today's playback worse.

## The RPC seam (the one hard design decision)

The JVM cannot host Bare in-process. So the P2P core is a **`bare` subprocess** the Hub/node
supervises, spoken to over a **Unix-domain socket** (loopback TCP fallback on platforms
without UDS). Same worklet JS runs in-process on mobile (bare-kit); desktop/server run it as
the subprocess.

- **Trust boundary:** the Ed25519 `secretKey` lives ONLY inside the worklet/subprocess. It is
  derived there from the seed and never crosses the socket into the JVM heap. The JVM sends
  intents ("announce this content_uuid", "fetch these bytes"), never key material.
- **Wire:** newline-delimited JSON-RPC over the socket (request id + method + params). Small,
  debuggable, language-neutral. Revisit only if framing overhead ever measures.
- **Socket is local-only** (0600 UDS in the runtime dir); no network exposure.

Open sub-decisions to settle in slice 1, not now: UDS path/permissions vs loopback port
allocation; supervision/backoff policy; how the seed reaches the subprocess at spawn
(env fd vs stdin one-shot) without JVM ever holding the secretKey.

## Increment ladder (each = one PR, default-OFF)

1. **Subprocess lifecycle + health.** Spawn/supervise the `bare` process, UDS transport,
   `ping`/`pong` RPC, restart-with-backoff. No P2P logic. Pure plumbing behind
   `p2p.worklet.enabled=false`. Ships the seam + a smoke test.
2. **Identity parity.** Worklet derives `HyperDHT.keyPair` from the #120 seed, returns
   `publicKey` over RPC. Assert it equals the server-side identity pubkey for the same seed —
   proves the crypto stacks agree before any transport rides on them.
3. **Announce-only.** For each locally-held `media_copies` row whose title has a
   `content_uuid` (#124), the worklet joins swarm `hash(content_uuid)` as a provider.
   Announce, do not serve. Observable via a debug RPC; no client path changes.
4. **Lookup-only.** JVM asks the worklet to resolve a `content_uuid` → candidate peers.
   Feeds a NEW resolve branch guarded by the flag; the HTTP `resolveCopy` path stays default.
   Read-only; nothing is transferred yet.
5. **Transfer + decrypt.** Byte-range fetch of HLS segments over the swarm connection
   (Hyperdrive/Hyperbee). Content key sealed to pubkey, opened + segments AES-decrypted
   **in the worklet** (this is where #122 lands). Key never crosses the seam. Still flagged.
6. **Parity + cutover.** Per-deployment flag flip once swarm playback matches HTTP on
   latency/integrity metrics. ONLY then retire Agent HTTP serving (#68–#73) — its own PR,
   gated on parity dashboards, reversible by flipping the flag back.

## Cross-repo

The worklet JS is the shared artifact: same source runs as mobile bare-kit (in-process) and
desktop/server subprocess. It needs a versioned home (new package/repo) so all platforms pin
the same build — a mismatched worklet = a partitioned swarm. Decide the home in slice 1.

## What this unblocks

Slices 3–5 are the missing substrate for the rest of the P2P epic: #124 DHT discovery
(needs slice 4), #122 self-custody playback (slice 5), #125 peer selection (rides slice 4's
peer list), #127 storage pool (rides slice 3's announce). Nothing downstream is truly
buildable server-side until the seam (slice 1) exists.
