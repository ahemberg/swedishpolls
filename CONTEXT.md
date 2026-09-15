# Swedish poll of polls

Vocabulary for Swedish voting-intention polls, historical estimates, and national seat approximations.

## Language

**OTHER**:
Combined support for parties not individually represented in a displayed period. It is not a single party and receives no seat allocation as a group.

**Individual party coverage**:
Representation of a party separately from OTHER, supported by usable polling coverage and sustained material support established in a documented review. Historical coverage can differ from current coverage; a dedicated source column alone does not establish individual coverage.

**Coverage period**:
A supported historical interval with a fixed roster of individually represented parties and a corresponding definition of OTHER. A boundary between coverage periods changes that grouping, not necessarily voter support.

**Roster**:
The parties represented individually in a coverage period, together with the aggregate holding the rest. A roster change requires a recorded owner decision and an effective period; it never varies poll by poll.

**Candidate coverage segment**:
A proposed coverage period whose boundaries come from documented source evidence, before validated fits establish support. Its individual estimates stay unavailable, and it never replaces a validated roster.

**Source observation**:
A party share reported by a poll, preserved with its date and provenance. Its existence alone does not establish a supported individual estimate.

**Modeled component**:
A party or aggregate whose support is estimated jointly with the other components of the voting-intention composition. It is distinct from an individual source observation or a grouping chosen for display.

**Model values**:
The fixed numerical coordinates, transformations, and covariance relationships used by a modeled composition or fitted state. Once attached to a source observation or estimate, they do not change.
_Avoid_: matrix

**Comparable remainder**:
Combined support outside the fixed eight parties S, M, SD, V, C, KD, L and MP. It includes FI in every period, unlike OTHER when FI is individually represented.

**Corrected history**:
A historical estimate using corrected source data available today, rather than a reconstruction of what was known at the time.

**National seat approximation**:
A seat estimate from national party shares using election-era rules, without constituency exceptions. It is distinct from an official election allocation.

**Poll eligibility**:
Whether a source record can contribute to the voting-intention estimate. An archived record can be ineligible, with its exclusion reason retained.

**Source snapshot**:
A complete captured version of the polling source that defines which source records belong together. A newer snapshot may correct or remove records without erasing the older version.

**Current-opinion estimate**:
An estimate of voting intention as of its last fieldwork date. It is not an election forecast.
_Avoid_: prediction, forecast, projection

**House effect**:
A polling institute's persistent deviation from the institute-ensemble reference within an election cycle. It is relative to that ensemble, not a deviation from true opinion.

**Overdispersion**:
Poll-to-poll variation beyond pure sampling variation, treated as inflated observation noise shared across institutes.

**Release gate**:
A pre-registered validation requirement that must pass before an estimation method may be published. A failed gate blocks release unless waived by a recorded owner decision.

**Publication**:
A complete, permanent set of results produced from one source snapshot by one model run. Its documents, image bytes, inputs, parameters and seeds are retained unchanged; a correction produces a new publication rather than an edit.

**Candidate publication**:
A publication being built. It is private until every document and image byte is written and verified, and it is abandoned rather than partially exposed when a check or a write fails.

**Asset version**:
One rendered version of a publication's share image. A renderer change publishes a new version beside the old one and never overwrites published bytes.

**Staleness**:
The notice a current publication carries after a later update failed. The publication itself is unchanged; only the notice is new, and unchanged source input never causes it.

**Publication-time check**:
An automated check run before each new estimate replaces the published one. On failure the last validated estimate stays published with a staleness notice.

**Method era**:
A documented period of a polling series with a particular measurement method. Method eras are distinct from institute or company names: a brand can span a method break, and a method can continue under a new name.

**Custom coalition**:
A visitor-selected combination of parties whose estimated voting-intention shares are summed. The grouping does not imply an agreement between the parties to govern together.

**Preset coalition**:
One of the ten approved party combinations published with every run, named by a fixed key and an explicit membership. A preset is not chosen by a visitor, which is what separates it from a custom coalition. Two presets may differ by a single party: opposition counts V where c_l_mp_s counts L.

**Pairwise comparison**:
The probability that one preset coalition takes more seats than another, counted over the same joint draws as the seat allocation. It cannot be derived from two separate seat intervals, because those carry no information about how the two totals move together.

**Tie outcome**:
How an exact equality between two compared coalitions is counted. A tie is credited to neither side and reported on its own, so the probabilities of a pair sum to one without either side being awarded the draw.

**Allocation rule**:
The election-era seat rules one allocation was made under: the divisors, the threshold, the documented tie order and its source. Rules are recorded per election year rather than applied backwards, so a historical estimate is allocated the way that election allocated.

**Page family**:
One approved page of the site, named independently of the path that reaches it. Each family has one
translated path per language, so a language switch maps an equivalent page rather than prefixing the
current path.

**Filtered poll view**:
One page of a publication's pinned source snapshot, selected by the filter a request declares. The
table, its paging and its download resolve the same filter against the same snapshot, so a
correction that publishes mid-visit cannot change the rows being counted or the file about to be
saved. A filter a request declares but the page cannot read is named and left unapplied, never
guessed.

**Source poll view**:
A view of collected source observations that is available independently of a validated estimate publication. Its observations may be newer than, or corrected since, those used by the latest published estimate.

**Poll marker**:
A graphical representation of one party's reported share in one poll. Its horizontal span represents the interview period, and its vertical position represents the reported percentage.

**Headline date**:
The last day the published estimate covers. It comes from the estimate rather than from the
snapshot, so it can fall before the snapshot's last fieldwork date when the newest poll's midpoint
does. A page never claims an estimate for a day the model did not estimate.

**Basic HTML**:
The complete page a request receives before any script runs, carrying the headline, its date and the
results table. The script replaces this markup rather than hydrating it, so no result and no
accessible label is ever present twice.

**Configured origin**:
The public HTTPS origin every canonical, alternate and image URL is built from. It is deployment
configuration, never the incoming Host header.
