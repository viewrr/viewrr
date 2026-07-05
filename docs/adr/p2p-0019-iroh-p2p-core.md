# 0019 — P2P core = Iroh (native, dial-by-Ed25519-key); core Endpoint only, segment transfer built over streams

**Status:** Proposed (2026-07-04). **Supersedes the transport + embedding decisions in
`p2p-0002`, `p2p-0003` (bare-worklet JS) and the transport half of `p2p-0014` (HyperDHT
ingress).** Keeps `p2p-0016` (segment = transfer unit), `p2p-0008` (central catalogue),
`p2p-0009` (peer selection), `p2p-0011` (RF/eviction) as the policy layer on top. Spike-gated.

## Context

The prior stack ran the **hyper\*** ecosystem (Hyperswarm/HyperDHT/Hypercore) — a
**Node.js/JS** stack — which forced **`p2p-0003` bare-worklet embedding**: a JS runtime
inside a Compose Multiplatform client (`p2p-0002`, `ADR-0005`). That JS embedding is the
awkward, high-maintenance seam of the whole architecture.

**Iroh** (n0-computer, 1.0 as of June 2026, running on 100k+ devices) is a native Rust P2P
library — "dial keys, not IPs" — that removes the JS runtime and aligns with three existing
decisions:

1. **Native bindings, no JS runtime** — `iroh-ffi` (uniffi) publishes a **Kotlin/JVM+Android**
   artifact (`computer.iroh:iroh`) **and** a **Swift** xcframework (iOS/macOS) from one Rust
   core. **Not a single KMP artifact** — they are separate per-language bindings, wired in
   Compose MP via **expect/actual** (Android/desktop → Kotlin binding, iOS → Swift binding).
   Still **kills the bare-worklet JS embedding**; same FFI pattern accepted for PowerSync
   (`p2p-0018`). **Caveat (see Decision §2):** the FFI exposes **core Endpoint only** — the
   higher-level protocol crates are not bound.
2. **Dial-by-Ed25519-key** — a peer's node id *is* its account publicKey. viewrr identity is
   already **publicKey = account** (Ed25519 self-custody). One identity, no mapping layer.
3. **QUIC + QNT hole-punching (~90%)** with **connection migration** (Wi-Fi↔cellular
   mid-stream) — a direct win for streaming to mobile clients.

On discovery, Iroh offers **signed-DNS records (default)** *or* an optional **Mainline DHT
(BEP 44)** fully-P2P lookup — so peer discovery need not depend on central infra. **Relays**
are still needed for hole-punch coordination + data fallback, but are **self-hostable** — run
one on the VPS that already serves ingress (`p2p-0014`/`p2p-0015`), preserving neutral infra
(`p2p-0005`). viewrr also doesn't need decentralised *content*-routing anyway: `p2p-0008`
already centralises catalogue + availability, so the job is *dial peer X by key across NAT* —
Iroh's core competency. Bonus: Iroh's **transport is swappable** (UDP default, but Bluetooth
/ Tor / Nym) — Bluetooth could later serve **same-room device-pool** transfer (`p2p-0011`)
with no internet.

## Decision

1. **P2P core = Iroh.** Replace hyper\* and the bare-worklet JS runtime. rust-libp2p rejected
   (its kad-DHT is the feature you don't need; ~70% hole-punch; no polished KMP bindings).
   jvm-libp2p rejected (JVM-only — no iOS path; transport still prototype).
2. **Use the Iroh 1.0 core Endpoint only — the FFI does not bind the protocol crates.**
   `iroh-ffi` mirrors the stabilised 1.0 surface (endpoints, connections, paths, tickets,
   relays, dial-by-key, encrypted byte send/recv). **`iroh-blobs`, `iroh-gossip`,
   `iroh-docs` are explicitly out of scope** (not at 1.0). So on the client you get
   **transport, not content-addressed blob transfer.** The segment layer is built **over**
   the Endpoint:
   The purpose-built library **is `iroh-blobs`** — "a simple request-response protocol based
   on BLAKE3 verified streaming" with range requests (= HLS-segment fetch). It is simply not
   in the mobile FFI (higher protocols out of 1.0 scope; n0's FFI work for them is paused).
   Two ways to consume it:
   - **Option A (chosen):** a **thin** request/response over Iroh byte streams — "give me
     segment by **BLAKE3 hash**" to a **catalogue-known** peer (`p2p-0008` availability,
     `p2p-0009` ranking) — with **integrity from the `bao`/BLAKE3 verified-streaming crate**,
     NOT hand-rolled. Only the request framing is ours; the hard part is a library. Rides
     only **stable** surfaces (iroh 1.0 Endpoint + BLAKE3), tracks nothing pre-1.0. Small
     because `p2p-0016` scopes the unit; not a general DHT/replication system.
   - **Option B (rejected):** wrap `iroh-blobs` itself via uniffi (bind the real library, ~a
     dozen methods). More feature-complete (blob sequences, GC) but chains us to a **paused,
     pre-1.0 FFI we'd maintain alone**. Not worth it for a transfer this narrowly scoped.
   - **libtorrent (BT v2)** was considered — mature, content-addressed, mobile bindings — but
     brings its **own swarm/DHT/transport**, bypassing Iroh and fighting `p2p-0009`. Rejected.
   Availability broadcast / presence (`p2p-0006`) is likewise a thin message over Endpoint
   streams (or via the catalogue), not `iroh-gossip`.
3. **`iroh-docs` is irrelevant regardless** — even if it were bound, viewrr has no P2P
   multiwriter state: the catalogue is server-authoritative (`p2p-0008` ParadeDB) and the
   directory is centralised (`p2p-0013` Ravencloak).
4. **Self-host the Iroh relay + DNS discovery on the VPS** (`p2p-0005` neutral infra,
   co-located per `p2p-0015`). Same role HyperDHT bootstrap played, native tech.
5. **Keep the policy layer as custom code — it is the product, not boilerplate.** Iroh
   erases transport + content-transfer + broadcast (~80% of the mesh layer). What remains is
   thin, viewrr-specific policy that no library replaces:
   - Peer selection (Plus Code proximity + uplink, `p2p-0009`) → an `iroh-blobs` provider-
     ranking hook.
   - RF policy + LRU eviction across the device pool (`p2p-0011`).
   - Prefetch (`p2p-0016`).
   - Self-custody encryption envelope (`p2p-0001`/`p2p-0007`) — encrypt segments client-side
     **before** handing to `iroh-blobs` (QUIC covers transport encryption; at-rest self-
     custody is our layer).
   - Glue: catalogue → "peers {A,B} hold segment X" → rank (`p2p-0009`) → `iroh-blobs` fetch.

## Consequences

- **JS runtime gone** — the bare-worklet embedding (`p2p-0003`) disappears; the client is
  pure Compose MP + one native Rust lib via KMP bindings.
- **One identity** — Ed25519 account key = Iroh node key. No identity bridging.
- **Mobile streaming gains** — ~90% hole-punch + Wi-Fi↔cellular connection migration.
- **Transport/NAT/discovery removed + JS embedding deleted** — dial-by-key, QUIC, hole-
  punching, relay, encrypted streams come from the Iroh core. **But content-addressed
  segment transfer + availability broadcast stay custom** (thin protocol over the Endpoint,
  Decision §2) because the FFI omits iroh-blobs/gossip. Less code removed than a full
  iroh-blobs adoption would give — the segment transport is DIY, though narrowly scoped by
  `p2p-0016`.
- **New self-hosted component** — the Iroh relay on the VPS (replaces HyperDHT bootstrap).
- **Migration cost** — swapping hyper\* → Iroh is real work, but viewrr is POC-stage; cheap
  now, expensive later.
- **Crypto change** — libsodium-in-worklet (`p2p-0007`) is largely displaced: Iroh brings
  Ed25519 keys + QUIC TLS for transport. `p2p-0007`'s at-rest/self-custody crypto (clear-key
  envelope) stays, but the transport crypto stack is now Iroh's. `p2p-0007` needs a revisit.

## Spike gate (before Accepted)

1. Wire the bindings via **expect/actual**: Android/desktop → `computer.iroh:iroh`
   (Kotlin/JVM), iOS → Swift xcframework. Confirm a shared interface over both drops into
   Compose MP. (There is **no** single KMP artifact — this glue is the integration cost.)
2. Dial two devices **by Ed25519 pubkey** across NAT; measure hole-punch success + Wi-Fi↔
   cellular migration on real phones.
3. Prototype the **thin segment protocol over Endpoint streams** (Decision §2 Option A):
   request a segment by BLAKE3 hash from a catalogue-known peer, verify integrity, resume.
   Confirm it stays small — if it balloons, reassess Option B (fork iroh-ffi).
4. Prototype the **availability announce** as a thin Endpoint message (or via the catalogue).
5. Confirm the **self-hosted relay** on the VPS handles discovery without n0's hosted relay.

If (1)–(3) hold → promote to **Accepted** and open the `p2p-0007` crypto revisit.

## References

- `p2p-0002`/`p2p-0003` — Compose client + bare-worklet embedding (**transport/embedding superseded**)
- `p2p-0014` — DHT ingress on VPS (**transport half superseded**; relay replaces HyperDHT bootstrap)
- `p2p-0016` — HLS segment = P2P transfer unit (now `iroh-blobs`)
- `p2p-0008` — central catalogue (why iroh-docs is unnecessary)
- `p2p-0009` — peer selection (iroh-blobs provider ranking)
- `p2p-0011` — RF + LRU (custom policy layer)
- `p2p-0007` — single crypto stack, libsodium (**needs revisit** — Iroh brings transport crypto)
- `p2p-0005`/`p2p-0015` — neutral infra / VPS (self-hosted relay)
- Iroh: https://github.com/n0-computer/iroh · blobs/gossip/docs split: https://www.iroh.computer/blog/iroh-0-28-let-them-have-crates
