CREATE TABLE music_tracks (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    library_id    UUID NOT NULL REFERENCES libraries(id) ON DELETE CASCADE,
    title         TEXT NOT NULL,
    artist        TEXT,
    album         TEXT,
    album_artist  TEXT,
    track_number  INTEGER,
    disc_number   INTEGER,
    duration_secs INTEGER,
    original_path TEXT NOT NULL UNIQUE,
    mime_type     VARCHAR(127),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX music_tracks_library_idx ON music_tracks (library_id);
CREATE INDEX music_tracks_album_idx ON music_tracks (album);
