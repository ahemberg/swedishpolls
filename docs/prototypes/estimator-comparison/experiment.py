#!/usr/bin/env python3
"""PROTOTYPE - DISPOSABLE. Estimator comparison reference experiment.

Wayfinder ticket #10 (ahemberg/swedishpolls). Not production code.
Compares three candidate poll-of-polls estimators on synthetic known-state
data and publication-aware historical folds:

  recency     - transparent recency+size weighted average (baseline)
  midpoint-ss - random-walk state space in ilr space, one observation per
                poll at its fieldwork midpoint, full delta-method covariance
  window-avg  - same prior, but each poll observes the *average* of the
                latent state over its fieldwork window (GMRF formulation)

Run: python3 experiment.py            (results land in results/)
Deps: numpy, scipy, Python 3.12 stdlib. Seeded; see CONFIG.
"""

import csv
import datetime as dt
import json
import os
import time
import tracemalloc

import numpy as np
import scipy.linalg as sla
import scipy.sparse as sp
import scipy.sparse.linalg as spla

# ----------------------------------------------------------------------------
# CONFIG
# ----------------------------------------------------------------------------
CONFIG = {
    "seed": 20260907,
    "data_file": "polls-pinned.csv",
    "upstream_commit": "f0390c05854d87bbf21db9d31c6431ffa0f07f7e",
    "parties": ["M", "L", "C", "KD", "S", "V", "MP", "SD"],  # + OTHER derived
    "model_start": "2010-01-01",
    "zero_share_floor": 0.0005,  # replace exact-zero components, renormalize
    "kappa_grid": [1.0, 1.5],   # design-effect multiplier on poll covariance
    "q_grid": [1e-5, 3e-5, 1e-4, 3e-4, 1e-3, 3e-3, 1e-2],  # RW var/day (ilr^2)
    "halflife_grid": [14.0, 30.0, 60.0],  # recency half-life days
    "recency_window_days": 120,
    "fold_start": "2018-01-15",
    "fold_end": "2026-07-15",
    "fold_step_days": 60,
    "tune_before": "2021-01-01",  # cutoffs before this tune hyperparameters
    "predict_horizon_days": 35,
    "final_cutoff": "2026-09-07",
    "threshold": 0.04,
    "n_draws": 4000,
    "synth_T": 1500,
    "synth_cutoff": 1400,
    "synth_replicates": 5,
    "synth_poll_gap_mean": 3.0,
    "synth_house_sd_ilr": 0.02,
    "synth_design_effect": 1.5,
    "synth_eval_points": 26,
}

D = 8  # ilr dimension for 9 components
PARTY_NAMES = CONFIG["parties"] + ["OTHER"]
HERE = os.path.dirname(os.path.abspath(__file__))
RESULTS = os.path.join(HERE, "results")

# ilr basis: rows of scipy helmert are orthonormal and orthogonal to ones.
H = sla.helmert(9)  # (8, 9)


def ilr(p):
    return H @ np.log(p)


def ilr_inv(y):
    z = H.T @ y
    e = np.exp(z - z.max(axis=0))
    return e / e.sum(axis=0)


def poll_cov_ilr(p, n, kappa):
    """Delta-method multinomial covariance in ilr: H diag(1/p) H' / n."""
    return kappa * (H * (1.0 / p)) @ H.T / n


# ----------------------------------------------------------------------------
# Data loading (eligibility per the approved data contract, ticket #5)
# ----------------------------------------------------------------------------
def parse_date(s):
    try:
        return dt.date.fromisoformat(s)
    except (ValueError, TypeError):
        return None


def load_polls():
    rows = list(csv.DictReader(open(os.path.join(HERE, CONFIG["data_file"]))))
    start = dt.date.fromisoformat(CONFIG["model_start"])
    polls, excl = [], {}

    def reject(reason):
        excl[reason] = excl.get(reason, 0) + 1

    for r in rows:
        if "VALU" in r["Company"].upper():
            reject("exit_poll")
            continue
        shares = []
        ok = True
        for pty in CONFIG["parties"]:
            try:
                shares.append(float(r[pty]))
            except ValueError:
                ok = False
        if not ok:
            reject("missing_party_share")
            continue
        try:
            n = float(r["n"])
            assert n > 0
        except (ValueError, AssertionError):
            reject("missing_or_bad_n")
            continue
        f, t = parse_date(r["collectPeriodFrom"]), parse_date(r["collectPeriodTo"])
        if not f or not t or t < f:
            reject("bad_collect_dates")
            continue
        pub = parse_date(r["PublDate"])
        if pub is None:
            reject("missing_publdate")
            continue
        if pub < t:
            reject("published_before_collect_end")
            continue
        other = 100.0 - sum(shares)
        if other <= 0:
            reject("nonpositive_other")
            continue
        if f < start:
            reject("before_model_start")
            continue
        p = np.array(shares + [other]) / 100.0
        nz = p < CONFIG["zero_share_floor"]
        if nz.any():
            p = np.maximum(p, CONFIG["zero_share_floor"])
            p = p / p.sum()
            reject("zero_share_floored_kept")  # kept, counted for transparency
        polls.append({
            "company": r["Company"], "p": p, "n": n,
            "from": f, "to": t, "pub": pub,
            "mid": f + dt.timedelta(days=(t - f).days // 2),
            "y": ilr(p),
        })
    polls.sort(key=lambda x: x["pub"])
    return polls, excl


# ----------------------------------------------------------------------------
# Estimator 1: recency+size weighted average (transparent baseline)
# ----------------------------------------------------------------------------
class Recency:
    name = "recency"

    def __init__(self, halflife, kappa):
        self.halflife, self.kappa = halflife, kappa

    def fit(self, polls, cutoff):
        lam = np.log(2) / self.halflife
        win = dt.timedelta(days=CONFIG["recency_window_days"])
        sub = [pl for pl in polls if pl["mid"] >= cutoff - win]
        if len(sub) < 3:
            sub = polls[-5:]
        w = np.array([pl["n"] * np.exp(-lam * (cutoff - pl["mid"]).days)
                      for pl in sub])
        P = np.array([pl["p"] for pl in sub])
        pbar = (w[:, None] * P).sum(axis=0) / w.sum()
        pbar = pbar / pbar.sum()
        n_eff = w.sum() ** 2 / (w ** 2).sum() * np.mean([pl["n"] for pl in sub])
        # latent interval: delta-method at pooled composition and n_eff
        self.mean = ilr(pbar)
        self.cov = poll_cov_ilr(pbar, n_eff, self.kappa)
        # predictive dispersion: weighted between-poll ilr covariance
        Y = np.array([pl["y"] for pl in sub])
        dy = Y - self.mean
        S = (w[:, None, None] * (dy[:, :, None] * dy[:, None, :])).sum(0) / w.sum()
        self.pred_disp = S + self.cov + 1e-10 * np.eye(D)
        return self

    def nowcast(self):
        return self.mean, self.cov

    def predict_poll(self, poll):
        return self.mean, self.pred_disp


# ----------------------------------------------------------------------------
# Estimator 2: midpoint state-space (Kalman filter/smoother, daily RW)
# ----------------------------------------------------------------------------
class MidpointSS:
    name = "midpoint-ss"

    def __init__(self, q, kappa):
        self.q, self.kappa = q, kappa

    def fit(self, polls, cutoff):
        obs = sorted(polls, key=lambda x: x["mid"])
        m = np.zeros(D)
        P = 1e4 * np.eye(D)
        prev = obs[0]["mid"]
        first = True
        self.loglik = 0.0
        self.n_ll = 0
        for i, pl in enumerate(obs):
            dtd = (pl["mid"] - prev).days
            P = P + self.q * dtd * np.eye(D)
            prev = pl["mid"]
            R = poll_cov_ilr(pl["p"], pl["n"], self.kappa)
            S = P + R
            L = sla.cho_factor(S)
            innov = pl["y"] - m
            if first:
                m = pl["y"].copy()
                P = R.copy()
                first = False
                continue
            if i >= 10:  # skip early terms: reduce diffuse-init sensitivity
                self.loglik += -0.5 * (innov @ sla.cho_solve(L, innov)
                                       + 2 * np.log(np.diag(L[0])).sum()
                                       + D * np.log(2 * np.pi))
                self.n_ll += 1
            K = sla.cho_solve(L, P).T
            m = m + K @ innov
            P = P - K @ S @ K.T
            P = 0.5 * (P + P.T)
        dtd = (cutoff - prev).days
        self.mean = m
        self.cov = P + self.q * max(dtd, 0) * np.eye(D)
        self.cutoff = cutoff
        return self

    def nowcast(self):
        return self.mean, self.cov

    def predict_poll(self, poll):
        dtd = max((poll["mid"] - self.cutoff).days, 0)
        R = poll_cov_ilr(poll["p"], poll["n"], self.kappa)
        return self.mean, self.cov + self.q * dtd * np.eye(D) + R


# ----------------------------------------------------------------------------
# Estimator 3: fieldwork-window-average likelihood (sparse GMRF)
# ----------------------------------------------------------------------------
class WindowAvg:
    name = "window-avg"

    def __init__(self, q, kappa):
        self.q, self.kappa = q, kappa

    def fit(self, polls, cutoff):
        t0 = min(pl["from"] for pl in polls)
        T = (cutoff - t0).days + 1
        self.t0, self.T, self.cutoff = t0, T, cutoff
        N = T * D
        # RW prior precision per coordinate: (1/q) D'D, tridiagonal
        main = np.full(T, 2.0 / self.q)
        main[0] = main[-1] = 1.0 / self.q
        off = np.full(T - 1, -1.0 / self.q)
        Lp = sp.diags([off, main, off], [-1, 0, 1], format="coo")
        Q = sp.kron(Lp, sp.eye(D), format="coo")
        ri, ci, vv = [Q.row], [Q.col], [Q.data]
        b = np.zeros(N)
        base = np.arange(D)
        for pl in polls:
            a = (pl["from"] - t0).days
            e = (pl["to"] - t0).days
            Lw = e - a + 1
            R = poll_cov_ilr(pl["p"], pl["n"], self.kappa)
            Rinv = np.linalg.inv(R)
            blk = Rinv / (Lw * Lw)
            days = np.arange(a, e + 1)
            idx = (days[:, None] * D + base[None, :]).ravel()  # (Lw*D,)
            # outer block structure: for all day pairs (s,t) add blk
            rr = np.repeat(idx, Lw * D)
            cc = np.tile(idx, Lw * D)
            ri.append(rr)
            ci.append(cc)
            vv.append(np.tile(blk, (Lw, Lw)).ravel())
            contrib = (Rinv @ pl["y"]) / Lw
            b[idx] += np.tile(contrib, Lw)
        rows = np.concatenate([np.asarray(x) for x in ri])
        cols = np.concatenate([np.asarray(x) for x in ci])
        vals = np.concatenate([np.asarray(x) for x in vv])
        A = sp.coo_matrix((vals, (rows, cols)), shape=(N, N)).tocsc()
        A = A + 1e-8 * sp.eye(N, format="csc")
        self.lu = spla.splu(A, permc_spec="NATURAL")
        self.mu = self.lu.solve(b)
        # marginal covariance at cutoff day (last day): 8 column solves
        E = np.zeros((N, D))
        for j in range(D):
            E[(T - 1) * D + j, j] = 1.0
        Scols = self.lu.solve(E)
        self.mean = self.mu[(T - 1) * D:(T - 1) * D + D]
        C = Scols[(T - 1) * D:(T - 1) * D + D, :]
        self.cov = 0.5 * (C + C.T)
        return self

    def latent_at(self, dates):
        """Posterior mean/marginal cov of latent state on given dates."""
        out = []
        for dte in dates:
            t = min(max((dte - self.t0).days, 0), self.T - 1)
            E = np.zeros((self.T * D, D))
            for j in range(D):
                E[t * D + j, j] = 1.0
            Sc = self.lu.solve(E)
            C = Sc[t * D:t * D + D, :]
            out.append((self.mu[t * D:t * D + D], 0.5 * (C + C.T)))
        return out

    def nowcast(self):
        return self.mean, self.cov

    def predict_poll(self, poll):
        dtd = max((poll["mid"] - self.cutoff).days, 0)
        R = poll_cov_ilr(poll["p"], poll["n"], self.kappa)
        return self.mean, self.cov + self.q * dtd * np.eye(D) + R


# ----------------------------------------------------------------------------
# Scoring
# ----------------------------------------------------------------------------
def log_score(y, mean, cov):
    L = sla.cho_factor(cov)
    d = y - mean
    return -0.5 * (d @ sla.cho_solve(L, d)
                   + 2 * np.log(np.diag(L[0])).sum() + D * np.log(2 * np.pi))


def mvn_draws(rng, mean, cov, n):
    return rng.multivariate_normal(mean, cov, size=n,
                                   check_valid="ignore", method="eigh")


def share_intervals(mean, cov, rng, level=0.95, n=2000):
    y = mvn_draws(rng, mean, cov, n)
    P = ilr_inv(y.T)  # (9, n)
    lo = np.quantile(P, (1 - level) / 2, axis=1)
    hi = np.quantile(P, 1 - (1 - level) / 2, axis=1)
    return lo, hi


# ----------------------------------------------------------------------------
# Historical publication-aware folds
# ----------------------------------------------------------------------------
def fold_cutoffs():
    c = dt.date.fromisoformat(CONFIG["fold_start"])
    end = dt.date.fromisoformat(CONFIG["fold_end"])
    out = []
    while c <= end:
        out.append(c)
        c += dt.timedelta(days=CONFIG["fold_step_days"])
    return out


def run_fold(est_factory, polls, cutoff, rng):
    train = [pl for pl in polls if pl["pub"] <= cutoff]
    horizon = cutoff + dt.timedelta(days=CONFIG["predict_horizon_days"])
    test = [pl for pl in polls if cutoff < pl["pub"] <= horizon]
    if len(train) < 20 or not test:
        return None
    est = est_factory().fit(train, cutoff)
    scores, cov_hits, abserr = [], [], []
    for pl in test:
        mean, cov = est.predict_poll(pl)
        scores.append(log_score(pl["y"], mean, cov))
        lo, hi = share_intervals(mean, cov, rng)
        cov_hits.append((pl["p"] >= lo) & (pl["p"] <= hi))
        draws = ilr_inv(mvn_draws(rng, mean, cov, 2000).T)
        abserr.append(np.abs(draws.mean(axis=1) - pl["p"]))
    return {
        "cutoff": str(cutoff), "n_test": len(test),
        "mean_log_score": float(np.mean(scores)),
        "coverage95": np.mean(cov_hits, axis=0).tolist(),
        "mae_shares": np.mean(abserr, axis=0).tolist(),
    }


# ----------------------------------------------------------------------------
# Synthetic known-state experiment
# ----------------------------------------------------------------------------
def synth_run(est_factory, q_true, houses, rng, polls_real):
    T = CONFIG["synth_T"]
    x = np.zeros((T, D))
    p0 = np.mean([pl["p"] for pl in polls_real[-40:]], axis=0)
    x[0] = ilr(p0 / p0.sum())
    steps = rng.multivariate_normal(np.zeros(D), q_true * np.eye(D), T - 1)
    x[1:] = x[0] + np.cumsum(steps, axis=0)
    ln = np.array([[(pl["to"] - pl["from"]).days + 1, pl["n"]]
                   for pl in polls_real])
    t0 = dt.date(2030, 1, 1)
    offsets = (rng.normal(0, CONFIG["synth_house_sd_ilr"], (8, D))
               if houses else np.zeros((8, D)))
    deff = CONFIG["synth_design_effect"] if houses else 1.0
    polls, day = [], 5
    hi = 0
    while day < T - 1:
        Lw, n = ln[rng.integers(len(ln))]
        Lw = int(min(Lw, day))
        a = day - Lw + 1
        pavg = ilr_inv(x[a:day + 1].mean(axis=0) + offsets[hi % 8])
        counts = rng.multinomial(int(n / deff), pavg)
        n_eff = counts.sum()
        p = np.maximum(counts / n_eff, CONFIG["zero_share_floor"])
        p = p / p.sum()
        polls.append({
            "company": f"H{hi % 8}", "p": p, "n": n_eff,
            "from": t0 + dt.timedelta(days=a),
            "to": t0 + dt.timedelta(days=day),
            "mid": t0 + dt.timedelta(days=(a + day) // 2),
            "pub": t0 + dt.timedelta(days=day + 3),
            "y": ilr(p),
        })
        hi += 1
        day += max(1, int(rng.exponential(CONFIG["synth_poll_gap_mean"])))
    cut_day = CONFIG["synth_cutoff"]
    cutoff = t0 + dt.timedelta(days=cut_day)
    train = [pl for pl in polls if pl["pub"] <= cutoff]
    est = est_factory().fit(train, cutoff)
    eval_days = np.linspace(100, cut_day, CONFIG["synth_eval_points"]).astype(int)
    eval_dates = [t0 + dt.timedelta(days=int(dd)) for dd in eval_days]
    if isinstance(est, WindowAvg):
        latents = est.latent_at(eval_dates)
    elif isinstance(est, MidpointSS):
        # rerun filter storing states: cheap refit per eval date
        latents = [est_factory().fit(
            [pl for pl in train if pl["pub"] <= edate], edate).nowcast()
            for edate in eval_dates]
    else:
        latents = [est_factory().fit(
            [pl for pl in train if pl["pub"] <= edate], edate).nowcast()
            for edate in eval_dates]
    hits95, hits50, rmse = [], [], []
    for (mean, cov), dd in zip(latents, eval_days):
        truth = ilr_inv(x[dd])
        lo, hi95 = share_intervals(mean, cov, rng, 0.95)
        lo50, hi50 = share_intervals(mean, cov, rng, 0.50)
        hits95.append((truth >= lo) & (truth <= hi95))
        hits50.append((truth >= lo50) & (truth <= hi50))
        rmse.append((ilr_inv(mean) - truth) ** 2)
    return {
        "coverage95": np.mean(hits95, axis=0).tolist(),
        "coverage50": np.mean(hits50, axis=0).tolist(),
        "rmse_shares": np.sqrt(np.mean(rmse, axis=0)).tolist(),
        "n_polls": len(train),
    }


# ----------------------------------------------------------------------------
# Main
# ----------------------------------------------------------------------------
def main():
    os.makedirs(RESULTS, exist_ok=True)
    rng = np.random.default_rng(CONFIG["seed"])
    polls, excl = load_polls()
    print(f"eligible polls: {len(polls)}; exclusions: {excl}", flush=True)
    overlaps = 0
    by_house = {}
    for i, pl in enumerate(polls):
        by_house.setdefault(pl["company"], []).append(pl)
    for hp in by_house.values():
        hp.sort(key=lambda p: p["from"])
        for a, b_ in zip(hp, hp[1:]):
            if b_["from"] <= a["to"]:
                overlaps += 1
    meta = {"config": CONFIG, "n_polls": len(polls), "exclusions": excl,
            "same_house_overlaps": overlaps}

    cutoffs = fold_cutoffs()
    tune_before = dt.date.fromisoformat(CONFIG["tune_before"])
    tune_cuts = [c for c in cutoffs if c < tune_before]
    eval_cuts = [c for c in cutoffs if c >= tune_before]
    print(f"folds: {len(tune_cuts)} tuning, {len(eval_cuts)} evaluation",
          flush=True)

    # ---- hyperparameter tuning on tuning folds (kappa=1.0 during tuning)
    tuned = {}
    t_start = time.time()
    for hl in CONFIG["halflife_grid"]:
        s = [r["mean_log_score"] for c in tune_cuts
             if (r := run_fold(lambda: Recency(hl, 1.0), polls, c, rng))]
        tuned.setdefault("recency", []).append((np.mean(s), hl))
        print(f"tune recency hl={hl}: {np.mean(s):.2f}", flush=True)
    for q in CONFIG["q_grid"]:
        s = [r["mean_log_score"] for c in tune_cuts
             if (r := run_fold(lambda: MidpointSS(q, 1.0), polls, c, rng))]
        tuned.setdefault("midpoint-ss", []).append((np.mean(s), q))
        print(f"tune midpoint q={q}: {np.mean(s):.2f}", flush=True)
    for q in CONFIG["q_grid"]:
        s = [r["mean_log_score"] for c in tune_cuts
             if (r := run_fold(lambda: WindowAvg(q, 1.0), polls, c, rng))]
        tuned.setdefault("window-avg", []).append((np.mean(s), q))
        print(f"tune window-avg q={q}: {np.mean(s):.2f}", flush=True)
    best = {k: max(v)[1] for k, v in tuned.items()}
    meta["tuning"] = {k: [(float(a), float(b)) for a, b in v]
                      for k, v in tuned.items()}
    meta["chosen_hyperparams"] = best
    meta["tuning_seconds"] = round(time.time() - t_start, 1)
    print(f"chosen: {best}", flush=True)

    factories = {}
    for kappa in CONFIG["kappa_grid"]:
        factories[("recency", kappa)] = (
            lambda hl=best["recency"], k=kappa: Recency(hl, k))
        factories[("midpoint-ss", kappa)] = (
            lambda q=best["midpoint-ss"], k=kappa: MidpointSS(q, k))
        factories[("window-avg", kappa)] = (
            lambda q=best["window-avg"], k=kappa: WindowAvg(q, k))

    # ---- evaluation folds
    fold_rows = []
    for (name, kappa), fac in factories.items():
        t0 = time.time()
        for c in eval_cuts:
            r = run_fold(fac, polls, c, rng)
            if r:
                r.update(estimator=name, kappa=kappa)
                fold_rows.append(r)
        print(f"folds {name} kappa={kappa}: {time.time()-t0:.0f}s", flush=True)
    with open(os.path.join(RESULTS, "folds.json"), "w") as f:
        json.dump(fold_rows, f, indent=1)

    # ---- synthetic known-state
    synth_rows = []
    q_true = best["midpoint-ss"]
    for scenario, houses in [("clean", False), ("house+deff", True)]:
        for rep in range(CONFIG["synth_replicates"]):
            srng = np.random.default_rng(CONFIG["seed"] + 1000 + rep)
            for name in ["recency", "midpoint-ss", "window-avg"]:
                fac = factories[(name, 1.0)]
                r = synth_run(fac, q_true, houses, srng, polls)
                r.update(estimator=name, scenario=scenario, rep=rep,
                         q_true=q_true)
                synth_rows.append(r)
        print(f"synthetic {scenario} done", flush=True)
    with open(os.path.join(RESULTS, "synthetic.json"), "w") as f:
        json.dump(synth_rows, f, indent=1)

    # ---- final-date nowcast, thresholds, coalitions, resources
    final_cut = dt.date.fromisoformat(CONFIG["final_cutoff"])
    final_rows = []
    for (name, kappa), fac in factories.items():
        tracemalloc.start()
        t0 = time.time()
        est = fac().fit([pl for pl in polls if pl["pub"] <= final_cut],
                        final_cut)
        mean, cov = est.nowcast()
        wall = time.time() - t0
        peak = tracemalloc.get_traced_memory()[1]
        tracemalloc.stop()
        draws = ilr_inv(mvn_draws(
            rng, mean, cov, CONFIG["n_draws"]).T)  # (9, n_draws)
        thr = CONFIG["threshold"]
        row = {
            "estimator": name, "kappa": kappa,
            "point_shares": ilr_inv(mean).round(4).tolist(),
            "p_ge_4pct": {PARTY_NAMES[i]: float((draws[i] >= thr).mean())
                          for i in range(8)},
            "p_left_gt_right": float(
                (draws[[4, 5, 6]].sum(0) > draws[[0, 1, 3, 7]].sum(0)).mean()),
            "wall_seconds": round(wall, 2),
            "peak_mem_mb": round(peak / 2**20, 1),
        }
        final_rows.append(row)
        print(f"final {name} kappa={kappa}: {wall:.1f}s "
              f"{row['peak_mem_mb']}MB", flush=True)
    with open(os.path.join(RESULTS, "final.json"), "w") as f:
        json.dump(final_rows, f, indent=1)

    # ---- descriptive house residuals vs window-avg smoothed latent
    fac = factories[("window-avg", 1.0)]
    est = fac().fit([pl for pl in polls if pl["pub"] <= final_cut], final_cut)
    recent = [pl for pl in polls
              if pl["pub"] <= final_cut and pl["pub"].year >= 2022]
    lat = est.latent_at([pl["mid"] for pl in recent])
    res = {}
    for pl, (lm, _) in zip(recent, lat):
        res.setdefault(pl["company"], []).append(pl["p"] - ilr_inv(lm))
    house_rows = {h: {"n": len(v),
                      "mean_share_residual_pp":
                          (100 * np.mean(v, axis=0)).round(2).tolist()}
                  for h, v in sorted(res.items(), key=lambda x: -len(x[1]))
                  if len(v) >= 10}
    meta["house_residuals_since_2022"] = house_rows

    with open(os.path.join(RESULTS, "meta.json"), "w") as f:
        json.dump(meta, f, indent=1, default=str)
    print("DONE", flush=True)


if __name__ == "__main__":
    main()
