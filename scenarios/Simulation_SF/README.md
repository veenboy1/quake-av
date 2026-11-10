# San Francisco scenario (eqasim data, MATSim 2026 runner)

This folder contains the original **eqasim San Francisco 1 % sample** and a
second config, `san_francisco_config-2026.xml`, that runs the same input data
with this repository's MATSim 2026 AV/earthquake code.

The original eqasim config is **kept unchanged**. All migration work is in the
new `-2026` config and in the small runtime helpers (see
[Code changes required by this scenario](#code-changes-required-by-this-scenario)).

> Detailed network/population reference:
> [`docs/san-francisco-network.md`](../../docs/san-francisco-network.md).
> Verification checklist:
> [`docs/verification-handoff.md`](../../docs/verification-handoff.md).

---

## Why a second config?

The data in this folder was generated for **eqasim 1.2.1 / MATSim 12 / Java 11**
(the original config is dated 2021-02-11, the data files 2021-04-27). This
repository is on **MATSim 2026.0 / Java 25**. The original config cannot run
here because it:

- uses `FastAStarLandmarks` (removed in current MATSim),
- sets config parameters/modules that no longer exist,
- contains eqasim and `DiscreteModeChoice` (DMC) modules that are not on this
  repository's classpath,
- uses the old module names (`controler`, `strategy`, `planCalcScore`, ...).

Rather than port eqasim itself (Plan A), we use **Plan B**:

> Keep the eqasim input files (network, population, facilities, households,
> transit) and run them with this repository's `RunAvEarthquake` /
> `AvSetup` / `AvRouting` code, with eqasim mode choice switched off.

**Trade-off:** the San Francisco mode-choice model, eqasim utility functions,
cost models and calibration are no longer active. Mode shares are fixed to the
input plans; the replanning strategy only re-routes trips. That is acceptable
for this project because the focus is driving/AV behaviour, not eqasim mode
choice.

---

## What is in this folder

| File | Size | Role |
|---|---:|---|
| `san_francisco_config.xml` | 55 KB | Original eqasim 1.2.1 config. **Do not edit.** Kept for reference / provenance. |
| `san_francisco_config-2026.xml` | 42 KB | New MATSim 2026 config used by `RunAvEarthquake`. |
| `san_francisco_network.xml.gz` | 23 MB | Bay Area road/transit network. |
| `san_francisco_population.xml.gz` | 70 MB | 75,228 persons with routed plans. |
| `san_francisco_facilities.xml.gz` | 4.1 MB | Activity facilities. |
| `san_francisco_households.xml.gz` | 447 KB | 27,192 households and car availability. |
| `san_francisco_transit_schedule.xml.gz` | 7.3 MB | 841 transit lines. |
| `san_francisco_transit_vehicles.xml.gz` | 89 KB | Transit vehicles. |
| `README.md` | this file | Migration notes and usage. |

All `.xml.gz` files are the **original eqasim inputs**, unchanged.

### Input dimensions (verified by loading in MATSim 2026)

| Item | Count |
|---|---:|
| Persons | 75,228 |
| Households | 27,192 |
| Facilities | 200,536 |
| Network nodes | 294,405 |
| Network links | 656,557 |
| Transit lines | 841 |
| Legs in population | 347,872 |

Leg modes in the input population:

| Mode | Legs |
|---|---:|
| `car` | 171,810 |
| `walk` | 82,841 |
| `car_passenger` | 63,766 |
| `pt` | 29,455 |

The scenario is a **1 % sample**. The network capacity factors must match it:

```xml
<module name="qsim">
  <param name="flowCapacityFactor" value="0.01" />
  <param name="storageCapacityFactor" value="0.01" />
</module>
```

Do not raise these to 1.0 unless you also regenerate the population at 100 %.

---

## Exact changes in `san_francisco_config-2026.xml`

### Removed modules

These modules belong to eqasim / MATSim 12 and are not available or not used
here:

- `DiscreteModeChoice` — the whole eqasim mode-choice model.
- `eqasim` — eqasim costs, estimators, sample size, trip analysis.
- `eqasim:calibration` — eqasim calibration output.
- `ptCounts` — public-transit count calibration.
- `JDEQSim` — unused because `mobsim = qsim`.

Removing `DiscreteModeChoice` is the main functional change: **there is no
mode choice anymore**. The `changeMode` and `subtourModeChoice` modules are
left in the file, but no active strategy uses them. The active replanning
strategies are `BestScore` (0.9) and `ReRoute` (0.1).

### Renamed modules to MATSim 2026 names

| Old name | New name |
|---|---|
| `controler` | `controller` |
| `strategy` | `replanning` |
| `planCalcScore` | `scoring` |
| `planscalcroute` | `routing` |
| `parallelEventHandling` | `eventsManager` |
| `TimeAllocationMutator` | `timeAllocationMutator` |

### Renamed parameters

| Old | New |
|---|---|
| `BrainExpBeta` | `brainExpBeta` |
| `PathSizeLogitBeta` | `pathSizeLogitBeta` |
| `createGraphs` | `createGraphsInterval` (set to `1`) |

### Removed parameters

- `global.insistingOnDeprecatedConfigVersion`
- `qsim.usingThreadpool`
- `travelTimeCalculator.travelTimeCalculator`

### Routing / traffic

- `controller.routingAlgorithmType`: `FastAStarLandmarks` → `SpeedyALT`.
- `routing.networkModes`: `car,car_passenger,truck` → `car,car_passenger`.
- `travelTimeCalculator.analyzedModes`: `car,truck,car_passenger` →
  `car,car_passenger`.

Why remove `truck`? San Francisco links allow
`car,taxi,car_passenger` (plus transit modes on some links); **no link allows
`truck`**. Keeping `truck` would make MATSim create truck vehicles that can
never use the network. `car_passenger` is kept because 63,766 input legs use
it.

### Replanning

| Setting | Original | New |
|---|---|---|
| `maxAgentPlanMemorySize` | `1` | `5` |
| `planSelectorForRemoval` | `NonSelectedPlanSelector` | `WorstPlanSelector` |
| Strategies | `DiscreteModeChoice` 0.1, `KeepLastSelected` 0.9 | `BestScore` 0.9, `ReRoute` 0.1 |
| `fractionOfIterationsToDisableInnovation` | `Infinity` | `Infinity` (unchanged) |

The original used one plan per person because DMC enforced a single plan. With
DMC gone, `BestScore` needs a few plans to select from, hence memory size 5.

### Run control

- `lastIteration`: `30` → `10`.
- `outputDirectory`: `simulation_output` → `./output-sf-1pct`.

### Preserved on purpose

- `flowCapacityFactor = storageCapacityFactor = 0.01` (1 % sample).
- Transit stays enabled (`transit`, `swissRailRaptor`, `transitRouter`), because
  the population contains ~29 k `pt` legs and we do not filter or convert them.
- `global.numberOfThreads = 24`, `qsim.numberOfThreads = 12`.
- `vehiclesSource = defaultVehicle` in the file. `RunAvEarthquake` switches
  this to `modeVehicleTypesFromVehiclesData` at runtime when `--avShare > 0`.

---

## Code changes required by this scenario

These are in the Java code, not in the config:

1. **Fallback vehicle types** in `AvSetup`.
   With `qsim.vehiclesSource = modeVehicleTypesFromVehiclesData`, MATSim
   requires a vehicle type for every configured network/main mode. San
   Francisco routes `car_passenger` on the network, but the scenario does not
   define a `car_passenger` vehicle type. `AvSetup` now adds a fallback type
   for each configured network/main mode that has no type yet. This is what
   lets a 50 % AV run get past `PrepareForSim`
   (`Could not find requested vehicle type = car_passenger/truck`).

2. **Vehicle-type ids are created on demand** (`AvSetup.carTypeId()` /
   `AvSetup.avTypeId()`), not stored in static `Id` fields. MATSim's `IdMap`
   indexes by `Id.index()`; the test suite resets the global id caches between
   runs, and a static `Id` would then point at the wrong map slot. Creating
   ids after the scenario is loaded keeps them consistent.

3. **`av` only on car links** in `AvSetup`. Adding `av` to every link made the
   per-mode routing network include disconnected rail/ferry/bus-only links;
   MATSim then aborted with
   `Network for mode 'av' has unreachable links and nodes`. The `av` mode is
   now added only to links that already allow `car`.

4. **Tests:** `AvSetupTest` has a regression test for the `car_passenger`
   fallback type, and all type-id assertions use the on-demand id methods.

---

## How to run

Build the fat jar once after pulling code:

```sh
cd /path/to/quake-av
./mvnw -q -DskipTests package
```

Then run the scenario. Use `java -cp`, **not** `java -jar` (the jar manifest
starts the upstream template class):

```sh
JAR=matsim-example-project-0.0.1-SNAPSHOT.jar

# Baseline, one iteration (no AVs)
java -Xmx10g -cp $JAR org.matsim.project.RunAvEarthquake \
  --config scenarios/Simulation_SF/san_francisco_config-2026.xml \
  --output output-sf-1pct/baseline \
  --lastIteration 0

# 50 % private AVs, one iteration
java -Xmx10g -cp $JAR org.matsim.project.RunAvEarthquake \
  --config scenarios/Simulation_SF/san_francisco_config-2026.xml \
  --output output-sf-1pct/av50 \
  --lastIteration 0 \
  --avShare 0.5 --avSeed 42
```

Remove `--lastIteration 0` (or set another value) to use the config's 10
iterations. Each iteration re-simulates the same day; iteration 0 is the
un-adapted day.

### Runtime expectations

San Francisco is much heavier than the Sioux Falls benchmark (3,608 persons /
76 links):

- Scenario loading: ~20 s.
- SwissRailRaptor preparation: **~12–14 minutes** on the first iteration.
- One mobsim iteration: **~2 minutes**.
- Total wall time for a one-iteration smoke run: **~16–17 minutes**.
- Peak memory: **~9.3 GB** with `-Xmx10g`; use `-Xmx10g` or more.
- Output: roughly **0.8 GB per iteration** (events, plans, trips, link stats,
  graphs). A 10-iteration run is several GB.

For quick checks use `--lastIteration 0`. For experiments that include an
earthquake, use the same runner flags as Sioux Falls (`--damageFile`,
`--redirect`, etc.); see the repository root `README.md`.

---

## AV / earthquake behaviour on this scenario

When `--avShare > 0`:

- `AvSetup` assigns AV persons with the hash-based `AvAssignment` (seeded,
  nested shares), rewrites their `car` legs to `av`, and assigns the `av`
  vehicle type. Conventional persons keep `car`.
- `av` is added to the routing network modes, QSim main modes and travel-time
  analyzer at runtime. It is also added to every link that already allows
  `car` (and only those links), so the `av` routing network mirrors the car
  network instead of pulling in disconnected rail/ferry/bus-only links.
- AVs use the observed travel-time router; conventional cars use the same
  router unless a damage file is present, in which case `AvRouting` freezes
  conventional routing to pre-quake free-flow times. Without damage, the two
  modes are treated identically apart from the AV flow-efficiency factor.

### Caveats specific to this dataset

- **No eqasim mode choice / calibration.** Mode shares are whatever the input
  plans contain; `ReRoute` cannot switch a person to another mode.
- **`AvAssignment` ignores `carAvailability` and `hasLicense`.** It is a
  simple hash split, so it may assign AVs to persons who could not drive in
  eqasim.
- **AVs use the car subnetwork.** `av` is intentionally not added to
  transit-only links (rail, ferry, bus-only); those links have no `car` mode.
  This keeps MATSim's per-mode network consistency check happy and prevents AVs
  from routing on rail/ferry infrastructure.
- **No San Francisco damage or hospital files yet.** The default
  `--hospitals` path points at `scenarios/siouxfalls/hospitals.csv`, whose link
  ids do not exist in the Bay Area network, so `--redirect` will fail until
  you provide an SF hospital CSV. A damage CSV can be produced later with SF
  link ids (same format as `docs/damage-format.md`).
- **Transit is simulated but not chosen.** Removing DMC means `pt` legs stay
  `pt`; they are not re-optimised.
- **`truck` is absent.** The network has no truck links, so freight is not
  represented.

---

## Verification performed

- `san_francisco_config-2026.xml` loads with MATSim 2026.
- The full scenario loads:
  `75,228` persons, `294,405` nodes, `656,557` links, `200,536` facilities,
  `27,192` households, `841` transit lines.
- `./mvnw test` passes the full test suite (40 tests), including the new
  `car_passenger` fallback-type regression test.
- A one-iteration **50 % AV** smoke run
  (`--lastIteration 0 --avShare 0.5 --avSeed 42`) completed with exit code 0:

  | Check | Result |
  |---|---|
  | AV assignment | `37,787 / 75,228` persons (50.2 %) |
  | Fallback vehicle type | `car_passenger` added |
  | `SwissRailRaptor` preparation | `740 s` (~12.3 min) |
  | Mobsim (iteration 0) | ~`120 s` |
  | Total wall time | ~`16.5 min` |
  | Peak RAM | ~`9.3 GB` (`-Xmx10g`) |
  | Output size | `804 MB` |

  Mode shares over `294,120` trips (MATSim iteration-0 `ModeStats`):
  `av` 29.3 %, `car` 29.2 %, `car_passenger` 21.7 %, `pt` 3.7 %,
  `walk` 16.2 %. The iteration-0 leg histogram reports 256,290 legs:
  `av` 64,717, `car` 64,801, `car_passenger` 59,927, `pt` 13,394,
  `walk` 53,451. MATSim's leg histogram counts completed legs; a large number
  of agents are still active when the 30:00 simulation end time is reached, so
  the completed-leg total is lower than the input file's 347,872 legs.

- The run log contains neither the previous
  `Could not find requested vehicle type` failure nor the
  `Network for mode 'av' has unreachable links and nodes` failure.

The untouched original `san_francisco_config.xml` is expected to fail on
MATSim 2026; use the `-2026` config instead.
