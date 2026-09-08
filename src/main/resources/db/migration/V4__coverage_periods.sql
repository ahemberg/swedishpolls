-- Coverage periods carry display and model membership only. They never hold source observations.
-- A roster change needs a new row with its own effective period and owner decision, not an edit.
CREATE TABLE coverage_period (
    id text PRIMARY KEY,
    effective_from date NOT NULL,
    -- An open-ended period ends when a later owner decision starts its successor.
    effective_to date,
    roster text[] NOT NULL,
    individual_fi boolean NOT NULL,
    -- A candidate segment stays unvalidated until the estimator ticket establishes its fits.
    support_validated boolean NOT NULL,
    decision_url text NOT NULL,
    evidence_url text,
    CHECK (effective_to IS NULL OR effective_to >= effective_from),
    CHECK (roster @> ARRAY['S','M','SD','V','C','KD','L','MP']),
    CHECK (roster <@ ARRAY['S','M','SD','V','C','KD','L','MP','FI']),
    CHECK (individual_fi = ('FI' = ANY (roster))),
    EXCLUDE USING gist (daterange(effective_from, effective_to, '[]') WITH &&) WHERE (support_validated)
);

INSERT INTO coverage_period VALUES
('eight_party_2010', '2010-01-01', NULL, ARRAY['S','M','SD','V','C','KD','L','MP'], false, true,
 'https://github.com/ahemberg/swedishpolls/issues/12#issuecomment-5575789888', NULL),
-- Boundaries are the outermost collection dates of the eligible FI polls in the pinned snapshot.
('fi_candidate_2014_2018', '2014-04-09', '2018-09-07', ARRAY['S','M','SD','V','C','KD','L','MP','FI'], true, false,
 'https://github.com/ahemberg/swedishpolls/issues/12#issuecomment-5575789888',
 'https://github.com/ahemberg/swedishpolls/blob/main/docs/party-rosters.md');
