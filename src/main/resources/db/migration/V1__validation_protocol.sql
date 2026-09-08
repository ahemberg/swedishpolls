-- Resolved development evidence must exist before the final audit or publication.
CREATE TABLE validation_protocol (
    version text PRIMARY KEY,
    document_sha256 text NOT NULL CHECK (document_sha256 ~ '^[0-9a-f]{64}$'),
    evidence_sha256 text CHECK (evidence_sha256 ~ '^[0-9a-f]{64}$'),
    tolerances jsonb,
    frozen_at timestamptz,
    CHECK ((frozen_at IS NULL AND tolerances IS NULL) OR
           (frozen_at IS NOT NULL AND tolerances IS NOT NULL AND evidence_sha256 IS NOT NULL))
);
