# viewrr-pay — design docs

Design/spec docs for the **viewrr-pay** Go settlement service (p2p-0022), one per open issue in `viewrr/pay`. Each is grounded in the governing `../adr/p2p-00XX` ADR.

## Implementation slices

| # | Doc | Depends on |
|---|-----|-----------|
| 1 | [gRPC contract + service skeleton](./1-grpc-contract-service-skeleton.md) | — |
| 2 | [Seed-derived EVM wallet + L2 client](./2-seed-derived-evm-wallet-l2-client.md) | #1 |
| 3 | [USDC bandwidth payment channels](./3-usdc-bandwidth-payment-channels.md) (p2p-0020) | #1, #2 |
| 4 | [Storage escrow contract client](./4-storage-escrow-contract-client.md) (p2p-0021) | #1, #2, #6 |
| 5 | [Durability backstop integration](./5-durability-backstop-integration.md) (p2p-0022) | #4, #6, #8 |
| 6 | [Proof-of-storage + deal-migration matcher](./6-proof-of-storage-deal-migration-matcher.md) | #4, #5 |

## Decisions (blocking)

| # | Doc |
|---|-----|
| 7 | [Storage pricing model — rent vs one-time](./7-decision-storage-pricing-model.md) |
| 8 | [Backstop network + USDC↔token swap](./8-decision-backstop-network-swap.md) |
| 9 | [Legal review gate before money movement ships](./9-legal-review-money-movement.md) |

**Gate:** #9 blocks shipping #3/#4/#5. Decisions #7/#8 unblock #5/#6 implementation detail.
