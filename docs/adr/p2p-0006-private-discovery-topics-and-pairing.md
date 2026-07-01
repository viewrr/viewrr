# 0006 — Private discovery topics are secret-derived; pairing uses ephemeral secrets

**Status:** Accepted (2026-07-01)

## Context

Several subsystems announced on Hyperswarm topics derived from the **public**
identity:
- Private vault + multi-device sync: `Hyperswarm.join(userPublicKey)`
- Notification mailbox: `hash(publicKey + ':notifications')`

`publicKey` is world-readable (it is in the `@handle → publicKey` registry). So any
outsider who knows a handle can compute these "private" topics — join the private-vault
swarm (confirming device presence, timing activity, attempting connections/DoS) and
observe mailbox activity. This contradicts the `15.5` claim of "no metadata: peers see
a topic hash, not who it belongs to."

## Decision

1. **Private vault / sync topic is secret-derived.** Derive it from a device-shared
   secret (HKDF of `secretKey`, or a dedicated vault-sync key) that only the user's own
   devices hold. Outsiders cannot compute or join it.
2. **Device pairing (Vault Link) uses an ephemeral one-time secret.** The QR carries a
   fresh pairing secret; the new device joins `hash(pairingSecret)`; device 1 sends the
   encrypted vault/`secretKey` over that Noise channel; the pairing topic is torn down
   afterward. The identity key is never the pairing rendezvous.
3. **Mailbox — accept a documented limit for MVP.** Senders must reach a recipient
   knowing only `@handle → publicKey`, so the mailbox topic is derivable from public
   info by design. The false "no metadata" claim is corrected: a targeted observer can
   see mail **timing** (content stays NaCl-sealed). Rotating mailbox topics (published
   in the signed mutable profile) are a post-MVP improvement.

## Consequences

- **Good:** Private vault and pairing are genuinely private — no public-derivable
  rendezvous.
- **Good:** Honest mailbox threat model instead of an overstated one.
- **Cost:** A separate vault-sync key must be derived and carried in device state;
  pairing gains an ephemeral-secret exchange step.
- **Residual:** Mailbox timing metadata is observable by an attacker targeting a known
  `publicKey`. Accepted for MVP; revisit with topic rotation.
