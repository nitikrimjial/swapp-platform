-- Approximate coordinates for "near me" search, and trigram indexes for fast substring search.

-- Coordinates are optional and stored rounded (~100 m) for privacy. Both or neither must be set.
ALTER TABLE items
    ADD COLUMN latitude  DOUBLE PRECISION,
    ADD COLUMN longitude DOUBLE PRECISION,
    ADD CONSTRAINT ck_items_coordinates CHECK (
        (latitude IS NULL AND longitude IS NULL)
        OR (latitude IS NOT NULL AND longitude IS NOT NULL
            AND latitude BETWEEN -90 AND 90 AND longitude BETWEEN -180 AND 180));

ALTER TABLE users
    ADD COLUMN latitude  DOUBLE PRECISION,
    ADD COLUMN longitude DOUBLE PRECISION,
    ADD CONSTRAINT ck_users_coordinates CHECK (
        (latitude IS NULL AND longitude IS NULL)
        OR (latitude IS NOT NULL AND longitude IS NOT NULL
            AND latitude BETWEEN -90 AND 90 AND longitude BETWEEN -180 AND 180));

-- Bounding-box prefilter for radius search (only available listings that have a location).
CREATE INDEX idx_items_geo ON items (latitude, longitude) WHERE status = 'AVAILABLE' AND latitude IS NOT NULL;

-- Trigram indexes make "contains" searches (LIKE '%term%') use an index instead of scanning every row.
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX idx_items_title_trgm ON items USING gin (lower(title) gin_trgm_ops);
CREATE INDEX idx_items_description_trgm ON items USING gin (lower(description) gin_trgm_ops);
CREATE INDEX idx_items_location_trgm ON items USING gin (lower(location) gin_trgm_ops);
