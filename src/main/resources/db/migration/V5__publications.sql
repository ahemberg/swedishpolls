-- A publication is immutable once published. Nothing here is ever updated in place except the
-- current pointer and the staleness it carries, so an old permanent link keeps its own bytes.
CREATE TABLE model_run (
    id text PRIMARY KEY CHECK (id ~ '^run_[0-9]{6}$'),
    snapshot_id bigint NOT NULL REFERENCES poll_snapshot(id),
    protocol_version text NOT NULL,
    seed bigint NOT NULL,
    draws integer NOT NULL CHECK (draws > 0),
    -- The frozen fit parameters, interval levels and coverage rules the run read.
    parameters jsonb NOT NULL,
    code_version text NOT NULL,
    estimator_version text NOT NULL,
    runtime text NOT NULL,
    numerical_library text NOT NULL,
    started_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE publication (
    id text PRIMARY KEY CHECK (id ~ '^pub_[0-9]{8}T[0-9]{6}Z$'),
    run_id text NOT NULL REFERENCES model_run(id),
    snapshot_id bigint NOT NULL REFERENCES poll_snapshot(id),
    -- Three distinct times. Fieldwork is the estimate's date, the check is when the source was
    -- read, and publication is when these bytes became current.
    last_fieldwork_date date NOT NULL,
    source_checked_at timestamptz NOT NULL,
    published_at timestamptz NOT NULL,
    history text NOT NULL CHECK (history = 'corrected'),
    -- A candidate is private until the pointer switches. It never becomes visible on failure.
    state text NOT NULL CHECK (state IN ('candidate', 'published', 'abandoned'))
);

CREATE TABLE publication_document (
    publication_id text NOT NULL REFERENCES publication(id),
    surface text NOT NULL,
    language text NOT NULL CHECK (language IN ('sv', 'en')),
    body jsonb NOT NULL,
    sha256 text NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    PRIMARY KEY (publication_id, surface, language)
);

-- Image bytes live on the durable publication volume; this row is their immutable index.
-- A renderer change adds a version and never replaces an existing row.
CREATE TABLE publication_asset (
    publication_id text NOT NULL REFERENCES publication(id),
    kind text NOT NULL,
    language text NOT NULL CHECK (language IN ('sv', 'en')),
    version integer NOT NULL CHECK (version > 0),
    renderer_version text NOT NULL,
    media_type text NOT NULL,
    byte_count integer NOT NULL CHECK (byte_count > 0),
    sha256 text NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    PRIMARY KEY (publication_id, kind, language, version)
);

CREATE TABLE current_publication (
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    publication_id text NOT NULL REFERENCES publication(id),
    -- A kept publication after a failed update. The prior bytes stay; only this notice changes.
    stale boolean NOT NULL,
    stale_since timestamptz,
    CHECK (stale = (stale_since IS NOT NULL))
);

CREATE TABLE publication_attempt (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    started_at timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz,
    source_checked_at timestamptz,
    outcome text NOT NULL CHECK (outcome IN ('published', 'unchanged', 'blocked', 'failed', 'busy')),
    publication_id text REFERENCES publication(id),
    failure text,
    CHECK ((outcome = 'published') = (publication_id IS NOT NULL)),
    CHECK ((outcome IN ('failed', 'blocked')) = (failure IS NOT NULL))
);
