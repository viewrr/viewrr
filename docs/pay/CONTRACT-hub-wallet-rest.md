
# Hub wallet REST contract (viewrr-pay bridge)

This document details the viewrr Hub wallet REST contract, serving as the read-only, non-custodial opt-in bridge for viewrr-pay. This implementation is currently constrained by a legal review (#9) and is strictly read-only. All balance movements are governed by decision 0001 (remote signer).

## Prerequisites

All endpoints require authentication.

## Endpoints

### 1. POST /api/pay/wallet/opt-in

Performs an idempotent opt-in to the wallet service. No wallet is provisioned until this endpoint is successfully called.

**Successful Response (200 OK):**
Registers the wallet and confirms opt-in status.
```json
{
  "address": "0xLotOfCharactersForEVM",
  "optedIn": true
}
```

### 2. GET /api/pay/wallet

Retrieves the current wallet status and balance. The application remains functional even if the wallet is not opted into.

**Response when Wallet is Opted In (200 OK):**
Returns wallet details, including the balance denominated in base units.
```json
{
  "address": "0xLotOfCharactersForEVM",
  "balanceBaseUnits": "1000000",
  "asset": "USDC",
  "decimals": 6,
  "optedIn": true
}
```

**Response when Wallet is NOT Opted In (200 OK):**
Returns confirmation of the non-opted-in status.
```json
{
  "optedIn": false
}
```

---
***Note on Balances:*** `balanceBaseUnits` must be passed and received as a string representing an integer base unit count (e.g., `1000000`). The standard asset for this service is USDC with 6 decimals.
