"""Phase 6 analysis functions for the AV + earthquake Sioux Falls runs.

Reads MATSim run outputs (trips CSVs, redirect logs, event streams) and
computes the plan's per-run metrics: travel times, hospital access, completion,
link volumes, AV/mode splits. Visualized in ``analysis.ipynb``.
"""

import gzip
import io
import xml.etree.ElementTree as ET
from contextlib import contextmanager
from pathlib import Path

import pandas as pd

try:
    import zstandard as zstd

    _HAS_ZSTD = True
except ImportError:  # pragma: no cover
    _HAS_ZSTD = False


# ---------------------------------------------------------------- IO helpers

@contextmanager
def _open_maybe_compressed(path):
    path = Path(path)
    if path.suffix == ".zst":
        if not _HAS_ZSTD:
            raise RuntimeError("zstandard package needed for .zst files")
        dctx = zstd.ZstdDecompressor()
        with open(path, "rb") as fh:
            with dctx.stream_reader(fh) as reader:
                yield io.TextIOWrapper(reader, encoding="utf-8")
    elif path.suffix == ".gz":
        with gzip.open(path, "rt") as fh:
            yield fh
    else:
        with open(path) as fh:
            yield fh


def _find(run_dir, *names):
    run_dir = Path(run_dir)
    for name in names:
        for candidate in (run_dir / name, run_dir / (name + ".zst")):
            if candidate.exists():
                return candidate
    raise FileNotFoundError(f"none of {names} in {run_dir}")


def _to_seconds(value):
    """Parse MATSim durations: float seconds or HH:MM:SS."""
    try:
        return float(value)
    except (TypeError, ValueError):
        parts = str(value).strip().split(":")
        total = 0.0
        for part in parts:
            total = total * 60.0 + float(part)
        return total


# ---------------------------------------------------------------- readers

def read_trips(run_dir):
    """Per-trip records (one row per completed trip leg-chain)."""
    path = _find(run_dir, "output_trips.csv")
    with _open_maybe_compressed(path) as fh:
        df = pd.read_csv(fh, sep=";")
    df.columns = [c.strip() for c in df.columns]
    for col in ("trav_time", "dep_time", "wait_time"):
        if col in df.columns:
            df[col + "_s"] = df[col].apply(_to_seconds)
    return df


def read_redirect_log(run_dir):
    """Per-redirect rows (quake runs only); None when absent."""
    path = Path(run_dir) / "redirect-log.csv"
    if not path.exists():
        return None
    df = pd.read_csv(path)
    return df


def iter_events(run_dir):
    """Yield (type, attrib-dict) for every event (streaming, low memory)."""
    path = _find(run_dir, "output_events.xml")
    with _open_maybe_compressed(path) as fh:
        context = ET.iterparse(fh, events=("end",))
        for _, elem in context:
            if elem.tag == "event":
                yield elem.get("type"), dict(elem.attrib)
                elem.clear()


def link_entries(run_dir, t_quake=None):
    """DataFrame(link, before, after): entered-link counts split at t_quake."""
    before, after = {}, {}
    for typ, attrib in iter_events(run_dir):
        if typ != "entered link":
            continue
        target = after if t_quake is not None and float(attrib["time"]) >= t_quake else before
        link = attrib["link"]
        target[link] = target.get(link, 0) + 1
    links = sorted(set(before) | set(after))
    return pd.DataFrame(
        {"link": links, "before": [before.get(link, 0) for link in links],
         "after": [after.get(link, 0) for link in links]}
    )


def count_stuck(run_dir):
    """Number of PersonStuckEvents (non-arrival pressure gauge)."""
    return sum(1 for typ, _ in iter_events(run_dir) if typ == "stuck")


# ---------------------------------------------------------------- metrics

def trip_travel_stats(trips):
    """Mean/median travel time (min) overall and for post-quake departures."""
    out = {}
    mins = trips["trav_time_s"] / 60.0
    out["n_trips"] = int(len(trips))
    out["mean_min"] = float(mins.mean())
    out["median_min"] = float(mins.median())
    if "dep_time_s" in trips.columns:
        post = trips[trips["dep_time_s"] >= 8.5 * 3600]
        out["n_postquake"] = int(len(post))
        if len(post):
            out["post_mean_min"] = float(post["trav_time_s"].mean() / 60.0)
            out["post_median_min"] = float(post["trav_time_s"].median() / 60.0)
    return out


def redirect_outcomes(redirect_log):
    """Counts per redirect outcome (empty when no redirect ran)."""
    if redirect_log is None or not len(redirect_log):
        return {}
    return redirect_log["outcome"].value_counts().to_dict()


def hospital_trip_stats(trips, redirect_log, t_quake=8.5 * 3600):
    """Travel stats for hospital-bound redirected agents' post-quake legs."""
    if redirect_log is None:
        return {}
    agents = set(redirect_log.loc[redirect_log["outcome"] == "hospital", "agent_id"].astype(str))
    legs = trips[
        trips["person"].astype(str).isin(agents) & (trips["dep_time_s"] >= t_quake)
    ]
    if not len(legs):
        return {"n_hospital_agents": len(agents), "n_postquake_legs": 0}
    mins = legs["trav_time_s"] / 60.0
    return {
        "n_hospital_agents": len(agents),
        "n_postquake_legs": int(len(legs)),
        "mean_min": float(mins.mean()),
        "median_min": float(mins.median()),
        "p90_min": float(mins.quantile(0.9)),
    }


def mode_shares(trips):
    """Trip counts per main mode."""
    col = next((c for c in ("main_mode", "trav_mode", "modes") if c in trips.columns), None)
    if col is None:
        return {}
    return trips[col].value_counts().to_dict()


def summarize_run(run_dir, label, t_quake=8.5 * 3600):
    """One-row metric dict for a run directory."""
    trips = read_trips(run_dir)
    redirect_log = read_redirect_log(run_dir)
    row = {"run": label}
    row.update({f"trip_{k}": v for k, v in trip_travel_stats(trips).items()})
    row.update({f"redirect_{k}": v for k, v in redirect_outcomes(redirect_log).items()})
    row.update({f"hospital_{k}": v for k, v in hospital_trip_stats(trips, redirect_log, t_quake).items()})
    row.update({f"mode_{k}": v for k, v in mode_shares(trips).items()})
    row["stuck_events"] = count_stuck(run_dir)
    return row


def compare_runs(runs, t_quake=8.5 * 3600):
    """runs: {label: dir}. Returns a labeled DataFrame, one row per run."""
    return pd.DataFrame([summarize_run(d, label, t_quake) for label, d in runs.items()])


# ---------------------------------------------------------------- plots

def _setup_ax(ax, title, xlabel, ylabel):
    ax.set_title(title)
    ax.set_xlabel(xlabel)
    ax.set_ylabel(ylabel)


def plot_travel_time_hist(ax, trips_by_label, bins=40, max_min=120):
    """Overlaid travel-time histograms per run."""
    for label, trips in trips_by_label.items():
        mins = (trips["trav_time_s"] / 60.0).clip(upper=max_min)
        ax.hist(mins, bins=bins, alpha=0.45, label=f"{label} (n={len(trips)})", density=True)
    _setup_ax(ax, "Trip travel-time distribution", "minutes", "density")
    ax.legend()


def plot_redirect_outcomes(ax, redirect_log):
    """Bar chart of D5 outcome counts."""
    counts = redirect_log["outcome"].value_counts()
    counts = counts.reindex(["home", "hospital", "stay", "random"]).dropna()
    ax.bar(counts.index, counts.values)
    _setup_ax(ax, "Redirect outcomes at t_quake", "outcome", "agents")
    for x, v in zip(counts.index, counts.values):
        ax.text(x, v, str(v), ha="center", va="bottom")


def plot_hospital_arrivals(ax, trips, redirect_log, t_quake=8.5 * 3600, bins=24):
    """Post-quake hospital-leg travel-time histogram."""
    agents = set(redirect_log.loc[redirect_log["outcome"] == "hospital", "agent_id"].astype(str))
    legs = trips[trips["person"].astype(str).isin(agents) & (trips["dep_time_s"] >= t_quake)]
    mins = (legs["trav_time_s"] / 60.0).clip(upper=180)
    ax.hist(mins, bins=bins)
    _setup_ax(ax, f"Hospital-bound post-quake legs (n={len(legs)})", "minutes", "legs")


def plot_link_shift(ax, baseline_vols, damaged_vols, top_n=15):
    """Largest absolute change in post-quake link entries, baseline vs damaged."""
    merged = baseline_vols.merge(damaged_vols, on="link", suffixes=("_base", "_dmg"))
    merged["delta"] = merged["after_dmg"] - merged["after_base"]
    top = merged.reindex(merged["delta"].abs().sort_values(ascending=False).index[:top_n])
    top = top.sort_values("delta")
    ax.barh(top["link"], top["delta"])
    _setup_ax(ax, f"Top-{top_n} post-quake link-volume shifts", "entries (damaged − baseline)", "link")
