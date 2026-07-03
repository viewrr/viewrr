# 0019 — P2P core = Iroh (native, dial-by-Ed25519-key); adopt iroh + iroh-blobs + iroh-gossip, skip iroh-docs

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

1. **Native, official Kotlin + Swift bindings** from one cross-compiled Rust core
   (iroh-ffi) → covers Android JVM **and** iOS native from a single implementation. **Kills
   the bare-worklet JS embedding.** Same FFI pattern already accepted for PowerSync
   (`p2p-0018`).
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
2. **Adopt only the mature Iroh crates** (n0 split Iroh into a core + opt-in protocol crates
   stacked via the Router):
   - **`iroh`** core (1.0) — dial-by-key, QUIC, hole-punching.
   - **`iroh-blobs`** — content-addressed BLAKE3 blobs with verified streaming. This **is**
     the segment transfer/store/integrity/resume/multi-provider layer (`p2p-0016`), replacing
     the bulk of custom mesh code.
   - **`iroh-gossip`** (stable) — topic broadcast for availability announcements, presence,
     and pairing signalling (`p2p-0006`).
3. **Skip `iroh-docs`.** It is the least-mature crate (no 1.0 target, CRDT meta-protocol) —
   and viewrr does not need it: the catalogue is server-authoritative (`p2p-0008` ParadeDB)
   and the directory is centralised (`p2p-0013` Ravencloak). No P2P multiwriter state exists
   to sync. Centralisation removes the dependency on the risky crate.
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
- **~80% of mesh app-layer code removed** — transfer/integrity/discovery handled by
  iroh-blobs + iroh-gossip. Remaining custom code is thin policy (the moat).
- **New self-hosted component** — the Iroh relay on the VPS (replaces HyperDHT bootstrap).
- **Migration cost** — swapping hyper\* → Iroh is real work, but viewrr is POC-stage; cheap
  now, expensive later.
- **Crypto change** — libsodium-in-worklet (`p2p-0007`) is largely displaced: Iroh brings
  Ed25519 keys + QUIC TLS for transport. `p2p-0007`'s at-rest/self-custody crypto (clear-key
  envelope) stays, but the transport crypto stack is now Iroh's. `p2p-0007` needs a revisit.

## Spike gate (before Accepted)

1. Bind **iroh-ffi Kotlin** into a shared KMP module; confirm packaging (KMP artifact vs
   Android lib + separate Swift package) drops cleanly into Compose MP for **both** Android
   and iOS.
2. Dial two devices **by Ed25519 pubkey** across NAT; measure hole-punch success + Wi-Fi↔
   cellular migration on real phones.
3. Transfer one encrypted HLS segment via **iroh-blobs**; verify BLAKE3 integrity + resume.
4. Broadcast an availability announce via **iroh-gossip**.
5. Confirm the **self-hosted relay** on the VPS handles discovery without n0's hosted relay.

If (1) and (2) hold → promote to **Accepted** and open the `p2p-0007` crypto revisit.

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
