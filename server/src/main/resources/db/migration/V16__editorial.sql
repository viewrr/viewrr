-- V16: editorial ingest — critic review links + award/festival highlights + TMDB star exposure.
-- Orthogonal to the P2P/Copy work; enriches the existing media_items Title rows.

-- TMDB star rating on the Title, exposed on the media read endpoint. Nullable until a scan or
-- POST /admin/media/backfill-tmdb fills it. REAL (not NUMERIC) keeps kotlinx.serialization simple
-- — a plain Float, no BigDecimal serializer needed. vote_average is 0.0..10.0.
ALTER TABLE media_items ADD COLUMN tmdb_vote_average REAL;
ALTER TABLE media_items ADD COLUMN tmdb_vote_count   INT;

-- Critic review links matched to a Title by the fuzzy matcher. parsed_rating is nullable and
-- usually null: most outlets publish no machine-readable score, so a link + snippet is the honest
-- floor. match_score records the fuzzy-match confidence (0.0..1.0) that tied this item to the Title.
CREATE TABLE movie_reviews (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    media_item_id  UUID        NOT NULL REFERENCES media_items(id) ON DELETE CASCADE,
    outlet         TEXT        NOT NULL,
    url            TEXT        NOT NULL,
    published_at   TIMESTAMPTZ,
    snippet        TEXT,
    parsed_rating  REAL,
    match_score    REAL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (media_item_id, url)          -- idempotent re-ingest: same review link, one row
);
CREATE INDEX movie_reviews_media_idx ON movie_reviews (media_item_id);

-- Award / festival badges rendered on the thumbnail. type is the classifier slug
-- (oscar-nom | globe-nom | festival-win); label is the human badge text.
CREATE TABLE movie_highlights (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    media_item_id  UUID        NOT NULL REFERENCES media_items(id) ON DELETE CASCADE,
    type           TEXT        NOT NULL,
    label          TEXT        NOT NULL,
    source_url     TEXT,
    date           TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (media_item_id, type, label)  -- idempotent re-ingest: one badge per (Title, type, label)
);
CREATE INDEX movie_highlights_media_idx ON movie_highlights (media_item_id);
