# worklet

Shared home for the P2P worklet JavaScript (P2P-ADR 0003, #121). The same source is meant to run
in two places: as a `bare` subprocess supervised by the desktop/server JVM (spoken to over
newline-delimited JSON-RPC on stdin/stdout — see `server/src/main/kotlin/worklet/`), and in-process
via bare-kit on mobile. Slice 1 ships only `ping.mjs`, a runtime-agnostic smoke worklet behind the
default-OFF `WORKLET_ENABLED` flag; it carries no P2P/Hyper\*/swarm logic yet. Versioning and
cross-repo distribution (a versioned package so every platform pins the same build — a mismatched
worklet is a partitioned swarm) are deliberately deferred and will be formalized in a later slice
per the #121 plan.
