"""Read retained run-2 draws and summaries; never fit or replace evidence."""

import array
import collections
import hashlib
import json
import math
from pathlib import Path
import sys


def quantile(values, probability):
    position = (len(values) - 1) * probability
    low = int(position)
    high = min(low + 1, len(values) - 1)
    return values[low] + (position - low) * (values[high] - values[low])


def summarize(rows):
    count = len(rows)
    return {
        "cases": count,
        "below95": sum(r["observed"] < r["lower95"] for r in rows),
        "above95": sum(r["observed"] > r["upper95"] for r in rows),
        "coverage95": sum(r["lower95"] <= r["observed"] <= r["upper95"] for r in rows) / count,
        "coverage50": sum(r["lower50"] <= r["observed"] <= r["upper50"] for r in rows) / count,
        "meanStandardizedResidual": math.fsum(r["standardizedResidual"] for r in rows) / count,
        "rootMeanSquareStandardizedResidual": math.sqrt(
            math.fsum(r["standardizedResidual"] ** 2 for r in rows) / count
        ),
    }


root = Path(__file__).resolve().parent / "evidence/run-2"
data = json.loads((root / "diagnostics.json").read_text())
folds = [f for f in data["folds"] if f["periodId"] == "eight_party_2010" and f["active"]]
diagnostic = next(d for d in data["diagnostics"] if d["periodId"] == "eight_party_2010")
assert len(folds) == 48
assert sum(len(f["polls"]) for f in folds) == 370
rows = []
files_checked = 0
largest_error = 0.0
for fold in folds:
    for poll in fold["polls"]:
        assert poll["replacedZeros"] == 0
        for method in poll["methods"]:
            if method["method"] not in ("midpoint", "ilr_window"):
                continue
            artifact = method["drawArtifact"]
            raw = (root / artifact["path"]).read_bytes()
            assert hashlib.sha256(raw).hexdigest() == artifact["sha256"]
            assert len(raw) == artifact["bytes"] == 4000 * 9 * 8
            values = array.array("d")
            values.frombytes(raw)
            if sys.byteorder == "little":
                values.byteswap()
            assert all(math.isfinite(v) and 0 <= v <= 100 for v in values)
            assert all(abs(math.fsum(values[i:i + 9]) - 100) < 1e-9 for i in range(0, len(values), 9))
            for index, component in enumerate(method["components"]):
                column = values[index::9]
                mean = math.fsum(column) / len(column)
                variance = math.fsum((v - mean) ** 2 for v in column) / (len(column) - 1)
                sorted_column = sorted(column)
                computed = {
                    "mean": mean,
                    "standardizedResidual": (component["observed"] - mean) / math.sqrt(variance),
                    "lower95": quantile(sorted_column, (1 - 0.95) / 2),
                    "upper95": quantile(sorted_column, (1 + 0.95) / 2),
                    "lower50": quantile(sorted_column, 0.25),
                    "upper50": quantile(sorted_column, 0.75),
                }
                for key, value in computed.items():
                    error = abs(value - component[key])
                    largest_error = max(largest_error, error)
                    assert error < 1e-10, (poll["rowNumber"], key, error)
                rows.append(dict(component, method=method["method"], cutoff=fold["cutoff"],
                                 institute=poll["institute"], row=poll["rowNumber"]))
            files_checked += 1

print("Verified draw files:", files_checked, "largest summary recomputation difference:", largest_error)
assert files_checked == 740
for method in ("midpoint", "ilr_window"):
    for party in ("S", "KD", "OTHER"):
        selected = [r for r in rows if r["method"] == method and r["component"] == party]
        summary = summarize(selected)
        archived = next(g for g in diagnostic["misfit"] if g["candidate"] == method
                        and g["scope"] == "party" and g["name"] == party)
        for key in ("cases", "coverage95", "coverage50", "meanStandardizedResidual",
                    "rootMeanSquareStandardizedResidual"):
            assert abs(summary[key] - archived[key]) < 1e-12
        groups = collections.defaultdict(list)
        for row in selected:
            groups[row["cutoff"]].append(row)
        total_squares = math.fsum(r["standardizedResidual"] ** 2 for r in selected)
        worst = []
        for cutoff, group in groups.items():
            detail = summarize(group)
            detail["cutoff"] = cutoff
            detail["squaredResidualFraction"] = math.fsum(r["standardizedResidual"] ** 2 for r in group) / total_squares
            detail["institutesWithMisses"] = len({r["institute"] for r in group
                                                 if not r["lower95"] <= r["observed"] <= r["upper95"]})
            worst.append(detail)
        worst.sort(key=lambda g: g["below95"] + g["above95"], reverse=True)
        print(json.dumps({"method": method, "party": party, "summary": summary,
                          "mostMisses": worst[:3]}, sort_keys=True))

# Reduced binary logit check, not a fit or the production share-residual calculation.
n = 1000
for truth in (0.30, 0.04, 0.02):
    masses = [math.exp(math.lgamma(n + 1) - math.lgamma(k + 1) - math.lgamma(n - k + 1)
                       + k * math.log(truth) + (n - k) * math.log1p(-truth))
              for k in range(n + 1)]
    assert abs(math.fsum(masses) - 1) < 1e-10
    covered = []
    for k, mass in enumerate(masses):
        observed = k / n
        if k == 0:
            observed = 0.5 / n
        elif k == n:
            observed = 1 - 0.5 / n
        residual = (math.log(observed / (1 - observed)) - math.log(truth / (1 - truth)))
        deviation = math.sqrt(1 / (n * observed * (1 - observed)))
        covered.append(mass if abs(residual / deviation) <= 1.959963984540054 else 0)
    print("Reduced binary logit coverage:", truth, math.fsum(covered))
