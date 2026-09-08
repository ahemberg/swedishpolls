-- Each immutable snapshot owns every row, including excluded rows. No natural-key upserts.
CREATE TABLE poll_snapshot (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source_url text NOT NULL,
    sha256 text NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    raw_csv bytea NOT NULL,
    captured_at timestamptz NOT NULL DEFAULT now(),
    parser_version integer NOT NULL,
    UNIQUE (source_url, sha256)
);

CREATE TABLE snapshot_poll (
    snapshot_id bigint NOT NULL REFERENCES poll_snapshot(id),
    row_number integer NOT NULL CHECK (row_number > 0),
    -- Numeric JSON values retain source precision; raw strings retain source missingness and spelling.
    poll jsonb NOT NULL,
    PRIMARY KEY (snapshot_id, row_number)
);

CREATE TABLE poll_source (
    source_url text PRIMARY KEY,
    active_snapshot_id bigint REFERENCES poll_snapshot(id),
    etag text,
    last_modified text,
    last_successful_check_at timestamptz
);

CREATE VIEW active_source_poll AS
SELECT p.snapshot_id, p.row_number, p.poll
FROM poll_source s JOIN snapshot_poll p ON p.snapshot_id = s.active_snapshot_id;
