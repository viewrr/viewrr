-- Phase 14 (#73): per-node auth token. Only the sha256 hash is stored; the raw
-- token is returned once at register and guards Hub<->Agent calls (plaintext LAN).
ALTER TABLE nodes ADD COLUMN token_hash TEXT;
