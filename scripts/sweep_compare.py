"""Sweep comparison: hospital access + travel times vs AV share.

Reuses analyze.py readers. Prints a seed-aggregated table and saves plots.
Events parsing is limited to a 4-cell subset (expensive); trips/redirect
metrics cover all 60 cells.
"""

import sys
from pathlib import Path

CWD = Path.cwd()
ROOT = CWD.parent if CWD.name == "scripts" else CWD
sys.path.insert(0, str(ROOT / "scripts"))

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import pandas as pd

import analyze

SHARES = [0, 10, 25, 50, 75, 100]
SEEDS = [1, 2, 3, 4, 5]
T_QUAKE = 8.5 * 3600
SWEEP = ROOT / "output" / "sweep"
FIG = SWEEP / "figures"
FIG.mkdir(parents=True, exist_ok=True)


def cell(share, seed, variant):
    return SWEEP / f"av{share}_seed{seed}_{variant}"


rows = []
for share in SHARES:
    for seed in SEEDS:
        for variant in ("quake", "noquake"):
            d = cell(share, seed, variant)
            trips = analyze.read_trips(d)
            redirect_log = analyze.read_redirect_log(d)
            row = {"share": share, "seed": seed, "variant": variant}
            row.update(analyze.trip_travel_stats(trips))
            row.update({"hospital_" + k: v for k, v in
                        analyze.hospital_trip_stats(trips, redirect_log, T_QUAKE).items()})
            row.update({"mode_" + k: v for k, v in analyze.mode_shares(trips).items()})
            rows.append(row)
full = pd.DataFrame(rows)
full.to_csv(SWEEP / "sweep-summary.csv", index=False)

agg = full.groupby(["share", "variant"]).agg(
    trip_post_median_min=("post_median_min", "mean"),
    trip_post_mean_min=("post_mean_min", "mean"),
    hospital_median_min=("hospital_median_min", "mean"),
    hospital_p90_min=("hospital_p90_min", "mean"),
    n_trips=("n_trips", "mean"),
).reset_index()
print(agg.to_string(index=False))

fig, axes = plt.subplots(1, 2, figsize=(12, 4))
for variant, style in (("quake", "o-"), ("noquake", "s--")):
    sub = agg[agg["variant"] == variant]
    axes[0].plot(sub["share"], sub["hospital_median_min"], style, label=variant)
    axes[1].plot(sub["share"], sub["trip_post_median_min"], style, label=variant)
axes[0].set_title("Hospital-bound median trip (min) vs AV share")
axes[0].set_xlabel("AV share (%)")
axes[1].set_title("Post-quake median trip (min) vs AV share")
axes[1].set_xlabel("AV share (%)")
axes[0].legend()
axes[1].legend()
fig.tight_layout()
fig.savefig(FIG / "sweep-av-share.png")
print("saved", FIG / "sweep-av-share.png")

# Stuck/completion spot-check on 4 corner cells (full event scans).
for share, variant in ((0, "quake"), (100, "quake"), (0, "noquake"), (100, "noquake")):
    d = cell(share, 1, variant)
    print(f"av{share} {variant}: stuck={analyze.count_stuck(d)}")
