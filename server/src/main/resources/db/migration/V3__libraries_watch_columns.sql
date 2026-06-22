-- Tracks last successful scan for active-discovery rate limiting (sibling issue #34/#35).
ALTER TABLE libraries ADD COLUMN last_scanned_at TIMESTAMPTZ;

-- Per-library kill switch for the file watcher. Default true so existing rows opt in.
ALTER TABLE libraries ADD COLUMN watch_enabled BOOLEAN NOT NULL DEFAULT TRUE;
