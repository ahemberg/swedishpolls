-- Reference outcomes never enter poll_snapshot or snapshot_poll.
-- Rule years refer to the election being approximated, not the last election held.
CREATE TABLE national_allocation_rule (
    election_year integer PRIMARY KEY,
    seats integer NOT NULL CHECK (seats = 349),
    national_threshold_percent numeric NOT NULL CHECK (national_threshold_percent = 4),
    threshold_inclusive boolean NOT NULL CHECK (threshold_inclusive),
    first_divisor numeric NOT NULL CHECK (first_divisor IN (1.4, 1.2)),
    subsequent_divisor_formula text NOT NULL CHECK (subsequent_divisor_formula = '2 * seats_already_allocated + 1'),
    tie_order text[] NOT NULL CHECK (tie_order = ARRAY['S','M','SD','V','C','KD','L','MP','FI']),
    other_receives_seats boolean NOT NULL CHECK (NOT other_receives_seats),
    constituency_exceptions_included boolean NOT NULL CHECK (NOT constituency_exceptions_included),
    official_tie_rule text NOT NULL CHECK (official_tie_rule = 'lottery'),
    source_url text NOT NULL
);

INSERT INTO national_allocation_rule
SELECT year, 349, 4, true, divisor, '2 * seats_already_allocated + 1',
       ARRAY['S','M','SD','V','C','KD','L','MP','FI'], false, false, 'lottery',
       'https://www.val.se/download/18.162047b519a91d05331183a9/1761747515752/manual-mandatfordelning-val-v785-05.pdf'
FROM (VALUES (2010, 1.4), (2014, 1.4), (2018, 1.2), (2022, 1.2), (2026, 1.2)) AS rules(year, divisor);

CREATE TABLE election_reference (
    election_date date PRIMARY KEY,
    election_year integer NOT NULL REFERENCES national_allocation_rule(election_year),
    valid_votes integer NOT NULL CHECK (valid_votes > 0),
    source_url text NOT NULL,
    source_sha256 text NOT NULL CHECK (source_sha256 ~ '^[0-9a-f]{64}$'),
    retrieved_on date NOT NULL,
    official_seats_source_url text NOT NULL,
    CHECK (extract(year FROM election_date) = election_year)
);

CREATE TABLE election_party_reference (
    election_date date NOT NULL REFERENCES election_reference(election_date),
    -- RESIDUAL excludes all nine named parties, including FI. It is not a party.
    component text NOT NULL CHECK (component IN ('S','M','SD','V','C','KD','L','MP','FI','RESIDUAL')),
    votes integer NOT NULL CHECK (votes >= 0),
    official_seats integer NOT NULL CHECK (official_seats BETWEEN 0 AND 349),
    source_label text NOT NULL,
    -- Overrides the election's vote source when a supplementary source is needed.
    vote_source_url text,
    PRIMARY KEY (election_date, component),
    CHECK (component <> 'RESIDUAL' OR official_seats = 0)
);

INSERT INTO election_reference VALUES
('2010-09-19', 2010, 5960408,
 'https://historik.val.se/val/val2010/slutresultat/R/rike/index.html',
 '9c107b43c0af80281746f947b93856e9d3148320e599a12beba4096df2373103', '2026-09-08',
 'https://www.val.se/valresultat-och-statistik/riksdags--region--och-kommunval/tidigare-valresultat'),
('2014-09-14', 2014, 6231573,
 'https://historik.val.se/val/val2014/slutresultat/R/rike/index.html',
 '0afde894066694dfdeb583c539dd0ad2f0fd81893d893410202930632888de57', '2026-09-08',
 'https://www.val.se/valresultat-och-statistik/riksdags--region--och-kommunval/tidigare-valresultat'),
('2018-09-09', 2018, 6476725,
 'https://historik.val.se/val/val2018/slutresultat/R/rike/index.html',
 '3fdc30494a1c3c0889e3fa23f32969354adb92a9d34333257ea5d3c1db45badc', '2026-09-08',
 'https://www.val.se/valresultat-och-statistik/riksdags--region--och-kommunval/tidigare-valresultat'),
('2022-09-11', 2022, 6477970,
 'https://resultat.val.se/data/resultat/val2022/RD_S.json',
 'b7e108ed78b247c65b62ef90fc3a7efed78eb6103476914471f5724978bccf8d', '2026-09-08',
 'https://www.val.se/download/18.162047b519a91d0533112bab/1666619695452/riksdag-val-2022-beslut-med-tva-rattelser-samt-bilagor.pdf');

INSERT INTO election_party_reference (election_date, component, votes, official_seats, source_label) VALUES
('2010-09-19', 'S', 1827497, 112, 'Arbetarepartiet-Socialdemokraterna'),
('2010-09-19', 'M', 1791766, 107, 'Moderata Samlingspartiet'),
('2010-09-19', 'SD', 339610, 20, 'Sverigedemokraterna'),
('2010-09-19', 'V', 334053, 19, 'Vänsterpartiet'),
('2010-09-19', 'C', 390804, 23, 'Centerpartiet'),
('2010-09-19', 'KD', 333696, 19, 'Kristdemokraterna'),
('2010-09-19', 'L', 420524, 24, 'Folkpartiet liberalerna'),
('2010-09-19', 'MP', 437435, 25, 'Miljöpartiet de gröna'),
('2010-09-19', 'FI', 24139, 0, 'Feministiskt initiativ'),
('2010-09-19', 'RESIDUAL', 60884, 0, 'Övriga partier'),
('2014-09-14', 'S', 1932711, 113, 'Arbetarepartiet-Socialdemokraterna'),
('2014-09-14', 'M', 1453517, 84, 'Moderaterna'),
('2014-09-14', 'SD', 801178, 49, 'Sverigedemokraterna'),
('2014-09-14', 'V', 356331, 21, 'Vänsterpartiet'),
('2014-09-14', 'C', 380937, 22, 'Centerpartiet'),
('2014-09-14', 'KD', 284806, 16, 'Kristdemokraterna'),
('2014-09-14', 'L', 337773, 19, 'Folkpartiet liberalerna'),
('2014-09-14', 'MP', 429275, 25, 'Miljöpartiet de gröna'),
('2014-09-14', 'FI', 194719, 0, 'Feministiskt initiativ'),
('2014-09-14', 'RESIDUAL', 60326, 0, 'Övriga partier'),
('2018-09-09', 'S', 1830386, 100, 'Arbetarepartiet-Socialdemokraterna'),
('2018-09-09', 'M', 1284698, 70, 'Moderaterna'),
('2018-09-09', 'SD', 1135627, 62, 'Sverigedemokraterna'),
('2018-09-09', 'V', 518454, 28, 'Vänsterpartiet'),
('2018-09-09', 'C', 557500, 31, 'Centerpartiet'),
('2018-09-09', 'KD', 409478, 22, 'Kristdemokraterna'),
('2018-09-09', 'L', 355546, 20, 'Liberalerna (tidigare Folkpartiet)'),
('2018-09-09', 'MP', 285899, 16, 'Miljöpartiet de gröna'),
('2018-09-09', 'FI', 29665, 0, 'Feministiskt initiativ'),
('2018-09-09', 'RESIDUAL', 69472, 0, 'Övriga anmälda partier'),
('2022-09-11', 'S', 1964474, 107, 'Arbetarepartiet-Socialdemokraterna'),
('2022-09-11', 'M', 1237428, 68, 'Moderaterna'),
('2022-09-11', 'SD', 1330325, 73, 'Sverigedemokraterna'),
('2022-09-11', 'V', 437050, 24, 'Vänsterpartiet'),
('2022-09-11', 'C', 434945, 24, 'Centerpartiet'),
('2022-09-11', 'KD', 345712, 19, 'Kristdemokraterna'),
('2022-09-11', 'L', 298542, 16, 'Liberalerna (tidigare Folkpartiet)'),
('2022-09-11', 'MP', 329242, 18, 'Miljöpartiet de gröna'),
('2022-09-11', 'FI', 3157, 0, 'Feministiskt initiativ'),
('2022-09-11', 'RESIDUAL', 97095, 0, 'Övriga anmälda partier, exklusive Feministiskt initiativ');

-- The 2010 summary groups FI into ÖVR. The 2014 comparison column separates it.
UPDATE election_party_reference
SET vote_source_url = 'https://historik.val.se/val/val2014/slutresultat/R/rike/index.html'
WHERE election_date = '2010-09-19' AND component IN ('FI', 'RESIDUAL');
