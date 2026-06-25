-- #82 (ADR-0002): Title/Copy split. The Copy is the physical layer.
-- media_items stays the logical Title (catalog row) so catalog, search, recs,
-- watch_events and stremio meta keep working unchanged. media_copies adds the
-- physical file layer: one row per actual file on a specific node.
--
-- ponytail: media_items.node_id / original_path / hls_path are intentionally NOT
-- dropped here (compat — playback/transcode still read them as the fallback). A
-- later migration removes the legacy columns once every read path resolves via
-- media_copies (#85: online-aware copy selection).

CREATE TABLE media_copies (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    title_id      UUID         NOT NULL REFERENCES media_items(id) ON DELETE CASCADE,
    node_id       UUID         NOT NULL REFERENCES nodes(id) ON DELETE CASCADE,
    original_path TEXT         NOT NULL,
    size_bytes    BIGINT,
    codecs        TEXT,
    hls_path      TEXT,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- a given file on a given node is exactly one copy (the dedup/upsert key).
    CONSTRAINT media_copies_node_path_uq UNIQUE (node_id, original_path)
);

CREATE INDEX media_copies_title_idx ON media_copies (title_id);
CREATE INDEX media_copies_node_idx  ON media_copies (node_id);

-- Backfill: every existing Title gets exactly one Copy mirroring its current
-- physical fields, so the single-box deployment (today) is byte-identical — each
-- media_items row already carried node_id + original_path + hls_path from V10.
-- codecs left null (V1/V10 never captured a codecs string; mime_type is not a
-- codec list, so we don't synthesise one). size_bytes copied straight over.
INSERT INTO media_copies (title_id, node_id, original_path, size_bytes, hls_path, created_at, updated_at)
SELECT id, node_id, original_path, size_bytes, hls_path, created_at, updated_at
FROM media_items;
