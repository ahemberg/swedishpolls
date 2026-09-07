# Statistical model audit and validation options

Research for [Audit the statistical model and define defensible validation options](https://github.com/ahemberg/swedishpolls/issues/3), 7 September 2026. This resolves an investigation, not the product owner's model choice. No model was fitted and no production accuracy or runtime has been measured.

## Proposal being audited

The supplied v1 spec models nine shares in order M, L, C, KD, S, V, MP, SD, OTHER; FI is included in the remainder. It floors parts at 0.0001, closes to one, and uses eight Helmert ilr coordinates. From 2006-09-01, each poll's single published composition is repeated on every fieldwork day, with effective daily sample size `n / days / k`, where k counts concurrent polls from that institute. Observation covariance is `(obsScale / effective_n + tau_h²) I`, default obsScale=1; tau is described as estimated but no estimator is specified.

The state contains eight daily random-walk coordinates (Q=qI) and eight static coordinates for each institute. The q grid has 25 log-spaced points between 1e-6 and 1e-2. The initial opinion mean uses the preceding election or first poll, covariance 10I; independent house priors are N(0,0.05I). After Kalman filtering and RTS smoothing, houses are centered with poll-count weights (equal weights optional), and the mean house vector is added to every opinion state. Daily inverse-ilr of the mean is called a mean; 2,000 Gaussian draws provide marginal 95% intervals. Final-day draws provide national seat allocation and coalition probabilities; house-effect intervals use nonlinear percentage-point differences.

The proposed gates are round-trip tests, 2022 national seats comparison, a fit through 2022-08-31 compared with the election, comparison with the old site's daily table, monotonically increasing likelihood during q tuning, and an interior q optimum. Full recomputation of roughly 7,000 days and 72 state dimensions is described as trivial and targeted under ten seconds.

## Recommendation

Keep the compositional state-space model as a candidate, but do not implement the supplied observation model unchanged. First compare a documented single-observation midpoint approximation with a fieldwork-average likelihood in a disposable statistical reference experiment. Establish publication-time evaluation and public uncertainty wording before turning either into the Java compute path. Below, mathematical deductions are explicitly identified as this audit's derivations; citations establish underlying methods and empirical cautions rather than claim the exact proposed Swedish model was studied in those sources.

## 1. Observation covariance is not identity divided by sample size

Let B be the 9×8 orthonormal contrast matrix, B'B=I and B'1=0; z=B'log(p). This is the ilr construction of [Egozcue et al. (2003)](https://doi.org/10.1023/A:1023818214614), whose authors' [institutional record](https://recerca.udg.edu/es/publications/isometric-logratio-transformations-for-compositional-data-analysi/) identifies the original work. A fixed Helmert orientation is fine; test orthogonality as well as round trips.

**Derivation for this audit:** under simple multinomial sampling, Cov(p̂)=(diag(p)−pp')/n. Applying the Jacobian B'diag(1/p) gives

`Cov(ẑ) ≈ B' diag(1/p) B / n`.

The cross term vanishes because B'1=0. For uniform nine-party shares this is **9I/n**, not I/n. For realistic unequal shares it is neither spherical nor constant across time. Small-party contrasts are noisier and coordinates can be correlated. The [SciPy multinomial reference and source](https://docs.scipy.org/doc/scipy/reference/generated/scipy.stats.multinomial.html) provide an independent runnable multinomial covariance implementation. The companion [derivation check](model-audit-check.py) verifies both matrix identities without dependencies.

Recommended candidate covariance: a design-effect multiplier times this matrix, plus a clearly defined poll-level nonsampling covariance. Plugging reported p into the matrix is itself an approximation; test it against a count-generating reference, particularly around small OTHER and rounded zeros. Weighted percentages are not literal multinomial counts. The reported n may include undecided respondents, so verify the denominator instead of interpreting it automatically as the decided-voter sample. Do not infer a universal design effect from another country's polls.

A floor is a numerical convention, not missing-data imputation: an unreported party is not zero. Reject/quarantine impossible negative remainders; explicitly handle harmless rounding separately. Record floor/closure adjustments and test sensitivity. Inverse ilr should use stable softmax; flooring every inverse draw would truncate the posterior and must not happen accidentally.

## 2. One aggregate cannot supply independent daily observations

**Derivation:** take one scalar poll y over d days and variance v. With tau=0 and k=1, the spec contributes the quadratic likelihood penalty

`Σ_t (y−θ_t)²/(2 d v)`

`= (y−θ̄)²/(2v) + Σ_t (θ_t−θ̄)²/(2 d v)`.

The first term is information about the period average. The second is an invented penalty forcing the daily path flat within fieldwork. Thus n/d preserves precision only for a constant state; it does **not** make daily repetition equivalent to observing a period average. Long polling windows may create artificial smoothness and distort fitted q.

With a poll-level variance tau² added independently to each pseudo-day, the effective variance for a constant state becomes `v + tau²/d`, instead of `v + tau²`. It spuriously makes long-fieldwork polls more reliable with respect to that error. Repeating an aggregate also changes the likelihood normalization, which matters when estimating hyperparameters.

Options to investigate:

| Candidate | Meaning and cost | Recommendation |
|---|---|---|
| One observation at fieldwork midpoint | Approximates opinion over the window by opinion on one day; transparent but may miss sharp movement and long-window effects | Cheapest reference candidate; retain exact fieldwork in chart markers and sensitivity tests |
| One linear observation of average ilr states | `z_i≈Σ_t w_it θ_t + δ_h + e_i`; Gaussian, but needs states spanning a window or sparse batch inference | Better temporal semantics; quantify difference from share-average likelihood |
| One observation of averaged daily share probabilities | `p̄_i=Σ_t w_it inverse_ilr(θ_t+δ_h)` followed by an appropriate sampling likelihood | Most faithful of these when polling samples throughout fieldwork; nonlinear, and weights are often unknown |

Uniform daily weights remain an explicit approximation when interview counts by date are unavailable. `ilr(mean(p))` is not `mean(ilr(p))`. Ordinary same-day Kalman updates alone cannot express a cross-day aggregate likelihood; architecture depends on this decision. Filtering/smoothing recursions assume the stated observation factorization, as set out in [Särkkä, Bayesian Filtering and Smoothing, chapters 4 and 8](https://users.aalto.fi/~ssarkka/pub/cup_book_online_20131111.pdf).

Concurrent fieldwork does not establish respondent overlap. Two independent polls from one house may overlap in dates; tracking releases may share most respondents. k cannot distinguish them. Prefer documented tracking-series metadata, observed shared-sample covariance where available, or a declared de-duplication/thinning sensitivity policy. Do not call k a calibrated correction without evidence.

## 3. House centering is a definition of the pooled reference

**Derivation:** for fixed weights summing to one, let a=Σ_h w_h δ_h. Set θ*=θ+a and δ*_h=δ_h−a. Then θ*+δ*_h=θ+δ_h, so fitted poll predictions are unchanged. But transforming means alone is wrong. For the full state x, use m*=Am and P*=APA'. In particular:

`Var(θ*) = Var(θ) + Var(a) + Cov(θ,a) + Cov(a,θ)`.

Full cross-covariance is needed at every displayed day, not just the marginal θ block. The transformed house covariance is singular because the weighted sum is exactly zero; sample the original joint Gaussian and transform draws rather than require a positive-definite Cholesky of the constrained full covariance.

This map discards a redundant direction; it is an exact linear pushforward of the fitted posterior, **not** proof that the priors equal those of a separately constrained model. Proper priors already influence the decomposition. Poll-count centering defines a poll-weighted institute ensemble, not truth. A prolific tracking institute can dominate that reference, and full-history counts let an old institute's historical output set today's anchor. Require sensitivity to equal weights, current-cycle weights and institute exclusions; make the chosen reference legible to readers.

There is no observation separating a shared all-house bias from true public opinion. Independent house priors and centering cannot learn that common error from poll agreement. [Shirani-Mehr et al. (2018)](https://sites.stat.columbia.edu/gelman/research/published/polling-errors.pdf) found both shared election-level bias and excess variance in US polls. This supports testing those error classes, not transplanting their numerical estimates to Sweden.

Constant house effects from 2006 to 2026 assume unchanged relative behavior through method changes and institute succession. Compare cycle-specific effects with shrinkage or a shorter fitting window before adding a house random walk. Institute-by-party parameters with sparse overlap can be weakly identified; audit which institutes coexist, not merely total row counts. Distinguish reference-relative house effects from election error in headings and tooltips.

## 4. Variance learning and prior meaning remain unspecified

q, obsScale and tau can trade off: short-lived real movement may be labeled noise, and poll noise may become movement. Specify the likelihood, regularization, estimation schedule, and treatment of houses with few observations. An initial benchmark can pool the extra variance; institute-specific variance earns its place through predictive evaluation. Compare pooled, partially pooled and unpooled alternatives on older folds. This is a decision still open, not a request to add all three to production.

A plug-in q/tau fit produces uncertainty **conditional on fitted hyperparameters**. The missing term is visible in the law of total variance:

`Var(θ|y)=E_λ[Var(θ|y,λ)] + Var_λ[E(θ|y,λ)]`.

Assess a low-dimensional hyperparameter mixture, parametric bootstrap that refits parameters, or a Bayesian reference fit. Choose through calibration/cost evidence. A grid mixture needs an explicit prior and grid weights; normalized maximum-likelihood scores alone do not define an innocuous prior, especially on a log grid.

A diffuse 10I ilr prior allows extreme compositions; “previous election anchored” overstates what a prior mean with that variance does. The default starts before the seeded 2010 election, so there is no preceding seeded result. Using the first poll both to initialize and then as an observation is data reuse; either make initialization independent or account for the conditioning. Specify behavior for polls spanning startDate and the first poll occurring later. Later seeded elections are not model observations under the supplied equations. Do not describe the trajectory as election-calibrated simply because results appear as chart dots. Use prior predictive draws to expose these implications; [Stan's predictive-check guidance](https://mc-stan.org/docs/2_38/stan-users-guide/posterior-predictive-checks.html) explains this check.

## 5. Outputs must use coherent joint samples

**Derivation:** inverse_ilr(E[z]) generally differs from E[inverse_ilr(z)]. If the API says posterior mean, average transformed draws (or use justified integration). The old expression is a transformed latent mean, not an arithmetic share mean. Marginal credible endpoints need not sum to 100, but each sampled composition and its mean do.

For each final-day draw, use the same nine-party composition for thresholds, national allocation, every coalition, and pairwise comparisons. Separate allocation of mean vote shares (integer point scenario) from mean of allocations (fractional expected seats). Partywise independent samples or independently sampled coalitions break dependencies. House effects require paired θ and δ from the joint posterior, evaluated as `inverse(θ+δ)−inverse(θ)` after the selected centering, then summarized across draws. Independent daily draws suffice for marginal bands; claims about 30-day change uncertainty or whole paths need joint temporal draws.

At 2,000 independent draws, a probability near 50% has Monte Carlo standard error about 1.12 percentage points (derived as sqrt(p(1−p)/N)). A printed probability of zero is not impossibility. Reuse a stored seed, report sensible precision, and test output stability near 4% and 175 seats. Quantile tails contain only about 50 draws each. Statistical draws for uncertainty are already in scope despite the phrase “no simulation”; distinguish posterior uncertainty sampling from a future election forecasting feature.

“Latest” currently means last interview date, not today: if it is stale, the filter and smoother agree there but that does not remove uncertainty about intervening days. Either display that dated estimate or predict forward with Q and label it. Define this before API and share-card wording.

## 6. The proposed likelihood assertions are invalid

**Derivation:** a scalar Gaussian innovation e with variance S(q)=c+q has log likelihood (up to a constant) `−0.5[log S+e²/S]`. Its derivative changes sign at S=e². Increasing q can help and then hurt; it need not improve monotonically. If e=0 the best q is at the lower boundary. The companion check demonstrates both cases.

Replace the assertions with: finite likelihoods on admissible candidates; selected grid point at least as good as every evaluated point; numerical agreement with a small direct Gaussian reference; reproducible results. Treat a boundary optimum as a diagnostic to investigate/refine the grid and model, not a failed truth assertion. Monotonicity of the *best score seen so far* is bookkeeping, not model validation. Särkkä's [parameter-estimation chapter](https://users.aalto.fi/~ssarkka/pub/cup_book_online_20131111.pdf) derives marginal likelihood optimization; it does not imply monotonicity in a variance parameter.

## 7. Defensible validation design

Separate three questions: is inference implemented correctly, does the model predict observations, and do election-adjacent estimates track election outcomes? A current-opinion estimator is not automatically an election forecast. [Lock and Gelman (2010)](https://sites.stat.columbia.edu/gelman/surveys.course/LockGelman2010.pdf) explicitly distinguish these targets. [Tierney and Volfovsky](https://arxiv.org/abs/2206.14570) study how changing preferences confound polling-error estimates; therefore the 11-day gap from August 31 to September 11 is not pure measurement error.

Recommended protocol:

1. Freeze a versioned data snapshot and an evaluation manifest before model selection. For each historical cutoff include only polls **published** by then, with complete fieldwork already available. A collect_to filter alone leaks later publications. Tune q/tau/weights/exclusions inside each fold; future data must not choose them. Replaying today's corrected archive by publication date is a retrospective reconstruction, not an authentic historical snapshot; disclose unavailable revision history.
2. Use successive older election cycles for development and rolling publication-time origins for next-release predictive checks. Keep 2022 as an untouched final audit only if it has not informed tuning; otherwise label it development and reserve prospective evaluation. There are few Swedish national elections, not dozens of independent party observations per election. The 2026 outcome is not yet available at this research date; register its evaluation in advance.
3. At each cutoff refit from allowed data. Historical smoothed values may use all data *within that cutoff* but never later releases. Archived real-time final-day estimates differ from a modern full-history smoothed curve. Keep both series distinct in tests and UI descriptions.
4. Predict a held-out poll's aggregate including its sampling and nonsampling variance; do not compare its reported share to a latent-state interval and demand 95% coverage. Group correlated tracking releases together in folds. Examine predictive log scores, interval scores, coverage at 50/80/95%, residual autocorrelation, institute/party/fieldwork-length splits, and interval width. Proper-score comparisons prevent winning coverage simply by making intervals uselessly wide.
5. For election-adjacent diagnostics report per-party signed errors, MAE/RMSE and intervals at fixed horizons (for example 28, 14, 7, 1 days), with all admitted publication dates listed. Present the August 31 result as an 11-day-before diagnostic. Election outcomes are not observed true daily opinion at earlier dates. A future forecast would need movement-to-election, turnout and common polling-error assumptions.
6. Compare at least an unadjusted transparent recency-weighted average, the midpoint candidate, and the aggregate-likelihood reference on identical folds. Pre-register the baseline formula and choose any decay/window using development data only. Compare the old site's curve as a diagnostic of method differences, not a target to optimize toward. Never assume non-trending differences prove calibration.
7. Test latent-state recovery and interval coverage with generated known-state data, including overlap and house effects. Use a direct tiny multivariate Gaussian calculation as an independent oracle. [Simulation-based calibration](https://mc-stan.org/docs/2_37/stan-users-guide/simulation-based-calibration.html) checks inference under the assumed generative model; passing it cannot establish that Swedish polling follows that model. For plug-in empirical-Bayes variants, evaluate repeated-data coverage explicitly rather than asserting exact Bayesian SBC behavior.

Proposed release gates for owner review: all algebraic/reference checks pass; no known future-data leakage; a published validation report with baseline comparisons and coverage uncertainty; no unexplained systematic predictive misfit in material groups; documented sensitivity of threshold/majority probabilities to defensible model choices; and reproducible results from archived inputs/parameters. Numerical accuracy/coverage tolerance and acceptable baseline tradeoffs must be decided before final evaluation. There is insufficient evidence here to invent a credible “MAE below X” requirement. Few elections cannot certify 95% national-election coverage or fine-grained majority probabilities.

## 8. Feasibility: plausible, not yet a ten-second promise

**Resource accounting, not a benchmark:** for T=7,000 and m=72, one full covariance history in 64-bit numbers consumes `7000×72²×8 = 290,304,000` bytes (~277 MiB). Predicted and filtered covariance histories alone are ~554 MiB; retaining smoothed matrices makes ~831 MiB before means, object overhead and application memory. A naive dense smoother has O(Tm³) work; `7000×72³ ≈ 2.61 billion` scalar cubic-work units, not a wall-clock estimate. Multiple full histories across 25 grid points must not be retained. Each likelihood evaluation needs only current filter state; smooth only selected/final candidates.

The parallel data audit identifies 14 post-start institutes, giving m=120 if all are included. At that size one 7,000-day covariance history is 806,400,000 bytes (~769 MiB), and three are ~2.25 GiB before overhead. Actual eligibility may change H; size the benchmark from the frozen eligible input, not the original H=8 illustration.

The original spherical model decomposes into eight independent (H+1)-dimensional filters, offering much smaller storage/work. Correcting the observation covariance couples coordinates, so that optimization cannot silently survive the correction. Identity transitions and static house blocks permit savings, but correct numerical results must precede optimizations. Use factorization/solves rather than explicit inverses, and verify symmetry/positive semidefiniteness with a stable covariance update. Extending state for long fieldwork windows increases the cost again.

Daily intervals require 14 million eight-dimensional Gaussian draws and 126 million transformed party shares; materializing all those shares would consume about 1 GB. Stream daywise summaries and keep only needed final/joint quantities. Drawing final seats is cheap relative to full-history inference. A 30-minute ingest period does not itself require a ten-second model run. Measure parse, fit, smooth, draws, persistence and image stages separately on deployment-equivalent CPU/RAM, both cold and warm; consider the ten-second number a budget to validate or revise. No Java or EJML benchmark was run in this research.

## 9. Existing-site evidence and remaining decisions

Direct HTTPS requests failed, but the search tool retrieved the site's first-party text. Its [methodology](https://pollofpolls.se/metod/) describes Bergman–Holmquist compositional LOESS, square-root sample weighting, daily fieldwork points and iterative house-effect adjustment. It inconsistently mentions 41- and 61-day regression spans, so it is not a complete executable reference. Its [August 2014 update](https://pollofpolls.se/ater-en-forandring-av-modellen/) explicitly changed midpoint observations into daily fieldwork points. Its [August 2018 tracking update](https://pollofpolls.se/uppdatering-av-poll-of-polls-med-avseende-pa-tracking-polls/) downweights overlapping same-house observations. These explain the proposed heuristics' provenance; they do not establish a valid independent Gaussian likelihood. Matching the old curve tests neither latent-state truth nor uncertainty calibration. A source-code/snapshot comparison remains necessary to reproduce it exactly.

The parallel [source-data audit](https://github.com/ahemberg/swedishpolls/issues/2) also matters to model eligibility: its findings include missing SD after the proposed start, election-day/exit-poll contamination, and institute/method changes. Include these in the cutoff/eligibility policy before any headline accuracy calculation; ordinary polling and exit polls cannot silently share the same validation universe.

Decisions now ready for their own tickets:

- Choose temporal likelihood: midpoint approximation with measured limitations or fieldwork aggregate model.
- Define the estimated public quantity and wording: pooled decided-voter opinion, reference-relative house effects, dated latest estimate, and conditional seat/coalition scenarios.
- Choose the house reference, fitting horizon/method-era treatment, variance pooling and hyperparameter uncertainty policy after a reference experiment.
- Freeze publication-time validation protocol, benchmarks and acceptance criteria before evaluating the reserved holdout.
- Resolve runtime/memory budget using a representative numerical prototype; choose compute stack afterward if necessary.

Unknowns requiring data evidence: sample denominators/design effects; genuine tracking overlap and interview weighting; archive revisions; stable institute/method identifiers; historical support for all eight named parties. Unknowns requiring an experiment: calibration, useful house complexity, benefit of aggregate likelihood, runtime. No probability accuracy claim should be inferred from this report.
