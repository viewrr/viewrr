-- V2: Replace Postgres tsvector full-text search with ParadeDB BM25.
-- Requires the pg_search extension to be available on the host Postgres
-- (Postgres.app on port 5433 ships with it; verify via
--  SELECT * FROM pg_available_extensions WHERE name = 'pg_search').

CREATE EXTENSION IF NOT EXISTS pg_search;

-- Tear down the V1 tsvector apparatus on media_items.
DROP TRIGGER IF EXISTS media_items_tsv_trg ON media_items;
DROP FUNCTION IF EXISTS media_items_tsv_update();
DROP INDEX IF EXISTS media_items_search_idx;
ALTER TABLE media_items DROP COLUMN IF EXISTS search_vector;

-- BM25 index. `id` is the UUID primary key (UNIQUE, untokenized) — satisfies
-- ParadeDB's key_field requirements. Title is indexed with the default
-- unicode tokenizer; query via the @@@ operator (pg_search 0.20+).
CREATE INDEX media_items_bm25 ON media_items
USING bm25 (id, title)
WITH (key_field='id');
