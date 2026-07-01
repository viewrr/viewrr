-- #120 (P2P-ADR 0001), increment 2: cosmetic display name for self-custody identities.
--
-- The Ed25519 public_key stays the ONLY collision-free network identity. display_name is a
-- human-readable petname (Nostr/SSB model): nullable, NOT unique, never authoritative. Two
-- accounts may share "jobin"; the pubkey (and its client-derived handle) disambiguates.
--
-- ponytail: no UNIQUE index on purpose. Globally-unique human usernames would need a
-- coordination/consensus layer (registry, squatting defense) that fights the P2P model —
-- explicitly rejected. Set-once at register for now; an authed rename is a later increment.
ALTER TABLE identity_accounts ADD COLUMN display_name TEXT;
