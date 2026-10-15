# quake-av: autonomous vehicles + earthquake disruption in MATSim

Earthquake traffic-disruption experiments on a literature-standard road network,
comparing **autonomous vehicles (AVs)** against conventional drivers. Built on
[MATSim](https://matsim.org) 2026.0 (pinned in `pom.xml`) with Java 25.

**Research question:** in the ~24 hours after an earthquake, how do AVs vs
conventional drivers compare on emergency access (hospital arrivals),
return-home completion, and network delay — across AV penetration rates
{0, 10, 25, 50, 75, 100%}?

**How it works (30-second version):** thousands of simulated agents drive a
morning commute on the Sioux Falls benchmark network. At 08:30 an earthquake
hits: some roads are damaged and a seeded share of agents is redirected (home /
hospital / stay / random). AVs plan routes on the live damaged network;
conventional drivers route damage-blind on pre-quake knowledge. Compare the
outcomes per penetration rate.

> **New here?** Start at [Quickstart](#quickstart): one build command, one run
> command, and you'll have your first results in about a minute.

---

## Prerequisites

- **Java 25** (e.g. Temurin 25.0.4.1+; check with `java -version`). Maven is
  **included** via the `./mvnw` wrapper — no separate install needed.
- **Python 3 + packages** (analysis only): `python3 -m pip install --user pandas matplotlib zstandard jupyter nbclient ipykernel`.
- ~2 GB free disk (the built jar is ~220 MB; a 60-cell sweep writes tens of GB
  to `output/`, which is git-ignored).
- First build downloads dependencies; allow several minutes on a fresh machine.

Clone and work on the `av-earthquake` branch (all project work lives there).

---

## Quickstart

```sh
# 1. Build once (~2-4 min first time; skips the 39-test suite for speed)
./mvnw -q -DskipTests package

# 2. Run the 1% Sioux Falls baseline, 1 iteration (~40 s on a laptop)
java -cp matsim-example-project-0.0.1-SNAPSHOT.jar org.matsim.project.RunAvEarthquake \
  --config scenarios/siouxfalls/config.xml --output /tmp/sf-baseline --lastIteration 1

# 3. Look at the outputs
ls /tmp/sf-baseline/   # output_events.xml.zst, output_trips.csv.zst, ...
```

> Use `java -cp <jar> org.matsim.project.RunAvEarthquake ...`, **not**
> `java -jar ... run ...` — the latter launches the upstream template class,
> not this project's run class.

Run the full test suite (39 tests) with `./mvnw test`.

---

## Running the experiments

All runs go through `org.matsim.project.RunAvEarthquake`. Common building blocks:

```sh
JAR=matsim-example-project-0.0.1-SNAPSHOT.jar
RUN="java -cp $JAR org.matsim.project.RunAvEarthquake --config scenarios/siouxfalls/config.xml"

# Baseline (no quake)
$RUN --output output/baseline --lastIteration 10

# Earthquake damage only (roads degrade/close at 08:30)
$RUN --output output/damage --lastIteration 10 \
  --damageFile scenarios/siouxfalls/damage-sample.csv

# Full quake: damage + destination redirect at 08:30 (D5 default splits)
$RUN --output output/quake --lastIteration 10 \
  --damageFile scenarios/siouxfalls/damage-sample.csv --redirect

# Full quake with 50% AVs
$RUN --output output/quake-av50 --lastIteration 10 \
  --damageFile scenarios/siouxfalls/damage-sample.csv --redirect \
  --avShare 0.5 --avSeed 42
```

### All flags (`--help` prints these too)

| Flag | Default | Meaning |
|---|---|---|
| `--config` | `scenarios/equil/config-2026.xml` | Scenario config file |
| `--output` | config's output dir | Where results go (overwritten each run) |
| `--lastIteration` | config's value (10) | Equilibration iterations; each iteration = the same 24h day |
| `--avShare` | `0.0` | AV penetration 0–1 (nested seeded assignment: the 10% AV set is inside the 25% set at the same seed) |
| `--avSeed` | `0` | Seed for AV assignment (fix across compared cells) |
| `--avFlowEfficiency` | `2.0` | AV capacity factor (>1 = platooning gain) |
| `--damageFile` | none | Per-link damage CSV (see `docs/damage-format.md`) |
| `--tQuake` | `30600` (= 08:30) | Quake time in seconds |
| `--redirect` | off | Enable destination redirect at `t_quake` |
| `--redirectSeed` | `9182` | Seed for redirect draws (fix across compared cells) |
| `--homeShare` / `--hospitalShare` / `--stayShare` / `--otherShare` | `0.60` / `0.17` / `0.15` / `0.08` | Redirect splits (D5; school's 0.10 folded into home until school locations exist) |
| `--hospitals` | `scenarios/siouxfalls/hospitals.csv` | Hospital sites file |

### The parameter sweep (Phase 5)

```sh
# Full grid: 6 shares x 5 seeds x {quake, noquake} = 60 cells (~7 min/cell at 1%)
scripts/run_sweep.sh --jobs 8
```

Cells land in `output/sweep/av{share}_seed{seed}_{quake|noquake}/`, each with
events, trips, and `redirect-log.csv`; `cell-status.txt` tracks OK/FAIL plus a
redirect-outcome summary. The script rebuilds a stale jar automatically. Key
options: `--shares`, `--seeds` (default 5, use e.g. `"1 2 3 4 5 6 7 8"` for long
runs), `--variants`, `--iterations`, `--jobs`, `--outdir`. A 4-cell equil smoke
test is built in (`SweepDriverTest`).

**Reading the design:** each iteration simulates the *same* 24h day (10
iterations = one day equilibrating, not ten days). Iteration 0 reads as the
surprise day; later iterations as the adapted response. See
`docs/sweep-regime.md` for the (a)-vs-(b) design discussion.

### Analysis and plots (Phase 6)

```sh
python3 -m pip install --user pandas matplotlib zstandard jupyter nbclient ipykernel
```

- `scripts/analyze.py` — importable metric functions (travel times, hospital
  access, completion/stuck, link volumes, mode shares).
- `scripts/analysis.ipynb` — six display sections over
  `output/demo/{baseline,damage,quake}`; open in Jupyter/VSCode to see the plots.
- `scripts/sweep_compare.py` — full-grid comparison → `sweep-summary.csv` +
  AV-share plots (hospital median 19.3→12.0 min, p90 156→31 min at 100% AV).

---

## Key concepts for outsiders

- **Scenarios.** `scenarios/equil/` is a tiny 100-agent toy for fast smoke
  tests. `scenarios/siouxfalls/` is the real benchmark: 24-node/76-link
  network, ~3,600-agent synthetic population (1% of the 360,600-trip OD matrix),
  3 hospital sites, ready-made config + sample damage file. Details in
  `scenarios/siouxfalls/README.md`.
- **Sample-rate rule.** Population fraction and network capacity must match:
  regenerating at `--sample X` requires `flowCapacityFactor =
  storageCapacityFactor = X` in the config. Defaults are 0.01 throughout.
- **AV vs conventional (the core distinction).** AV persons drive `av`-mode legs
  routed on *observed* post-quake travel times; conventional drivers route on a
  frozen *pre-quake* snapshot (damage-blind). Both share the roads; AVs also
  carry a flow-efficiency factor. Active only when `--avShare > 0`.
- **Effective closure.** "Closed" links keep a 2%-capacity trickle instead of
  absolute zero: true zero-capacity mid-run closures hang the simulator
  irrecoverably (documented in `docs/damage-format.md`, found the hard way).
- **No-quake default.** Without `--damageFile`/`--redirect`, runs are plain
  baselines with stock MATSim behavior — safe for comparisons.
- **Raw data.** `extra-data/` (original TNTP files) is git-ignored and not in
  the clone. You can run everything without it (built files in `scenarios/` are
  tracked); to *regenerate* the network/population, fetch the Sioux Falls files
  from the Transportation Networks for Research repository (TNTP) into
  `extra-data/SiouxFalls/` and follow `scenarios/siouxfalls/README.md`.

---

## Repository layout

```
src/main/java/org/matsim/project/  RunAvEarthquake, AvAssignment, AvSetup, AvRouting,
                                   SiouxFallsNetworkConverter, SiouxFallsPopulationGenerator,
                                   DamageToNetworkChangeEvents, QuakeRedirector,
                                   RedirectConfig, RedirectLogger
src/test/...                       39 tests (one class per component + end-to-end runs)
scenarios/equil/                   100-agent smoke-test scenario
scenarios/siouxfalls/              network, population, hospitals, config, damage sample + README
docs/                              damage-format.md (CSV contract), sweep-regime.md (design brief)
scripts/                           run_sweep.sh, analyze.py, analysis.ipynb, sweep_compare.py
output/                            run outputs (git-ignored)
agent-helpers/                     working plan + status (git-ignored; start here to contribute)
```

## Troubleshooting

- **Stale jar:** always rebuild after pulling code (`./mvnw -q -DskipTests package`);
  `run_sweep.sh` does this automatically when sources are newer.
- **Slow first build / DTD timeouts:** dependency downloads and MATSim DTD
  fetches need network; transient `Read timed out` failures usually pass on retry.
- **A run never finishes:** check it isn't a true zero-capacity closure (see
  Effective closure above); otherwise inspect `logfileWarningsErrors.log`.
- **Out of disk:** one 10-iteration 1% cell is a few hundred MB; the full sweep
  is tens of GB. Point `--output`/`--outdir` at a big volume.

---

### Import into eclipse

1. download a modern version of eclipse. This should have maven and git included by default.
1. `file->import->git->projects from git->clone URI` and clone as specified above.  _It will go through a
sequence of windows; it is important that you import as 'general project'._
1. `file->import->maven->existing maven projects`

Sometimes, step 3 does not work, in particular after previously failed attempts.  Sometimes, it is possible to
right-click to `configure->convert to maven project`.  If that fails, the best thing seems to remove all
pieces of the failed attempt in the directory and start over.

### Import into IntelliJ

`File -> New -> Project from Version Control` paste the repository url and hit 'clone'. IntelliJ usually figures out
that the project is a maven project. If not: `Right click on pom.xml -> import as maven project`.

### Java Version

The project uses Java 25. Usually a suitable SDK is packaged within IntelliJ or Eclipse. Otherwise, one must install a
suitable sdk manually, which is available [here](https://openjdk.java.net/)

### Licenses
(The following paragraphs need to be adjusted according to the specifications of your project.)

The **MATSim program code** in this repository is distributed under the terms of the [GNU General Public License as published by the Free Software Foundation (version 2)](https://www.gnu.org/licenses/old-licenses/gpl-2.0.en.html). The MATSim program code are files that reside in the `src` directory hierarchy and typically end with `*.java`.

The **MATSim input files, output files, analysis data and visualizations** are licensed under a <a rel="license" href="http://creativecommons.org/licenses/by/4.0/">Creative Commons Attribution 4.0 International License</a>.
<a rel="license" href="http://creativecommons.org/licenses/by/4.0/"><img alt="Creative Commons License" style="border-width:0" src="https://i.creativecommons.org/l/by/4.0/80x15.png" /></a><br /> MATSim input files are those that are used as input to run MATSim. They often, but not always, have a header pointing to matsim.org. They typically reside in the `scenarios` directory hierarchy. MATSim output files, analysis data, and visualizations are files generated by MATSim runs, or by postprocessing.  They typically reside in a directory hierarchy starting with `output`.

**Other data files**, in particular in `original-input-data`, have their own individual licenses that need to be individually clarified with the copyright holders.
