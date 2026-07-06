# 0025 — Identity key ≠ Iroh node key; per-device transport keys bound by a signed device set

**Status:** Accepted (2026-07-04). **Corrects `p2p-0019`** Decision 2 ("Ed25519 account key
= Iroh node key, no identity bridging") where it collides with the wallet model of
`p2p-0006`/`p2p-0013`. Keeps the wallet model for auth.

## Context

`p2p-0019` (Iroh P2P core) states *"Ed25519 account key = Iroh node key. No identity
bridging."* — assuming one keypair per reachable node. But viewrr's **wallet model**
(`p2p-0006` Vault Link copies the `secretKey`; `p2p-0013` and the `CONTEXT.md` Identity
entry) gives **every one of a user's devices the *same* Ed25519 identity key**.

Compose the two and they conflict:

> shared identity key → one Iroh **node id** for all a user's devices → Iroh dials a
> *node by key*, so it **cannot distinguish a user's phone from their laptop**, cannot
> route a segment to a specific device, and loses per-device presence and QUIC
> connection migration.

Iroh's model is one keypair = one reachable node. A key shared across devices breaks
device-level routing. `p2p-0019`'s "no bridging" line did not account for one-key-many-
devices.

## Decision

1. **Identity key ≠ transport key.** The shared account **Ed25519 identity key** remains
   the sole thing that says *who you are / who owns what* — the wallet model
   (`p2p-0006`/`p2p-0013`) is unchanged for **auth and ownership**. It is **not** used as
   an Iroh node key.
2. **Each device generates its own Iroh node keypair** — a per-device **transport
   identity**, distinct on every device. This is what Iroh dials, migrates, and reports
   presence for.
3. **The account publishes a signed device set.** The account identity key **signs each
   device's node public key** (plus a label + added-at), producing a small, verifiable
   `{account_pubkey → [device_node_key, …]}` record. "Reach user U" resolves to U's
   current device node-keys; any peer verifies a device genuinely belongs to U by
   checking the account signature. This is the *thin bridge* `p2p-0019` said didn't
   exist.
4. **Where the device set lives:** published to the **Ravencloak registry** (the
   directory of `p2p-0013`) **and** announceable over **iroh-gossip**. Because the
   registry is a *directory, not a gate* (`p2p-0013`), the set is cacheable and
   gossip-refreshable — device resolution keeps working if the registry is offline.
5. **Per-device transport revocation for free.** Removing a device from the signed set
   (and re-signing) revokes that device's *transport* reachability **without touching the
   auth key** — a lost phone drops off the mesh while the wallet-model identity survives
   on the user's other devices. (This is the device-level revocation the wallet model
   couldn't give at the auth layer, now available at the transport layer.)

## Consequences

- **Wallet model intact for auth** (`p2p-0006`/`p2p-0013` unchanged); Iroh gets the
  per-device keys it needs. Both models coexist via the signed device set.
- **`p2p-0019` Decision 2 is corrected:** identity key is *not* the node key; a thin
  signed-device-set bridge exists. (Flagging for the `p2p-0019` author — it is Proposed.)
- **Presence + migration work** — "which of my devices is online" and Wi-Fi↔cellular
  migration are per-device because transport keys are per-device.
- **`#142` (mobile bootstrap) gains a step:** after deriving the Ed25519 identity, the
  device generates its Iroh node keypair and registers it into the account's device set.
- **`p2p-0006` pairing (Vault Link) gains a step:** enrolling a new device now also adds
  its node key to the signed set (in addition to copying the identity `secretKey`).
- **Interaction with `p2p-0024` recovery:** restoring the identity seed on a fresh device
  yields the auth key, but the device still mints a *new* Iroh node key and must be
  re-added to the device set — recovery restores identity, not transport reachability.

## Open questions

1. **Device-set freshness / revocation propagation** — how quickly a removed device's
   entry expires in caches and gossip (TTL + re-sign cadence).
2. **Device-set size / churn** for users with many devices; whether stale entries are
   pruned automatically.
3. Confirmed by the `p2p-0019` spike: that iroh-ffi exposes per-device node-key
   generation cleanly on both Android and iOS.
