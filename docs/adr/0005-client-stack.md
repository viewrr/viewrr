---
status: accepted
---

# Client stack: AFinity (Compose Multiplatform) for mobile, Vue + Tailwind for web/TV

viewrr builds **first-party clients** (it does not adopt the Jellyfin API to reuse
third-party clients — see context note below). The chosen stack:

- **Android + iOS** — fork **AFinity** (github.com/MakD/AFinity; Kotlin, Jetpack
  Compose, Material 3, libmpv) and **migrate it to Compose Multiplatform** so one
  codebase serves both platforms. Bugs are fixed during the port.
- **Web + TV** — a new **Vue 3** app styled to the **Apple TV** design language
  (shelves, hero, focus/10-foot for TV), built with **Tailwind CSS + Tailwind Plus**.
- **Design language** — Apple TV across every surface.

## Considered options

- **Native per platform** (Swiftfin/SwiftUI for iOS+tvOS + AFinity for Android) —
  rejected: two mobile codebases. The CMP port keeps mobile to one.
- **Adopt the Jellyfin API server-side** so AFinity/Swiftfin/etc. fork near-drop-in —
  deferred (see ADR/decision to build first-party clients). The cost of that choice
  reappears as the data-layer retarget below.

## Consequences

- **AFinity is Android-only today.** Compose → Compose Multiplatform is real work,
  especially the player (libmpv on iOS) and platform integrations. This is the
  largest unknown in the mobile track.
- **Every fork speaks the Jellyfin API.** AFinity's data layer must be ripped out and
  **retargeted to viewrr's own API**. Same for any web reference. This is the recurring
  cost of not adopting the Jellyfin API; if it proves too large per client, revisit.
- The web client is greenfield Vue (no fork) — full control, but built from scratch on
  the Apple TV design language + Tailwind Plus components.
- Casting (AirPlay/Chromecast) is now first-party work in each client, not inherited
  from a mature client ecosystem.
