# Verification handoff

This is the short, operational checklist for an agent or reviewer verifying the
AV + earthquake work. It assumes no prior context. For the full project history
see `agent-helpers/MATSIM_AV_EARTHQUAKE_PLAN.md`; for the San Francisco data
see [`docs/san-francisco-network.md`](san-francisco-network.md); for the
migration details see
[`scenarios/Simulation_SF/README.md`](../scenarios/Simulation_SF/README.md).

---

## 1. What this repository adds on top of upstream MATSim

Upstream: `matsim-org/matsim-example-project` (MATSim 2026.0, Java 25).

Added by this project (branch `av-earthquake`):

| Area | Files |
|---|---|
| Run entry point / CLI | `RunAvEarthquake.java` |
| Private AVs | `AvAssignment.java`, `AvSetup.java`, `AvRouting.java` |
| Earthquake damage | `DamageToNetworkChangeEvents.java` |
| Mid-day destination redirect | `QuakeRedirector.java`, `RedirectConfig.java`, `RedirectLogger.java` |
| Sioux Falls build pipeline | `SiouxFallsNetworkConverter.java`, `SiouxFallsPopulationGenerator.java` |
| Scenarios | `scenarios/siouxfalls/`, `scenarios/Simulation_SF/` |
| Sweep + analysis | `scripts/run_sweep.sh`, `scripts/analyze.py`, `scripts/sweep_compare.py`, `scripts/analysis.ipynb` |
| Docs | `docs/damage-format.md`, `docs/sweep-regime.md`, `docs/san-francisco-network.md`, this file |
| Tests | 12 test classes, **40 tests total** |

The upstream `RunMatsimModelImplementation` / `MatsimModelImplementation` /
`RunMatsimWithoutApplication` template classes are still present and untouched.

---

## 2. Preflight

```sh
cd /path/to/quake-av
java -version            # expect Java 25
./mvnw -q test           # expect 40 tests, 0 failures
./mvnw -q -DskipTests package
```

The built jar is `matsim-example-project-0.0.1-SNAPSHOT.jar`.

---

## 3. Fast verification (Sioux Falls)

This is the cheap end-to-end check (~40 s per iteration).

```sh
JAR=matsim-example-project-0.0.1-SNAPSHOT.jar

# 1-iteration baseline
java -cp $JAR org.matsim.project.RunAvEarthquake \
  --config scenarios/siouxfalls/config.xml \
  --output /tmp/sf-baseline --lastIteration 1

# 1-iteration damage + redirect + 50 % AVs
java -cp $JAR org.matsim.project.RunAvEarthquake \
  --config scenarios/siouxfalls/config.xml \
  --output /tmp/sf-quake --lastIteration 1 \
  --damageFile scenarios/siouxfalls/damage-sample.csv \
  --redirect --avShare 0.5 --avSeed 42
```

Expected: exit code 0, `redirect-log.csv` in the output directory, zero stuck
events, and the post-quake link changes documented in
`scenarios/siouxfalls/README.md`.

---

## 4. Large-network verification (San Francisco)

This is heavy: **~16.5 minutes and ~9.3 GB RAM for one iteration.** Use
`-Xmx10g` or more.

```sh
JAR=matsim-example-project-0.0.1-SNAPSHOT.jar

# Baseline, one iteration
java -Xmx10g -cp $JAR org.matsim.project.RunAvEarthquake \
  --config scenarios/Simulation_SF/san_francisco_config-2026.xml \
  --output output-sf-1pct/baseline --lastIteration 0

# 50 % private AVs, one iteration
java -Xmx10g -cp $JAR org.matsim.project.RunAvEarthquake \
  --config scenarios/Simulation_SF/san_francisco_config-2026.xml \
  --output output-sf-1pct/av50 --lastIteration 0 \
  --avShare 0.5 --avSeed 42
```

Do **not** use `java -jar`; use `java -cp <jar> org.matsim.project.RunAvEarthquake`.

### Expected numbers (50 % AV run, verified)

| Check | Expected |
|---|---|
| Exit code | `0` |
| AV assignment | `37,787 / 75,228` persons (50.2 %) |
| Persons / households / facilities | 75,228 / 27,192 / 200,536 |
| Nodes / links / transit lines | 294,405 / 656,557 / 841 |
| Input legs | 347,872 |
| Car links / car-subnet nodes | 634,703 / 285,055 |
| `SwissRailRaptor` preparation | ~740 s |
| Mobsim iteration 0 | ~120 s |
| Total wall time | ~16.5 min |
| Peak RAM | ~9.3 GB |
| Output size | ~804 MB |
| Mode shares (294,120 trips) | `av` 29.3 %, `car` 29.2 %, `car_passenger` 21.7 %, `pt` 3.7 %, `walk` 16.2 % |
| Completed-leg histogram | 256,290 legs: `av` 64,717, `car` 64,801, `car_passenger` 59,927, `pt` 13,394, `walk` 53,451 |

A baseline run has no `av` rows. Counts vary slightly with seed/iteration; the
AV assignment count should be identical for `--avShare 0.5 --avSeed 42`.

### SF data caveats

- `san_francisco_config.xml` (original eqasim) is **untouched** and is expected
  **not** to load on MATSim 2026. Use `san_francisco_config-2026.xml`.
- `--damageFile` needs **SF** link ids; no SF damage file exists yet.
- `--hospitals` defaults to the Sioux Falls file. `--redirect` on SF will fail
  fast (`Hospital link unknown to network`) until an SF hospital CSV exists.
- Do not raise `flowCapacityFactor` / `storageCapacityFactor` above `0.01`
  unless a matching 100 % population is generated.

---

## 5. Known benign warnings

These appear in a successful SF run and are **not** failures:

| Warning | Why it is benign |
|---|---|
| `Setting pathSizeLogitBeta different from zero is experimental` | Inherited eqasim scoring parameter; MATSim only logs it. |
| `The first mode routing (= teleported mode) params that are explicitly defined clear the default ...` | Config defines teleported walk/bike/outside parameters explicitly; intended. |
| `length=0.0 of link id ... may cause problems` | A handful of zero-length SF links from the original eqasim network; they do not stop the run. |
| `coordinate transformation defined both in config and in input file: setting from input file will be used` | Input declares `Atlantis`; intended. |
| `'usePersonIdForMissingVehicleId' is deprecated` | Inherited eqasim config; harmless for fixed-route cars. |
| `A terminally deprecated method in sun.misc.Unsafe ... com.google.inject...` / `Unsupported class file major version 69` / `ASM is probably out of date` | Guice's bundled ASM is older than Java 25. Stack-trace line numbers are missing, but the run completes. Upstream Guice issue, not project code. |
| `Link ... too small: enlarge storage capacity ...` | Caused by the 1 % `storageCapacityFactor`; MATSim quietly raises very small storage capacities. Does not prevent completion. |
| `The first and the last activity do not have the same type` | Normal when the simulation ends before all plans finish (`30:00:00` end time). |
| `If you are using MATSim without time adaptation, you can ignore this warning` | From the events manager / time adaptation check; the project does not use within-day time adaptation. |

Real failures seen during development (must **not** appear after the fixes):

- `java.lang.RuntimeException: Could not find requested vehicle type = truck.`
- `RuntimeException: Could not find requested vehicle type = car_passenger.`
- `Network for mode 'av' has unreachable links and nodes.`

All three are fixed by the `AvSetup` changes described in
`scenarios/Simulation_SF/README.md`.

---

## 6. Known limitations

These are deliberate scope choices, not bugs:

1. **No eqasim mode choice.** `DiscreteModeChoice` is removed. Mode shares are
   fixed by the input plans; only re-routing is active.
2. **`AvAssignment` ignores `carAvailability` and `hasLicense`.** It is a
   seeded hash split, so it can mark a person AV who could not drive in eqasim.
3. **AVs are private cars plus an `av` mode.** No DRT/shared fleet. AV capacity
   effect is a configurable flow-efficiency factor (default 2.0).
4. **AVs use only car links.** `av` is added only to links that already allow
   `car`, so AVs cannot route on rail/ferry/bus-only infrastructure.
5. **No SF hospital or damage data yet.** Redirect runs need a new
   `hospitals.csv` with SF link ids; damage needs SF link ids.
6. **No truck/freight.** SF has no truck links; `truck` is removed from the
   network/analyzed modes.
7. **Effective closures, not absolute zero.** `closed=true` applies
   capacity × 0.02 and freespeed × 0.3. True zero-capacity mid-run closures
   hang QSim (see `docs/damage-format.md`).
8. **Single-day regime (b).** Each iteration re-simulates the same 24 h day
   with the quake active; iteration 0 is the surprise readout. Regime (a) is
   documented but not implemented (`docs/sweep-regime.md`).
9. **Trips run shorter under quake than no-quake** because redirect truncates
   long commutes. Compare within-variant only.
10. **SF runs are not part of the 60-cell sweep.** The sweep remains Sioux
    Falls only; SF is the large-network verification case.

---

## 7. Where to look

| Question | File |
|---|---|
| What changed vs upstream? | this file, section 1; root `README.md` |
| How does the SF scenario work? | `scenarios/Simulation_SF/README.md` |
| What is in the SF network/population? | `docs/san-francisco-network.md` |
| Damage CSV contract / closure semantics | `docs/damage-format.md` |
| Sweep design (a) vs (b) | `docs/sweep-regime.md` |
| Project plan, decisions, session history | `agent-helpers/MATSIM_AV_EARTHQUAKE_PLAN.md` |
| Sioux Falls benchmark details | `scenarios/siouxfalls/README.md` |
| AV setup/routing code | `src/main/java/org/matsim/project/AvSetup.java`, `AvRouting.java` |

---

## 8. Repo state notes

- Work is on branch **`av-earthquake`**.
- `agent-helpers/` and `extra-data/` are git-ignored by design; the built
  scenario files in `scenarios/` are tracked/handed over separately.
- `scenarios/Simulation_SF/` was added as an untracked folder in the working
  tree; commit it before pushing.
- `output/`, `output-sf-1pct/`, and `target/` are git-ignored.
- The original `scenarios/Simulation_SF/san_francisco_config.xml` and all
  `.xml.gz` inputs are the untouched eqasim files.
