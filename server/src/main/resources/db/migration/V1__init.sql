CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- users
CREATE TABLE users (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username        VARCHAR(64)  NOT NULL UNIQUE,
    email           VARCHAR(255) NOT NULL UNIQUE,
    password_hash   TEXT         NOT NULL,
    display_name    VARCHAR(255),
    is_admin        BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX users_username_lower_idx ON users (lower(username));
CREATE INDEX users_email_lower_idx    ON users (lower(email));

-- libraries
CREATE TABLE libraries (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(255) NOT NULL,
    kind        VARCHAR(32)  NOT NULL,           -- 'movies' | 'shows' | 'music'
    root_path   TEXT         NOT NULL UNIQUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- media_items
CREATE TABLE media_items (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    library_id      UUID         NOT NULL REFERENCES libraries(id) ON DELETE CASCADE,
    title           TEXT         NOT NULL,
    original_path   TEXT         NOT NULL UNIQUE,
    hls_path        TEXT,
    duration_secs   INTEGER,
    size_bytes      BIGINT,
    mime_type       VARCHAR(127),
    year            SMALLINT,
    search_vector   TSVECTOR,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX media_items_library_idx  ON media_items (library_id);
CREATE INDEX media_items_search_idx   ON media_items USING GIN (search_vector);

CREATE FUNCTION media_items_tsv_update() RETURNS trigger AS $$
BEGIN
  NEW.search_vector := to_tsvector('simple', coalesce(NEW.title, ''));
  NEW.updated_at := now();
  RETURN NEW;
END
$$ LANGUAGE plpgsql;

CREATE TRIGGER media_items_tsv_trg
BEFORE INSERT OR UPDATE ON media_items
FOR EACH ROW EXECUTE FUNCTION media_items_tsv_update();

-- watch_events (append-only)
CREATE TABLE watch_events (
    id              BIGSERIAL PRIMARY KEY,
    user_id         UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    media_id        UUID        NOT NULL REFERENCES media_items(id) ON DELETE CASCADE,
    position_secs   INTEGER     NOT NULL,
    event_type      VARCHAR(16) NOT NULL,        -- 'start'|'progress'|'pause'|'stop'|'finish'
    session_id      UUID        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX watch_events_user_created_idx       ON watch_events (user_id, created_at DESC);
CREATE INDEX watch_events_user_media_created_idx ON watch_events (user_id, media_id, created_at DESC);

-- downloads
CREATE TABLE downloads (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    media_id    UUID         NOT NULL REFERENCES media_items(id) ON DELETE CASCADE,
    device_id   VARCHAR(128) NOT NULL,
    status      VARCHAR(16)  NOT NULL,            -- 'queued'|'ready'|'expired'
    file_path   TEXT,
    expires_at  TIMESTAMPTZ  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (user_id, media_id, device_id)
);
CREATE INDEX downloads_expires_idx ON downloads (expires_at);

-- party_rooms (authoritative state)
CREATE TABLE party_rooms (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id        UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    media_id        UUID         NOT NULL REFERENCES media_items(id) ON DELETE CASCADE,
    join_code       VARCHAR(8)   NOT NULL UNIQUE,
    position_secs   INTEGER      NOT NULL DEFAULT 0,
    is_playing      BOOLEAN      NOT NULL DEFAULT FALSE,
    last_synced_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    closed_at       TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- party_members
CREATE TABLE party_members (
    room_id     UUID         NOT NULL REFERENCES party_rooms(id) ON DELETE CASCADE,
    user_id     UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    joined_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    left_at     TIMESTAMPTZ,
    PRIMARY KEY (room_id, user_id)
);

-- user_recommendations (pre-computed by Python worker)
CREATE TABLE user_recommendations (
    user_id      UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    media_id     UUID        NOT NULL REFERENCES media_items(id) ON DELETE CASCADE,
    score        REAL        NOT NULL,
    rank         SMALLINT    NOT NULL,
    computed_at  TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_id, media_id)
);
CREATE INDEX user_recommendations_rank_idx ON user_recommendations (user_id, rank);
