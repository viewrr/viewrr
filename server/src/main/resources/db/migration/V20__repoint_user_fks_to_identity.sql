-- #120 B1 (P2P principal unification): repoint the user-scoped FKs off the legacy `users`
-- table onto `identity_accounts` — the sole auth principal after #150 (publicKey identity)
-- and #151 (parental resolves via identity_accounts). Every one of these tables stores the
-- auth subject id (JWT `sub` = identity_accounts.id) in its user_id/owner_id column, so an
-- identity subject's inserts previously failed the FK to `users` and the feature was orphaned.
--
-- Tables repointed (owner/user column -> identity_accounts.id, ON DELETE CASCADE):
--   watch_events.user_id, downloads.user_id, party_rooms.owner_id, party_members.user_id,
--   user_recommendations.user_id, collections.owner_id
-- (collections.owner_id is the same class of orphaned FK; it lives in CollectionTables.kt and
--  was not in the original 5-table brief, but B1 applies identically — included so the
--  principal unification is complete rather than half-done.)
--
-- ================================ DATA-MIGRATION DECISION — needs-human ========================
-- The old FKs pointed at users(id). Rows written BEFORE #150 (Keycloak/users-era auth) may hold
-- users.id values that do NOT exist in identity_accounts, and there is NO safe automatic mapping
-- from a legacy users.id to an identity_accounts.id (a legacy user may never have registered an
-- Ed25519 keypair). A naive VALIDATED `ADD ... REFERENCES identity_accounts` would therefore FAIL
-- against any such pre-existing row, and we must NOT silently drop that data.
--
-- Chosen handling: add each new FK as `NOT VALID`. Postgres enforces referential integrity for
-- every NEW insert/update (so identity subjects work immediately and correctly) while SKIPPING the
-- validation scan of pre-existing rows. Any legacy rows are preserved untouched.
--
-- FOLLOW-UP (human): once legacy rows are reconciled (migrate to identity ids, or delete the
-- unreachable ones — those principals can no longer authenticate after #150), run for each table:
--     ALTER TABLE <t> VALIDATE CONSTRAINT <t>_<col>_fkey;
-- VALIDATE takes only a SHARE UPDATE EXCLUSIVE lock and promotes the constraint to fully valid.
-- On a fresh/empty deployment these tables have no legacy rows, so NOT VALID is equivalent to a
-- normal FK and the follow-up VALIDATE is a no-op.
-- ==============================================================================================
--
-- Constraint names are the Postgres inline-FK defaults (`<table>_<column>_fkey`, created unnamed in
-- V1/V6); reuse of the same name keeps the schema greppable. DROP ... IF EXISTS mirrors V10's pattern.

ALTER TABLE watch_events DROP CONSTRAINT IF EXISTS watch_events_user_id_fkey;
ALTER TABLE watch_events ADD CONSTRAINT watch_events_user_id_fkey
    FOREIGN KEY (user_id) REFERENCES identity_accounts(id) ON DELETE CASCADE NOT VALID;

ALTER TABLE downloads DROP CONSTRAINT IF EXISTS downloads_user_id_fkey;
ALTER TABLE downloads ADD CONSTRAINT downloads_user_id_fkey
    FOREIGN KEY (user_id) REFERENCES identity_accounts(id) ON DELETE CASCADE NOT VALID;

ALTER TABLE party_rooms DROP CONSTRAINT IF EXISTS party_rooms_owner_id_fkey;
ALTER TABLE party_rooms ADD CONSTRAINT party_rooms_owner_id_fkey
    FOREIGN KEY (owner_id) REFERENCES identity_accounts(id) ON DELETE CASCADE NOT VALID;

ALTER TABLE party_members DROP CONSTRAINT IF EXISTS party_members_user_id_fkey;
ALTER TABLE party_members ADD CONSTRAINT party_members_user_id_fkey
    FOREIGN KEY (user_id) REFERENCES identity_accounts(id) ON DELETE CASCADE NOT VALID;

ALTER TABLE user_recommendations DROP CONSTRAINT IF EXISTS user_recommendations_user_id_fkey;
ALTER TABLE user_recommendations ADD CONSTRAINT user_recommendations_user_id_fkey
    FOREIGN KEY (user_id) REFERENCES identity_accounts(id) ON DELETE CASCADE NOT VALID;

ALTER TABLE collections DROP CONSTRAINT IF EXISTS collections_owner_id_fkey;
ALTER TABLE collections ADD CONSTRAINT collections_owner_id_fkey
    FOREIGN KEY (owner_id) REFERENCES identity_accounts(id) ON DELETE CASCADE NOT VALID;
