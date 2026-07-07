-- mesh-hub (docs/pay/1-grpc-contract-service-skeleton.md): local opt-in gate for the HTTP wallet
-- contract (server/src/main/kotlin/pay/WalletRoutes.kt).
--
-- No wallet is provisioned on viewrr-pay unless the account explicitly calls
-- POST /api/pay/wallet/opt-in. This table is the Hub-local record of that gate: a row exists ONLY
-- after SettlementClient.ensureWallet has actually returned an address for the account. GET
-- /api/pay/wallet consults this table first (optedIn=false, no gRPC call) and only reads live
-- balance for accounts that opted in — the app works fully with payments absent.
--
-- wallet_address is cached here (not just the opt-in bit) so the balance read doesn't need a second
-- EnsureWallet round trip merely to re-derive an address viewrr-pay already handed back once.
CREATE TABLE pay_wallets (
    account_id    UUID        PRIMARY KEY REFERENCES identity_accounts(id) ON DELETE CASCADE,
    wallet_address TEXT       NOT NULL,
    opted_in_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
