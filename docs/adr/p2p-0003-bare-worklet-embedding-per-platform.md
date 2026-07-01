# 0003 — Bare worklet embedding: bare-kit on mobile, subprocess on JVM desktop

**Status:** Accepted (2026-07-01)

## Context

viewrr's entire P2P core is a **Bare worklet written in JavaScript**
(Hyperswarm/Hyperdrive/Hyperbee/Autobase/HyperDHT). Native shells talk to it over a
typed RPC seam (hyperschema). The embedding mechanism is platform-specific:

- Mobile (Android/iOS): `bare-kit` embeds Bare into the native app. Documented.
- Desktop: once the shell became **Compose Multiplatform (JVM)** instead of Electron
  (see `P2P-ADR 0002`), the worklet can no longer run in-process — a JVM cannot host Bare the
  way an Electron/JS runtime could.

Rewriting the Hyper* stack in JVM (no mature impl) or switching to jvm-libp2p (loses
all Hyper* data structures) were both rejected.

## Decision

Run the **same worklet JS on every platform**; only the launch mechanism differs.

- **Mobile:** `bare-kit` in-app (unchanged).
- **Desktop (JVM):** bundle the `bare` runtime binary per-OS, spawn it as a
  **subprocess**, and communicate over a **local socket** (Unix-domain / loopback,
  never TCP-exposed) using the same hyperschema RPC seam.

The JS core and RPC contract stay byte-identical across platforms.

## Consequences

- **Good:** Maximum code reuse — per-platform code is just "how Bare is launched."
- **Good:** RPC seam is the single, uniform integration surface everywhere.
- **Security:** On desktop the `secretKey` lives in the **subprocess**, sent over the
  local socket after biometric unlock. The socket must be local-only; the Bare
  subprocess is the secret-holding trust boundary. Same model as mobile, across a
  process line instead of a thread.
- **Cost:** Ship and version the `bare` binary per desktop OS; manage subprocess
  lifecycle (spawn, health, crash-restart, shutdown-wipe).
