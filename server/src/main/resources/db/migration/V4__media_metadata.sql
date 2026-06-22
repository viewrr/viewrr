-- Filename-parsed display metadata (issue #40). `year SMALLINT` already exists from V1.
ALTER TABLE media_items ADD COLUMN clean_title    TEXT;
ALTER TABLE media_items ADD COLUMN show_title     TEXT;
ALTER TABLE media_items ADD COLUMN season_number  INT;
ALTER TABLE media_items ADD COLUMN episode_number INT;
