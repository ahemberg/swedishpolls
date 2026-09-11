# Self-hosted fonts

The site loads no third-party font service. These files are served from this origin.

| File | Family | Axes | Subset |
| --- | --- | --- | --- |
| `newsreader-latin.woff2` | Newsreader | weight 400-700 | latin |
| `public-sans-latin.woff2` | Public Sans | weight 400-700 | latin |

Only the `latin` subset is shipped. It covers every character the site writes: the Swedish `å ä ö`
and the accented names that reach the poll table. A character outside it falls back to the reader's
system font rather than pulling a second file, which is the cheaper trade at this vocabulary.

Both families are licensed under the SIL Open Font License 1.1.

- Newsreader, Production Type: <https://github.com/productiontype/Newsreader>
- Public Sans, US General Services Administration: <https://github.com/uswds/public-sans>

The bytes come from the Google Fonts `latin` variable builds, `Newsreader` v26 and `Public Sans`
v21. Replacing a file means replacing the whole subset, never editing one in place.
