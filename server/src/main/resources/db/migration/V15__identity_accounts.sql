-- #120 (P2P-ADR 0001), foundation increment 1: self-custody identity keyed by an
-- Ed25519 public key. Runs ALONGSIDE the existing users/Keycloak auth — nothing here
-- retires Keycloak (that is #112/#114/#115 follow-up). The client mnemonic->keypair flow
-- lives in the mobile/web repos; the Hub only ever sees the public key.
--
-- ponytail: public_key is stored as lowercase hex TEXT, not BYTEA. Ed25519 raw keys are
-- 32 bytes -> 64 hex chars; TEXT keeps the Exposed mirror trivial and the UNIQUE index
-- canonical (callers normalize to lowercase before insert). Switch to BYTEA only if the
-- account count ever makes 2x storage matter — it won't for a self-hosted media Hub.
CREATE TABLE identity_accounts (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    public_key  TEXT        NOT NULL UNIQUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
