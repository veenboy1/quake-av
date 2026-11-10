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

## Changes relative to the upstream MATSim example project

This repository started from
[`matsim-org/matsim-example-project`](https://github.com/matsim-org/matsim-example-project)
(MATSim 2026.0 / Java 25). The upstream template run classes are still present;
all project work is additive.

| Added / changed | Where | What it does |
|---|---|---|
| Custom run class + CLI | `src/main/java/org/matsim/project/RunAvEarthquake.java` | Loads any scenario and exposes `--config`, `--output`, `--lastIteration`, AV, damage, redirect and hospital flags. |
| Private AVs | `AvAssignment.java`, `AvSetup.java` | Seeded nested AV share, `car`/`av` vehicle types, `av` mode and links wired at runtime. |
| AV-vs-conventional routing | `AvRouting.java` | AVs route on observed (post-quake) times; conventional cars route on a pre-quake snapshot in damage runs. |
| Earthquake damage | `DamageToNetworkChangeEvents.java` | Per-link CSV → `NetworkChangeEvent`s at `t_quake` (effective closures, not absolute zero). |
| Mid-day destination redirect | `QuakeRedirector.java`, `RedirectConfig.java`, `RedirectLogger.java` | Seeded home/hospital/stay/random reassignment at `t_quake`, with a per-run CSV log. |
| Sioux Falls build pipeline | `SiouxFallsNetworkConverter.java`, `SiouxFallsPopulationGenerator.java` | TNTP network → MATSim network; OD matrix → synthetic 1 % population + hospitals. |
| Scenarios | `scenarios/equil/`, `scenarios/siouxfalls/`, `scenarios/Simulation_SF/` | Toy smoke test, 76-link benchmark, and the large eqasim San Francisco 1 % scenario. |
| Sweep + analysis | `scripts/run_sweep.sh`, `scripts/analyze.py`, `scripts/sweep_compare.py`, `scripts/analysis.ipynb` | 6 AV shares × 5 seeds × {quake, no-quake}, plus metrics/plots. |
| Documentation | `docs/` | Damage CSV contract, sweep design, SF network reference, verification handoff. |
| Tests | `src/test/java/org/matsim/project/` | **40 tests**, including end-to-end runs and SF regressions. |

The original eqasim config and input files are preserved unchanged. See
[`scenarios/Simulation_SF/README.md`](scenarios/Simulation_SF/README.md) for the
migration and [`docs/san-francisco-network.md`](docs/san-francisco-network.md)
for the full network/population reference.

For a verification checklist, see
[`docs/verification-handoff.md`](docs/verification-handoff.md).

---

## Prerequisites

- **Java 25** (e.g. Temurin 25.0.4.1+; check with `java -version`). Maven is
  **included** via the `./mvnw` wrapper — no separate install needed.
- **Python 3 + packages** (analysis only): `python3 -m pip install --user pandas matplotlib zstandard jupyter nbclient ipykernel`.
- ~2 GB free disk for the Sioux Falls work (the built jar is ~220 MB; a 60-cell
  sweep writes tens of GB to `output/`, which is git-ignored). A San Francisco
  iteration writes ~0.8 GB; budget **~10 GB RAM** and several GB of disk for it.
- First build downloads dependencies; allow several minutes on a fresh machine.

Clone and work on the `av-earthquake` branch (all project work lives there).

---

## Quickstart

```sh
# 1. Build once (~2-4 min first time; skips the 40-test suite for speed)
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

Run the full test suite (40 tests) with `./mvnw test`.

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

## San Francisco scenario (large network)

`scenarios/Simulation_SF/` contains an **eqasim San Francisco 1 % sample**
(75,228 persons, 656,557 links, 841 transit lines) and a MATSim 2026 config,
`san_francisco_config-2026.xml`. The original eqasim config and `.xml.gz` inputs
are kept unchanged. This is the **large-network verification case**; Sioux
Falls remains the fast benchmark.

Plan B is deliberate: the eqasim input data is run through this repository's
AV/earthquake runner with **eqasim mode choice switched off**. Mode shares are
fixed by the input plans; only re-routing is active.

```sh
JAR=matsim-example-project-0.0.1-SNAPSHOT.jar

# Baseline, one iteration (~16 min, ~10 GB RAM)
java -Xmx10g -cp $JAR org.matsim.project.RunAvEarthquake \
  --config scenarios/Simulation_SF/san_francisco_config-2026.xml \
  --output output-sf-1pct/baseline --lastIteration 0

# 50 % private AVs, one iteration
java -Xmx10g -cp $JAR org.matsim.project.RunAvEarthquake \
  --config scenarios/Simulation_SF/san_francisco_config-2026.xml \
  --output output-sf-1pct/av50 --lastIteration 0 \
  --avShare 0.5 --avSeed 42
```

Expected for the 50 % AV smoke run: exit 0, **37,787 / 75,228** AV persons,
`SwissRailRaptor` preparation ~12–14 min, mobsim ~2 min, ~9.3 GB peak RAM,
~0.8 GB output. Mode shares over 294,120 trips: `av` 29.3 %, `car` 29.2 %,
`car_passenger` 21.7 %, `pt` 3.7 %, `walk` 16.2 %.

Key caveats:

- Keep `flowCapacityFactor = storageCapacityFactor = 0.01`; the population is a
  1 % sample. Raising them without a full population removes all congestion.
- No SF damage or hospital CSV exists yet. `--damageFile` needs SF link IDs, and
  `--redirect` will fail fast because the default `--hospitals` file is Sioux
  Falls.
- `AvAssignment` ignores `carAvailability`/`hasLicense`; AVs are added only to
  links that already allow `car`; `truck` is not represented (no truck links).

Full details:
[`scenarios/Simulation_SF/README.md`](scenarios/Simulation_SF/README.md) and
[`docs/san-francisco-network.md`](docs/san-francisco-network.md).

---

## Key concepts for outsiders

- **Scenarios.** `scenarios/equil/` is a tiny 100-agent toy for fast smoke
  tests. `scenarios/siouxfalls/` is the small benchmark: 24-node/76-link
  network, ~3,600-agent synthetic population (1% of the 360,600-trip OD matrix),
  3 hospital sites, ready-made config + sample damage file. Details in
  `scenarios/siouxfalls/README.md`. `scenarios/Simulation_SF/` is the large
  eqasim San Francisco 1 % scenario: 75,228 persons, 656,557 links, 841 transit
  lines. Details in `scenarios/Simulation_SF/README.md` and
  `docs/san-francisco-network.md`.
- **Sample-rate rule.** Population fraction and network capacity must match:
  regenerating at `--sample X` requires `flowCapacityFactor =
  storageCapacityFactor = X` in the config. Defaults are 0.01 throughout,
  including San Francisco (do not raise SF's factors without a full population).
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
src/test/...                       40 tests (one class per component + end-to-end runs)
scenarios/equil/                   100-agent smoke-test scenario
scenarios/siouxfalls/              network, population, hospitals, config, damage sample + README
scenarios/Simulation_SF/           eqasim SF 1% input data + MATSim 2026 config + README
docs/                              damage-format.md (CSV contract), sweep-regime.md (design brief),
                                   san-francisco-network.md (SF data reference), verification-handoff.md
scripts/                           run_sweep.sh, analyze.py, analysis.ipynb, sweep_compare.py
output/                            run outputs (git-ignored)
agent-helpers/                     long-form plan + decision log + session history (git-ignored)
```

## Troubleshooting

- **Stale jar:** always rebuild after pulling code (`./mvnw -q -DskipTests package`);
  `run_sweep.sh` does this automatically when sources are newer.
- **Slow first build / DTD timeouts:** dependency downloads and MATSim DTD
  fetches need network; transient `Read timed out` failures usually pass on retry.
- **A run never finishes:** check it isn't a true zero-capacity closure (see
  Effective closure above); otherwise inspect `logfileWarningsErrors.log`.
- **Out of disk:** one 10-iteration 1% Sioux Falls cell is a few hundred MB; the
  full sweep is tens of GB. Point `--output`/`--outdir` at a big volume. One San
  Francisco iteration writes ~0.8 GB.
- **San Francisco out-of-memory / very slow startup:** use `-Xmx10g` or more;
  transit preparation (`SwissRailRaptor`) takes ~12–14 min per run, not a hang.

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
