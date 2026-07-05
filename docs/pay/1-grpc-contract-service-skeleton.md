# gRPC contract + service skeleton

- **Issue:** viewrr/pay#1
- **Status:** Draft
- **Related ADRs:** [p2p-0023](../adr/p2p-0023-monetization-suite-overview.md) (invariants + through-line), [p2p-0022](../adr/p2p-0022-storage-supply-hybrid-and-go-settlement-service.md) (Go/Kotlin split + gRPC boundary, OQ3), [p2p-0020](../adr/p2p-0020-seeder-bandwidth-payments-opt-in-usdc-channels.md) (bandwidth surface), [p2p-0021](../adr/p2p-0021-storage-marketplace-smart-contract-escrow.md) (storage surface). Supporting: `#142` (BIP39→wallet seed).

## Problem & context

`viewrr-pay` is the Go settlement service — the *only* component that touches chains,
escrow contracts, and decentralized storage networks (p2p-0022 Decision 3). The
Kotlin Hub in the [viewrr](https://github.com/viewrr/viewrr) repo drives every
money/settlement/backstop action by calling this service over **gRPC**; the mesh data
plane (segment serving, device storage pool, replication) stays in Kotlin/worklet and
never enters this repo.

Today the repo is a stub: `cmd/viewrr-pay/main.go` logs and blocks on a signal;
`internal/` is empty; `go.mod` declares only `module github.com/viewrr/pay`, `go 1.26`.
This is build-order step 1 of GETTING_STARTED.md and it **blocks all other slices** —
nothing (wallet, channels, escrow, backstop) can be built or tested until the Hub↔pay
wire contract exists and a server answers it.

The job here is deliberately narrow: **define the proto surface, generate Go stubs,
and wire a stub gRPC server** that returns `Unimplemented`/placeholder responses for
every method. No chain, no wallet, no escrow logic — those are slices 2–6. The issue's
one hard constraint: **agree the proto contract with the Hub side before going deep**,
because the contract is the shared artifact both repos build against.

## Goals / Non-goals

**Goals**

- A versioned proto package (`viewrr.pay.v1`) defining the RPC surface the Hub calls:
  wallet opt-in/balance, bandwidth (quote / open-channel / settle-segment /
  close-channel), storage (open-deal / deal-status).
- Deterministic Go stub generation wired into the Makefile (`make proto`), committed
  generated code, and a `buf`-based (or `protoc`) toolchain pinned for reproducibility.
- A runnable gRPC server in `cmd/viewrr-pay` that registers the service, listens,
  serves health + reflection, and shuts down gracefully — every method returns
  `codes.Unimplemented` for now.
- Money-safe type conventions in the proto (integer base units as strings, never
  floats) so later slices inherit them.
- Enough of a service-boundary sketch that the Hub team can review and ratify.

**Non-goals**

- No wallet derivation, chain client, or USDC calls (slice 2).
- No payment-channel state machine or real settlement (slice 3).
- No escrow contract interaction, proof verification, or migration matcher (slices 4–6).
- No Sia/Filecoin backstop (slice 5).
- Not resolving the economic/network open questions (recurring-rent, backstop choice,
  chain platform) — those gate their own slices, not this one.
- No auth hardening beyond a placeholder interceptor hook (see Open questions).

## Design

### Proto package

One package, `viewrr.pay.v1`, file `proto/viewrr/pay/v1/pay.proto`, wrapping a single
`SettlementService`. Grouped by the two revenue products plus wallet. This sketch is a
**starting point for Hub agreement**, not a frozen contract.

```proto
syntax = "proto3";
package viewrr.pay.v1;
option go_package = "github.com/viewrr/pay/gen/viewrr/pay/v1;payv1";

// Money is always integer base units carried as a decimal string (USDC has 6
// decimals: "1500000" == 1.50 USDC). NEVER a float — this is money code.
message Amount {
  string base_units = 1;   // integer, as string
  string asset = 2;        // "USDC" (users/contributors only; SC/FIL never surfaced)
}

service SettlementService {
  // --- wallet (opt-in; p2p-0020 D2, #142) ---
  rpc EnsureWallet   (EnsureWalletRequest)   returns (WalletInfo);      // idempotent opt-in
  rpc GetWalletInfo  (GetWalletInfoRequest)  returns (WalletInfo);      // address + on-chain balance

  // --- bandwidth payments (p2p-0020) ---
  rpc QuoteSegments  (QuoteSegmentsRequest)  returns (QuoteSegmentsResponse); // USDC/GB per peer
  rpc OpenChannel    (OpenChannelRequest)    returns (Channel);         // open/reuse pairwise channel + max-spend cap
  rpc SettleSegment  (SettleSegmentRequest)  returns (SettleSegmentResponse); // pay-on-verified-delivery
  rpc CloseChannel   (CloseChannelRequest)   returns (Channel);         // close/settle on-chain

  // --- storage marketplace (p2p-0021) ---
  rpc OpenStorageDeal(OpenStorageDealRequest) returns (StorageDeal);    // fund escrow deal
  rpc GetStorageDeal (GetStorageDealRequest)  returns (StorageDeal);    // deal + escrow state
}
```

Message shape sketch (fields firm up during Hub review):

- `EnsureWalletRequest{ account_id }` → `WalletInfo{ address, opted_in, Amount balance }`.
  Wallet is a separate secp256k1 EVM address derived from the account's BIP39 seed on a
  distinct HD path (p2p-0020 D2 / `#142`) — derivation itself is slice 2; the RPC exists
  now returning `Unimplemented`.
- `QuoteSegmentsRequest{ content_id, peer_wallets[] }` → `QuoteSegmentsResponse{ quotes[]{ peer_wallet, Amount price_per_gb } }`.
  Pricing originates in the DHT availability lookup (Hub side, p2p-0020 D4); see Open
  questions on whether this RPC is even owned by pay.
- `OpenChannelRequest{ account_id, counterparty_wallet, Amount max_spend }` → `Channel{ channel_id, state, Amount funded, Amount spent }`.
- `SettleSegmentRequest{ channel_id, segment_hash, Amount price }` → `SettleSegmentResponse{ receipt_id, Amount remaining_cap, bool cap_exhausted }`.
  Release is contingent on a verified segment hash (p2p-0020 D5, invariant 5); the Hub
  passes the already-verified hash, pay records/releases the micropayment.
- `OpenStorageDealRequest{ account_id, uint64 gb, duration, Amount budget }` → `StorageDeal{ deal_id, state, escrow_address }`.
  Escrow is on-chain and auto-splits (p2p-0021 D1/D2); pay never holds funds.
- `GetStorageDealRequest{ deal_id }` → `StorageDeal{ deal_id, state, providers[], Amount escrowed, Amount released }`.

Every settlement-mutating RPC (`OpenChannel`, `SettleSegment`, `CloseChannel`,
`OpenStorageDeal`) carries a client-supplied `idempotency_key` string, because
p2p-0022 OQ3 explicitly flags idempotency of settlement calls as unresolved and money
RPCs must be safe to retry.

### Package layout

```
proto/viewrr/pay/v1/pay.proto      # the contract (source of truth, reviewed with Hub)
buf.yaml, buf.gen.yaml             # pinned codegen toolchain
gen/viewrr/pay/v1/*.pb.go          # generated stubs (committed)
cmd/viewrr-pay/main.go             # wire + serve the gRPC server, graceful shutdown
internal/rpc/                      # SettlementService implementation (stub → Unimplemented)
  server.go                        #   the *server struct, NewServer(), Register()
internal/rpc/server_test.go        # in-process dial + assert each method reachable
```

`internal/<domain>` (wallet, channels, escrow, backstop, proof) stays empty this slice —
the stub `internal/rpc` server holds no business logic and calls into no domain package
yet. This matches the GETTING_STARTED convention: `cmd/viewrr-pay` entrypoint,
`internal/<domain>` for real logic, money-critical code pure + well-tested.

### How it wires into viewrr

- **Client:** the Kotlin Hub (`:server`) generates the *same* proto into its own stubs
  and dials this service. The `.proto` is the shared artifact — it should live
  canonically here and be vendored/mirrored to the Hub, or published to a shared proto
  location both sides pull (decide with Hub; see Open questions).
- **Boundary discipline:** pay exposes only settlement RPCs; it never receives or serves
  mesh/data-plane traffic. The Hub verifies segment hashes and holds the free-tier data
  plane; it calls pay only to move money against those verified facts.
- **Observability/logging:** `log/slog` structured logging, never logging a key/seed
  (invariant 4). A gRPC logging + (placeholder) auth interceptor chain is registered so
  later slices slot real auth in without reshaping `main.go`.

## Implementation plan

1. **Toolchain.** Add `buf.yaml` + `buf.gen.yaml` (protoc-gen-go + protoc-gen-go-grpc,
   versions pinned); add `make proto` (lint + generate) and `make proto-lint`. Depends on: nothing.
2. **Proto draft.** Write `pay.proto` with the surface above. Open a contract-review
   thread with the Hub side and iterate to agreement **before** step 4. Depends on: 1.
   Blocks: everything downstream.
3. **Generate + commit stubs** into `gen/`. Wire generation into CI so drift fails. Depends on: 1, 2.
4. **Stub server.** Implement `internal/rpc.Server` returning `codes.Unimplemented`
   for every method; register grpc health + reflection. Depends on: 3.
5. **Wire `cmd/viewrr-pay`.** Replace the stub `main` body: create listener (addr from
   env/flag), register the server + interceptors, serve, graceful stop on SIGINT/SIGTERM
   (keep the existing signal handling). `make run` now starts a real gRPC server. Depends on: 4.
6. **Tests + docs.** In-process dial test asserting each method is reachable and returns
   `Unimplemented`; reflection-list test; update README/GETTING_STARTED status from "stub"
   to "gRPC skeleton live". Depends on: 5.

Downstream pay issues consume this: **slice 2 (wallet + chain client)** fills
`EnsureWallet`/`GetWalletInfo`; **slice 3 (channels)** fills the bandwidth RPCs; **slice
4 (escrow)** fills the storage RPCs. Those are separate issues and out of scope here.

## Open questions & risks

- **Proto ownership / distribution.** Canonical here + mirrored to Hub, or a shared
  proto module both pull? Needs a Hub-side decision (affects both build pipelines).
- **Hub↔pay auth (p2p-0022 OQ3).** Same-host trusted socket, mTLS, or a shared token?
  Undecided — we register a placeholder interceptor now and land real auth before any
  RPC actually moves money. Do not ship money movement without this resolved.
- **Idempotency semantics (p2p-0022 OQ3).** `idempotency_key` field is reserved, but the
  dedup/retry contract (window, storage, replay response) is unspecified — decide before
  slice 3 wires real settlement.
- **Does `QuoteSegments` belong in pay at all?** p2p-0020 D4 puts price in the DHT
  availability lookup (Hub side). If quoting is purely Hub-local, drop this RPC and keep
  pay settlement-only. Confirm with Hub during contract review.
- **Amount representation.** Integer base units as string chosen to avoid float money
  bugs; confirm the Hub's Kotlin side maps this cleanly (e.g. `BigInteger`) before freeze.
- **Streaming vs unary for `SettleSegment`.** Per-segment micropayments may be
  high-frequency; a client-streaming variant might fit better than unary-per-segment.
  Left unary for the skeleton; revisit when slice 3 has real latency numbers.
- **Contract-review latency risk.** This slice is gated on Hub agreement; the stub server
  can land independently, but the proto should not be treated as frozen until ratified.

## Verification

- **Build/gen:** `make proto` regenerates `gen/` with no diff (committed stubs match the
  proto); `make proto-lint` passes; `make build` produces `bin/viewrr-pay`.
- **Unit/integration:** `make test` runs an in-process gRPC test (`bufconn` or a real
  loopback listener) that dials `SettlementService` and asserts **every** method returns
  `codes.Unimplemented` — proving the surface is registered and reachable.
- **Reflection:** a test lists services via the reflection API and asserts
  `viewrr.pay.v1.SettlementService` plus all method names are present (guards accidental
  surface drift).
- **Runnable check:** `make run` starts the server; `grpcurl -plaintext localhost:<port> list`
  shows the service and `... describe viewrr.pay.v1.SettlementService` shows the methods;
  a `grpcurl` call to any method returns `Unimplemented` (not a crash). Health check
  returns `SERVING`.
- **Sign-off gate:** the proto is reviewed and acknowledged by the Hub side before the
  issue closes — the contract is the deliverable, the stub server just proves it compiles
  and serves.
