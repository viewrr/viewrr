-- Phase 14 (#72): single -> distributed.
-- Every library and media item now belongs to a Node. Pre-existing content is
-- attached to a fixed "local" node so the single-box deployment keeps working.

CREATE TABLE nodes (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name           TEXT         NOT NULL,
    mesh_address   TEXT,                 -- infra plane (Headscale); filled at register (#69)
    client_address TEXT,                 -- client plane (LAN); filled at register (#71)
    last_seen_at   TIMESTAMPTZ,          -- heartbeat (#83)
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- The implicit local node that owns everything that existed before the split.
INSERT INTO nodes (id, name)
VALUES ('00000000-0000-0000-0000-000000000001', 'local');

-- libraries: belong to a node; root_path was globally UNIQUE, now unique per node.
ALTER TABLE libraries ADD COLUMN node_id UUID REFERENCES nodes(id) ON DELETE CASCADE;
UPDATE libraries SET node_id = '00000000-0000-0000-0000-000000000001';
ALTER TABLE libraries ALTER COLUMN node_id SET NOT NULL;
ALTER TABLE libraries DROP CONSTRAINT IF EXISTS libraries_root_path_key;
ALTER TABLE libraries ADD CONSTRAINT libraries_node_root_uq UNIQUE (node_id, root_path);

-- media_items: belong to a node. original_path stays globally UNIQUE for now —
-- only one node exists, so no collision. Per-node path identity lands with the
-- Title/Copy split (#82, ADR-0002).
ALTER TABLE media_items ADD COLUMN node_id UUID REFERENCES nodes(id) ON DELETE CASCADE;
UPDATE media_items SET node_id = '00000000-0000-0000-0000-000000000001';
ALTER TABLE media_items ALTER COLUMN node_id SET NOT NULL;
