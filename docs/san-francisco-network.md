# San Francisco network and population reference

This document is the detailed reference for the **eqasim San Francisco 1 %
sample** that now ships in `scenarios/Simulation_SF/`. It records what the data
contains, how it was produced, what it does and does not support, and the facts
a verification agent needs before running or extending it.

For the migration itself (why there are two configs, exactly which config
parameters changed, and the code fixes) see
[`scenarios/Simulation_SF/README.md`](../scenarios/Simulation_SF/README.md).

---

## 1. Provenance

| Item | Value |
|---|---|
| Source | eqasim San Francisco example, **1 % sample** |
| eqasim version | **1.2.1** |
| Original MATSim version | **MATSim 12** |
| Original Java version | **Java 11** |
| Input file dates | `2021-04-27` |
| Original config date | `2021-02-11` |
| Current runner | this repository: **MATSim 2026.0 / Java 25** |
| Coordinate system | `Atlantis` (eqasim's projected system; not lon/lat) |

The input files are used **unchanged**. The original
`scenarios/Simulation_SF/san_francisco_config.xml` is kept only for provenance;
it does **not** load in MATSim 2026. The runnable config is
`scenarios/Simulation_SF/san_francisco_config-2026.xml`.

---

## 2. Scenario dimensions (verified by loading in MATSim 2026)

| Item | Count |
|---|---:|
| Persons | 75,228 |
| Households | 27,192 |
| Facilities | 200,536 |
| Network nodes | 294,405 |
| Network links | **656,557** |
| Links allowing `car` | **634,703** |
| Nodes in the car subnetwork | 285,055 |
| Transit lines | 841 |
| Transit stop facilities | 23,374 |
| Legs in the input population | 347,872 |
| Activities in the input population | 423,100 |

### Input leg modes

| Mode | Legs |
|---|---:|
| `car` | 171,810 |
| `walk` | 82,841 |
| `car_passenger` | 63,766 |
| `pt` | 29,455 |

`car` + `car_passenger` = 235,576 legs (67.7 % of all legs) are road-based
demand. `pt` is 8.5 % of legs but contains 53,752 of the 423,100 activities
(`pt interaction`), because each transit trip expands into access/egress
interactions.

### Input activity types

| Activity | Count |
|---|---:|
| `home` | 175,175 |
| `other` | 57,855 |
| `pt interaction` | 53,752 |
| `leisure` | 53,273 |
| `work` | 31,449 |
| `shop` | 27,745 |
| `education` | 13,474 |
| `business` | 10,377 |

The population contains **no hospital or shelter activities**. A
redirect/hospital run needs an SF hospital file (see section 5).

---

## 3. Network mode composition

Every link's allowed modes were counted directly from
`san_francisco_network.xml.gz`. The full distribution is:

| Allowed modes | Links | Share |
|---|---:|---:|
| `car,taxi,car_passenger` | 568,966 | 86.66 % |
| `bus,pt,car,taxi,car_passenger` | 65,737 | 10.01 % |
| `bus,artificial` | 8,947 | 1.36 % |
| `stopFacilityLink,bus,artificial` | 6,636 | 1.01 % |
| `pt,rail,train` | 2,892 | 0.44 % |
| `bus,pt` | 1,644 | 0.25 % |
| `artificial,tram` | 438 | 0.07 % |
| `stopFacilityLink,artificial,tram` | 419 | 0.06 % |
| `rail` | 418 | 0.06 % |
| `stopFacilityLink,artificial,cable car` | 116 | 0.02 % |
| `artificial,cable car` | 115 | 0.02 % |
| `artificial,subway` | 101 | 0.02 % |
| `stopFacilityLink,artificial,subway` | 48 | <0.01 % |
| `artificial,ferry` | 38 | <0.01 % |
| `stopFacilityLink,artificial,ferry` | 18 | <0.01 % |
| `artificial,rail` | 16 | <0.01 % |
| `stopFacilityLink,artificial,rail` | 8 | <0.01 % |
| **Total** | **656,557** | 100 % |

Key consequences:

- **634,703 links allow `car`.** Those are exactly the two `car`-containing
  rows. The AV mode is added only to these links at runtime.
- The car network splits into **634,703 links / 285,055 nodes** as a
  per-mode subnetwork. It passes MATSim's `NetworkRoutingProvider`
  reachability check once `av` is added only to car links.
- **No link allows `truck`.** `truck` is therefore removed from
  `routing.networkModes` and `travelTimeCalculator.analyzedModes`; freight is
  not represented in this scenario.
- Rail, subway, tram, ferry, cable-car, bus-only and stop-facility links are
  **not** part of the AV/car network. Adding `av` to every link (the naive
  approach) makes the per-mode network disconnected and MATSim aborts with
  `Network for mode 'av' has unreachable links and nodes`.
- `stopFacilityLink` edges exist alongside transit infrastructure. They are
  artificial access edges, not roads.

---

## 4. Sample rate and capacity factors

This is a **1 % population sample**. The config therefore sets:

```xml
<module name="qsim">
  <param name="flowCapacityFactor" value="0.01" />
  <param name="storageCapacityFactor" value="0.01" />
</module>
```

Rules:

- Keep both factors at **0.01** for this population.
- Do **not** raise them to 1.0. That would multiply link capacity by 100 while
  keeping 1 % of the demand, removing essentially all congestion.
- If a full 100 % population is ever regenerated, set both factors back to the
  matching sample fraction in the same commit. The population and factors must
  always agree.
- The Sioux Falls convention (population fraction = capacity factor) applies
  here too; it is already encoded in the config.

---

## 5. What this scenario does **not** contain yet

These are the main gaps a verification team should know about.

1. **No San Francisco damage CSV.**
   `--damageFile` accepts the normal per-link contract from
   [`docs/damage-format.md`](damage-format.md), but every `link_id` must be a
   real Bay Area MATSim link id. There is no SF earthquake damage file in the
   repository yet; do not use the Sioux Falls `damage-sample.csv` (its ids such
   as `8_9` do not exist in this network).

2. **No San Francisco hospital file.**
   `--hospitals` defaults to `scenarios/siouxfalls/hospitals.csv`, whose link
   ids are Sioux Falls link ids. `--redirect` fails fast on these
   (`Hospital link unknown to network`). Redirect runs on SF need a new
   `hospitals.csv` with SF link ids in the same
   `hospital_id,node,link,x,y` format.

3. **No eqasim mode choice.**
   The `DiscreteModeChoice` module is removed. Mode shares are fixed by the
   input plans; the replanning loop only re-routes. This is intentional for an
   AV/driving study, but it means the scenario cannot answer eqasim
   mode-choice/calibration questions.

4. **No truck / freight demand.**
   No network link allows `truck`, and there are no truck legs.

5. **No scenario-specific scoring calibration.**
   The `-2026` config keeps the original eqasim scoring parameter values, but
   without eqasim estimators and calibration the scores are not the eqasim
   calibrated utilities. Treat absolute scores as diagnostic only.

---

## 6. Runtime characteristics

Measured on the development laptop with the final 50 % AV one-iteration run
(`--lastIteration 0 --avShare 0.5 --avSeed 42`, `-Xmx10g`):

| Phase | Time |
|---|---:|
| Scenario loading | ~20 s |
| `SwissRailRaptor` preparation | **740 s (~12.3 min)** |
| Mobsim iteration 0 | ~120 s |
| **Total wall time** | **~16.5 min** |
| Peak RAM | **~9.3 GB** |
| Output size | **804 MB** |

Use **`-Xmx10g`** or more. Transit preparation dominates; a 10-iteration run is
roughly one transit preparation plus ten mobsim iterations and several GB of
output. For verification, use `--lastIteration 0`.

Sioux Falls remains the fast benchmark (~40 s/iteration, 3,608 persons);
San Francisco is the **large-network verification case**.

---

## 7. Verified results to expect

Final 50 % AV smoke run (exit code 0):

| Check | Result |
|---|---|
| AV assignment | **37,787 / 75,228** persons (50.2 %) |
| Fallback vehicle type | `car_passenger` added (SF does not define one) |
| `Could not find requested vehicle type` | absent |
| `Network for mode 'av' has unreachable links and nodes` | absent |
| Mode shares (294,120 trips) | `av` 29.3 %, `car` 29.2 %, `car_passenger` 21.7 %, `pt` 3.7 %, `walk` 16.2 % |
| Leg histogram (256,290 completed legs) | `av` 64,717, `car` 64,801, `car_passenger` 59,927, `pt` 13,394, `walk` 53,451 |

The leg histogram is lower than the input file's 347,872 legs because MATSim
counts **completed** legs and many agents are still active at the `30:00:00`
simulation end time.

A baseline (`--avShare 0`) run should show `car`/`car_passenger`/`pt`/`walk`
only; the AV shares above are specific to the 50 % AV run.

---

## 8. Commands

Build once:

```sh
./mvnw -q -DskipTests package
```

Baseline, one iteration:

```sh
JAR=matsim-example-project-0.0.1-SNAPSHOT.jar
java -Xmx10g -cp $JAR org.matsim.project.RunAvEarthquake \
  --config scenarios/Simulation_SF/san_francisco_config-2026.xml \
  --output output-sf-1pct/baseline \
  --lastIteration 0
```

50 % AV, one iteration:

```sh
java -Xmx10g -cp $JAR org.matsim.project.RunAvEarthquake \
  --config scenarios/Simulation_SF/san_francisco_config-2026.xml \
  --output output-sf-1pct/av50 \
  --lastIteration 0 \
  --avShare 0.5 --avSeed 42
```

Do **not** use `java -jar`; its manifest starts the upstream template class.
Use `java -cp <jar> org.matsim.project.RunAvEarthquake ...`.

---

## 9. AV / earthquake behaviour on this network

When `--avShare > 0`:

- `AvAssignment` hash-splits persons (seeded, nested across shares). It
  **ignores `carAvailability` and `hasLicense`**, so an AV may be assigned to a
  person eqasim would not have allowed to drive.
- Each AV person's `car` legs are rewritten to `av`; conventional persons stay
  on `car`.
- `AvSetup` adds `av` only to links that already allow `car`, creates the
  `av` + `car` vehicle types, and creates fallback types for every configured
  network/main mode (notably `car_passenger`).
- Vehicle-type ids are created on demand (`carTypeId()` / `avTypeId()`) to avoid
  MATSim's global `IdMap` cache-reset problem.
- With a damage file, AVs route on observed (post-quake) travel times while
  conventional `car` routing is bound to a pre-quake snapshot (`AvRouting`).
  Without damage, both modes observe the same network apart from the AV
  flow-efficiency factor.

When `--avShare = 0`, `AvSetup` does not run and the scenario is a stock MATSim
run of the eqasim inputs.

---

## 10. Quick verification checklist

- [ ] `./mvnw test` → **40 tests**, all green.
- [ ] `san_francisco_config-2026.xml` loads.
- [ ] Loaded counts match section 2: 75,228 persons, 27,192 households,
      200,536 facilities, 294,405 nodes, 656,557 links, 841 transit lines.
- [ ] Car subnet is 634,703 links / 285,055 nodes; no `truck` links.
- [ ] 50 % AV one-iteration run exits 0 with `37,787` AV persons.
- [ ] Run log has no vehicle-type or unreachable-`av`-network errors.
- [ ] Mode-share/leg counts are in the same ballpark as section 7 (exact
      counts depend on seed and iteration count).
- [ ] Original `san_francisco_config.xml` is unchanged and still fails on
      MATSim 2026 (expected).
