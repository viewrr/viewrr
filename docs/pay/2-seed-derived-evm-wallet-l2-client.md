# Seed-derived EVM wallet + L2 chain client

- **Issue:** viewrr/pay#2
- **Status:** Draft
- **Related ADRs:** p2p-0020 (opt-in USDC L2 payment wallet — primary), p2p-0022 (Go settlement service, gRPC boundary, non-custodial), p2p-0023 (shared monetization invariants), p2p-0024 (BIP39 seed is the recovery floor; wallet + identity share one seed), p2p-0013 (self-custody Ed25519 account key). Builds on viewrr#142 (BIP39→Ed25519 identity).

## Problem & context

viewrr-pay needs a way to hold and move USDC on behalf of an opted-in user without
becoming a custodian. Per `p2p-0020` Decision 2, when a user opts into payments a
wallet is derived from **the same BIP39 seed** as their Ed25519 identity (viewrr#142,
`p2p-0024`), on a **separate HD path**, yielding a distinct secp256k1 EVM address that
is *not linkable* to the identity unless the user publishes the link. One recovery
phrase restores both keys, and the address is importable into MetaMask/Rabby.

This issue is build-order step 2 in `GETTING_STARTED.md`: derive that EVM wallet,
connect to the chosen L2 (Base per `p2p-0020` Decision 3) via an eth client, and read
USDC balance. It is the foundation the payment-channel work (#3) and the escrow client
(#4) both sit on. It handles key material, so the trust boundary is the central design
concern — the non-custodial invariant (`p2p-0023` #1, `p2p-0020` Decision 1) is
non-negotiable.

This slice does **no money movement** — derive, connect, read. No signing of value
transfers ships here (that starts in #3), so it stays behind the legal-review gate (#9)
without blocking on it.

## Goals / Non-goals

**Goals**
- Deterministically derive a secp256k1 EVM keypair + address from a BIP39 seed on a
  BIP44 EVM path (coin type 60), disjoint from the identity's Ed25519 path.
- Connect to an EVM L2 (Base) through a Go eth client; verify chain ID; read an
  account's native and USDC (ERC-20) balances.
- Expose small, testable interfaces (`Signer`, `ChainClient`) that #3/#4 consume.
- Keep key handling non-custodial and secret-safe: keys never logged, never persisted
  to viewrr's central infra, zeroized when possible.

**Non-goals**
- No payment channels, escrow, or any value-moving transaction (that is #3/#4).
- No wallet UI / opt-in flow (that lives in the Kotlin client per `p2p-0020`).
- No seed generation or recovery UX — the seed is owned upstream (viewrr#142/`p2p-0024`);
  this service receives it, it does not mint it.
- No USDC↔SC/FIL swap or treasury float (that is `p2p-0022`/#8).
- No multi-account/multi-wallet management beyond a single derived account per seed.

## Design

### Where the service runs (trust boundary — read first)

The non-custodial invariant only holds if the seed never reaches viewrr-owned central
infrastructure. The design therefore assumes **viewrr-pay runs co-located with the
user's own Hub instance** (the Owner device / node of `p2p-0004`, `p2p-0013`), and the
Hub↔pay gRPC boundary of `p2p-0022` is a **local/loopback** channel, not a call into a
shared server. The seed (or a derived signer) crosses that local boundary only for an
opted-in user; it is held in process memory, optionally in a local OS-protected
keystore, and is never written to logs, telemetry, or any remote store. If a deployment
topology ever puts pay on shared infra, this whole issue's threat model changes — see
Open questions.

### Key derivation

- **Curve:** secp256k1 (EVM), distinct from the identity's Ed25519 — different curves,
  so the two keys are cryptographically independent even from one seed (`p2p-0020`
  Context).
- **Path:** standard BIP44 EVM path `m/44'/60'/0'/0/0` (coin type 60 = Ethereum,
  account/change/index 0 for the primary wallet). The Ed25519 identity uses its own,
  separate derivation (SLIP-0010 / viewrr#142 scheme) — the two paths never collide, so
  the addresses are unlinkable on-chain. Higher indices are reserved for future
  per-purpose addresses but out of scope here.
- **Flow:** `mnemonic → BIP39 seed (PBKDF2) → BIP32 master → BIP44 child → secp256k1
  private key → keccak256(pubkey)[12:] → 0x address`.

Keep this a **pure function** (`seed []byte, index uint32 → key, address`) with no I/O,
so it is exhaustively testable against published BIP44/MetaMask vectors.

### Libraries

- **`github.com/ethereum/go-ethereum`** — the canonical Go stack the ADRs already assume
  (`p2p-0022` rationale: geth/web3 is Go-native). Provides `ethclient` (JSON-RPC dial),
  `crypto` (secp256k1, keccak, address), `types`, `common`, `accounts/abi` for the ERC-20
  `balanceOf` call, and (later) transaction signing for #3/#4.
- **`github.com/tyler-smith/go-bip39`** — mnemonic ↔ seed.
- **BIP32/BIP44 derivation** — `github.com/tyler-smith/go-bip32` (hand-rolled BIP44 path,
  smallest dependency surface) *or* the `go-ethereum-hdwallet` wrapper that yields an
  `accounts.Account` directly. Prefer the minimal-dependency route and cover it with
  vectors; the wrapper is a convenience, not a requirement. **Decide at implementation.**

### Key handling / security

- Seed and private key are `[]byte` / `*ecdsa.PrivateKey` held only in memory; zeroize
  the seed buffer after derivation (`defer` a wipe).
- No `slog`/print path ever receives seed or key bytes; add a lint/test guard.
- At-rest option: go-ethereum's encrypted keystore (scrypt JSON) under a user-supplied
  passphrase, in a gitignored local dir — never the repo, never remote (`GETTING_STARTED`
  invariant 4).
- USDC and RPC endpoints come from **config, never hardcoded** (mainnet vs testnet USDC
  addresses differ; the CLAUDE/PAI path rule and money-code hygiene both apply).

### Interfaces exposed to the rest of pay

```go
// internal/wallet
type Wallet interface {
    Address() common.Address
    Signer                       // signs tx / channel state for #3, #4
}
func Derive(seed []byte, index uint32) (Wallet, error) // pure, deterministic

// internal/chain
type ChainClient interface {
    ChainID(ctx) (*big.Int, error)          // sanity-check configured network
    NativeBalance(ctx, common.Address) (*big.Int, error) // gas balance
    USDCBalance(ctx, common.Address) (*big.Int, error)   // ERC-20 balanceOf, 6 decimals
    // Close()
}
```

`Signer` is the seam #3 uses to sign per-segment channel state and open/close txs;
`ChainClient` is the seam #3/#4 use to read state and (later) broadcast. This issue
implements only the read paths and derivation; value-moving methods are stubbed or
deferred to their owning issues.

## Implementation plan

1. **Prereq — depends on #1** (gRPC skeleton + config + DI). This issue plugs a
   `wallet` and `chain` module into that skeleton; land #1 first.
2. **`internal/wallet` derivation.** Pure BIP39→BIP44→secp256k1 function + `Wallet`/
   `Signer` interfaces. Test-first against known vectors. No network.
3. **Seed/key provider + trust boundary.** Define how the seed reaches the module
   (opt-in gate; local Hub-over-loopback or local encrypted keystore). Enforce
   "no wallet unless opted in" (`p2p-0023` #2). Secret-hygiene guards.
4. **`internal/chain` client.** Config-driven `ethclient.Dial` to Base RPC; `ChainID`
   verification against expected network; `NativeBalance` + `USDCBalance` (ERC-20
   `balanceOf`, 6-decimal formatting).
5. **Wire to gRPC.** If the #1 Hub contract includes wallet-address / balance reads,
   implement those handlers over the two modules; otherwise expose internally for #3.
6. **Hand off to #3.** `Signer` + `ChainClient` become the inputs to the payment-channel
   slice. Gas-funding of a fresh wallet (needs a little ETH on Base for open/close) is
   flagged there, not solved here.

## Open questions & risks

- **Deployment topology / trust boundary (highest risk).** The whole non-custodial
  claim rests on pay running on the user's own device with a local Hub↔pay boundary.
  Confirm this topology explicitly; if pay is ever central, re-derive the threat model
  before any key crosses the wire.
- **How the seed is delivered.** Local Hub passes it over loopback gRPC per opt-in, vs
  pay reads a local encrypted keystore it was given a passphrase for. Pick one; both
  must keep the seed off central infra and out of logs.
- **Exact L2 network.** `p2p-0020` names **Base**, and this doc assumes it. Residual
  choices remain: Base mainnet vs Base Sepolia for dev/test, and whether any final
  network confirmation is still pending. Track under the monetization DECISION issues —
  **#8** covers backstop network + USDC↔token swap (that is the SC/FIL leg, *not* the L2
  wallet), so the user-facing L2 stays Base unless a decision issue supersedes `p2p-0020`.
- **RPC provider.** Self-run Base node vs a hosted RPC (Alchemy/Infura/public). Hosted
  RPC sees the address's read traffic — a minor pseudonymity leak (`p2p-0020` scopes
  pseudonymity to the paying pair; the RPC is a third observer). Config-driven; note it.
- **USDC contract addresses per network** must be config, not constants (mainnet and
  Sepolia differ; 6 decimals both).
- **Derivation library choice** (minimal go-bip32 vs go-ethereum-hdwallet wrapper) —
  decide at implementation; either must pass the same vectors.
- **Key zeroization in Go is best-effort** (GC/copies); document the limitation rather
  than over-claim.

## Verification

- **Derivation vectors (unit).** Given a fixed test mnemonic, the derived address equals
  the address MetaMask/a reference BIP44 tool produces for `m/44'/60'/0'/0/0`.
  Determinism: same seed → same address every run. Isolation: the EVM path and the
  identity's Ed25519 path from one seed yield distinct, unlinkable keys.
- **Secret hygiene (unit/lint).** Assert seed/private-key bytes never appear in any log
  sink; grep the module for accidental `%v` on key types; confirm nothing key-bearing is
  written outside a gitignored keystore path.
- **Opt-in gate (unit).** No wallet is derivable/derived unless the opt-in flag is set;
  the service functions fully with payments absent (`p2p-0023` #2).
- **Chain reads (integration, testnet).** Dial Base Sepolia; assert `ChainID` matches the
  configured network; read `USDCBalance`/`NativeBalance` of a funded test address and
  match the on-chain value (block explorer) with correct 6-decimal formatting.
- **Non-custody assertion.** Review confirms no key material is persisted to or
  transmitted toward any central/remote store; keys live in memory or a local keystore
  only.
