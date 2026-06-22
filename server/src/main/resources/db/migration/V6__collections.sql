-- User-owned collections (playlists) + ordered items (issue #50).
CREATE TABLE collections (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id   UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name       VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX collections_owner_idx ON collections (owner_id);

CREATE TABLE collection_items (
    collection_id UUID        NOT NULL REFERENCES collections(id) ON DELETE CASCADE,
    media_id      UUID        NOT NULL REFERENCES media_items(id) ON DELETE CASCADE,
    position      INTEGER     NOT NULL DEFAULT 0,
    added_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (collection_id, media_id)
);
