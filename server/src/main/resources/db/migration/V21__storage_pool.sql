-- #127 (P2P-ADR 0011): Multi-device storage pool — schema foundation slice.
--
-- A user's Identity (identity_accounts, P2P-ADR 0001) spans multiple devices
-- (nodes). Each device dedicates >=20% of its free space to a single, user-scoped
-- storage pool that hosts the user's private vault AND publicly-seeded content —
-- there is no central media origin (P2P-ADR 0005 pt1).
--
-- This migration is ADDITIVE and lands ONLY the durable-state foundation:
--   1. storage_pool_members  — which devices joined which user's pool + the
--      device's declared free-space contribution (the >=20% floor).
--   2. pool_content_replicas — per-content replica placement across pooled
--      devices, tagged PRIVATE (RF>=2, never evicted) or PUBLIC (RF=1, LRU-evictable).
--
-- The RF>=2 invariant for private originals, and the loud single-device warning,
-- are enforced in wtf.jobin.storage.StoragePoolRepository / StoragePoolPolicy.
--
-- ponytail: this is a heavier PARALLEL availability model. Central Title/Copy
-- (media_copies, #82) + node heartbeat (#83) still own today's availability path
-- and are untouched here. Device-side space enforcement, LRU eviction, and the
-- encrypted NAS backup tier are deliberately left to later slices of #127.

-- Devices (nodes) that have joined a user's storage pool. The pool is user-scoped:
-- one pool per identity_accounts row, spanning that user's devices.
CREATE TABLE storage_pool_members (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id          UUID        NOT NULL REFERENCES identity_accounts(id) ON DELETE CASCADE,
    node_id           UUID        NOT NULL REFERENCES nodes(id) ON DELETE CASCADE,
    -- Total free space the device reported at (re)declaration time.
    free_space_bytes  BIGINT      NOT NULL,
    -- Bytes actually dedicated to the pool. Must be >= 20% of free_space_bytes
    -- (Decision 1); enforced by the repository at write time.
    contributed_bytes BIGINT      NOT NULL,
    -- Declared floor percentage (Decision 1: minimum 20). Stored (not hard-coded)
    -- so a device can pledge MORE than the floor; the >=20 minimum is a CHECK.
    contribution_pct  SMALLINT    NOT NULL DEFAULT 20,
    joined_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- A given device joins a given user's pool exactly once (the upsert key).
    CONSTRAINT storage_pool_members_owner_node_uq UNIQUE (owner_id, node_id),
    CONSTRAINT storage_pool_members_pct_floor_chk CHECK (contribution_pct >= 20),
    CONSTRAINT storage_pool_members_contrib_nonneg_chk CHECK (contributed_bytes >= 0)
);

CREATE INDEX storage_pool_members_owner_idx ON storage_pool_members (owner_id);
CREATE INDEX storage_pool_members_node_idx  ON storage_pool_members (node_id);

-- One row per replica: content X has a copy living on pooled device N. The
-- replication factor of a content = COUNT(DISTINCT node_id) for that (owner, key).
-- content_key is a free-form content address (e.g. content_uuid hex, or a private
-- vault object id) so this layer stays decoupled from media_items — private vault
-- originals need not be catalog Titles.
CREATE TABLE pool_content_replicas (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id      UUID        NOT NULL REFERENCES identity_accounts(id) ON DELETE CASCADE,
    content_key   TEXT        NOT NULL,
    -- PRIVATE originals: RF>=2, never RF=1, never evicted (Decision 4).
    -- PUBLIC cache:      RF=1, LRU-evictable, re-fetchable from mesh (Decision 4).
    content_class TEXT        NOT NULL,
    node_id       UUID        NOT NULL REFERENCES nodes(id) ON DELETE CASCADE,
    size_bytes    BIGINT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- At most one replica of a given content on a given device.
    CONSTRAINT pool_content_replicas_owner_key_node_uq UNIQUE (owner_id, content_key, node_id),
    CONSTRAINT pool_content_replicas_class_chk CHECK (content_class IN ('PRIVATE', 'PUBLIC'))
);

CREATE INDEX pool_content_replicas_owner_key_idx ON pool_content_replicas (owner_id, content_key);
CREATE INDEX pool_content_replicas_node_idx      ON pool_content_replicas (node_id);
