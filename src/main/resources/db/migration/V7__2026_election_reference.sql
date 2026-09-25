INSERT INTO election_reference VALUES
('2026-09-13', 2026, 6767429,
 'https://resultat.val.se/data/resultat/val2026/RD_S.json',
 '1998afdc723a83b325c729faf68063c8731e33496fc274bf9fbb80499e0afb08', '2026-09-25',
 'https://resultat.val.se/data/resultat/val2026/RD_S.json');

INSERT INTO election_party_reference
    (election_date, component, votes, official_seats, source_label) VALUES
('2026-09-13', 'S', 1895989, 99, 'Arbetarepartiet-Socialdemokraterna'),
('2026-09-13', 'M', 1343448, 70, 'Moderaterna'),
('2026-09-13', 'SD', 1183248, 62, 'Sverigedemokraterna'),
('2026-09-13', 'V', 568781, 30, 'Vänsterpartiet'),
('2026-09-13', 'C', 475780, 25, 'Centerpartiet'),
('2026-09-13', 'KD', 417490, 22, 'Kristdemokraterna'),
('2026-09-13', 'L', 361187, 19, 'Liberalerna (tidigare Folkpartiet)'),
('2026-09-13', 'MP', 414307, 22, 'Miljöpartiet de gröna'),
('2026-09-13', 'FI', 0, 0, 'Feministiskt initiativ'),
('2026-09-13', 'RESIDUAL', 107199, 0, 'Övriga partier');
