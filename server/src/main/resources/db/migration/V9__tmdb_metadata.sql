-- TMDb-enriched display metadata (movies). Populated by TmdbClient during scan.
ALTER TABLE media_items ADD COLUMN tmdb_id  INT;
ALTER TABLE media_items ADD COLUMN poster   TEXT;
ALTER TABLE media_items ADD COLUMN backdrop TEXT;
ALTER TABLE media_items ADD COLUMN overview TEXT;
