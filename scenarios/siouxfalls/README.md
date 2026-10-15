# Sioux Falls scenario (tracked build output)

Built from the raw TNTP data in `extra-data/SiouxFalls/` (gitignored, local only).
Raw source: `SiouxFalls_net.tntp` (76 links) + `SiouxFalls_node.tntp` (24 lon/lat nodes),
canonical TNTP benchmark (24 zones, 360,600 trips total, BPR B=0.15/p=4).

## How `network.xml` was built

```
./mvnw compile exec:java -Dexec.mainClass=org.matsim.project.SiouxFallsNetworkConverter
# reads extra-data/SiouxFalls/SiouxFalls_{net,node}.tntp
# writes scenarios/siouxfalls/network.xml
```

Converter: `src/main/java/org/matsim/project/SiouxFallsNetworkConverter.java`
(verified against MATSim 2026.0; uses `new NetworkCleaner().run(network)` —
`NetworkUtils.runNetworkCleaner` from the plan no longer exists in this version).

## Assumptions (per handoff plan)

- Length = TNTP length x 1000 m. Free-flow time read as minutes:
  freespeed = length_m / (fft_min x 60). Length == fft on all 76 links,
  so every link is ~16.67 m/s (60 km/h).
- Capacity = TNTP capacity directly (veh/h, 4,824-25,900).
- Lanes = max(1, round(capacity / 2000)).
- Modes = `car` only. Link ids = `from_to` (Phase 3 damage-mapping key).
- Nodes: equirectangular projection about dataset mean lon/lat (R = 6371000 m).
- NetworkCleaner keeps all 24 nodes / 76 links.

## Population (`population-1pct.xml`, 3,608 agents)

```
./mvnw compile exec:java -Dexec.mainClass=org.matsim.project.SiouxFallsPopulationGenerator \
  -Dexec.args="--sample 0.01"
# --sample 0.001  ->  ~360 agents (laptop smoke test, verified nested subset)
# --sample 0.01   ->  ~3.6k agents (laptop default)
# --sample 0.05/0.10 -> ~18k/36k agents (server)
# --sample 1.0    ->  360,600 agents (mini-server/cluster test)
# other flags: --seed 4711 --hospitalNodes 1,13,18 --od ... --network ... --plans ... --hospitals ...
```

Generator: `src/main/java/org/matsim/project/SiouxFallsPopulationGenerator.java`.
Sampling is nested via `AvAssignment.drawUniform("o-d-i", seed) < sample`, so every
agent at 0.1% is also present at 1% with the same seed (verified). Plans are
home-work-home, `car`, home end times uniform 6-9am (seeded per-person draw),
work duration 8h. Set config `flowCapacityFactor = storageCapacityFactor = sample`.

## Hospitals (`hospitals.csv`)

Default nodes **1, 13, 18** — the max-min-spread triple (pairwise >= ~9.6 km),
covering NW / SW / E so post-quake arrival times vary by geography. Node 10 is
the highest-degree hub (5 links) and a possible 4th site. Format
`hospital_id,node,link,x,y`, link = nearest MATSim link to the node; override
with `--hospitalNodes`. Phase 4 will redirect agents to the nearest hospital link.

## Config (`config.xml`, 1% baseline)

Wires `network.xml` + `population-1pct.xml` with `flowCapacityFactor =
storageCapacityFactor = 0.01` (qsim module), `h`/`w` scoring plus a `hospital`
activity type (unused until Phase 4), equil-style replanning
(BestScore 0.9 / ReRoute 0.1), 10 iterations. Run it:

```
java -jar matsim-example-project-0.0.1-SNAPSHOT.jar run --config scenarios/siouxfalls/config.xml
# or via the run class:
./mvnw compile exec:java -Dexec.mainClass=org.matsim.project.RunAvEarthquake \
  -Dexec.args="--config scenarios/siouxfalls/config.xml --output /tmp/sf-baseline --lastIteration 10"
```

Measured 2026-10-13: 1 iteration, 3,608 agents, ~39 s wall (incl. JVM startup)
on this laptop. When regenerating at another sample rate, set both qsim factors
to that rate (e.g. 0.001 / 0.05 / 0.10).

## Damage sample (`damage-sample.csv`, Phase 3)

Per-link quake damage at default `t_quake` 08:30 (contract: `docs/damage-format.md`).
Sample closes 8_9 (effective closure) and degrades 10_11/11_10/9_8 to 50%:

```
./mvnw compile exec:java -Dexec.mainClass=org.matsim.project.RunAvEarthquake \
  -Dexec.args="--config scenarios/siouxfalls/config.xml --output /tmp/sf-damage --lastIteration 1 --damageFile scenarios/siouxfalls/damage-sample.csv"
```

Verified 2026-10-14, 1 iteration, link entries before/after 08:30:

| link | damage | baseline after | damage after |
|---|---|---|---|
| 8_9 | closed (eff.) | 10 | 2 (−80%) |
| 9_8 | 0.5 / 0.5 | 4 | 1 |
| 11_10 | 0.5 / 0.5 | 881 | 612 (−31%) |
| 10_11 | 0.5 / 0.5 | 1102 | 1023 (−7%) |

Pre-quake counts identical (control holds), zero stuck events, run completes on
the same ~40 s budget. Note 8_9 keeps a 2-vehicle trickle (effective closure,
not absolute zero — see `docs/damage-format.md` for why).

## Separate AV mode (`av`, vision: AVs see damage, conventionals don't)

AV persons travel on `av`-mode legs with `av` vehicles; links, qsim main modes,
scoring params, and the routing `av` network mode are wired at runtime
(`AvSetup` + `prepareConfig`, only when `--avShare > 0`; baseline untouched).
Routing asymmetry uses stock MATSim machinery: `av` rides the default observed
time-dependent router; in damage runs `car` travel times are rebound to a
pre-quake snapshot (`AvRouting`: frozen base freespeeds via
`addTravelTimeBinding("car")` override). Without damage both modes observe —
the asymmetry activates with the quake.

Proven on equil (`AvRoutingTest`, through-link 10 closed at 06:01, mixed run):
0 AV routes touch link 10, conventional routes hold course identical to the
no-damage run. Sioux Falls (mixed 50/50, artery effectively closed at 08:30,
post-quake departures): AV 656 vs conventional 761 users — conventional counts
identical across damage sets (761 mild / 761 severe: perfectly blind), AV use
falls monotonically with severity (706 → 656). Gap size is rational route
choice (1% demand, short queues), not avoidance failure.

## Redirect (`--redirect`, Phase 4)

`t_quake` destination reassignment with D5 default splits (all CLI-tunable):

```
./mvnw compile exec:java -Dexec.mainClass=org.matsim.project.RunAvEarthquake \
  -Dexec.args="--config scenarios/siouxfalls/config.xml --output /tmp/sf-quake --lastIteration 1 --damageFile scenarios/siouxfalls/damage-sample.csv --redirect"
# flags: --tQuake 30600 --redirectSeed 9182 --homeShare 0.60 --hospitalShare 0.17
#        --stayShare 0.15 --otherShare 0.08 --hospitals scenarios/siouxfalls/hospitals.csv
```

Verified 2026-10-14, 1 iteration, 3,608 agents: home 2,137 (59%) / hospital 608
(17%) / stay 556 (15%) / random 307 (9%); hospital link 16_18 entries 58 → 575;
final activities hospital 608/608, home+random 2,444/2,444, stay 555/556 with a
single stuck event trailing the last agent. Per-iteration log at
`<outputDir>/redirect-log.csv` (iteration,agent,time,outcome,orig/new dest,mode,is_av).

## Sweep (Phase 5)

```sh
# Full grid: 6 shares x 5 seeds x {quake, noquake} = 60 cells (~7 min/cell at 1%, use --jobs)
scripts/run_sweep.sh --jobs 4
# Smoke: 1-iteration mini-grid
scripts/run_sweep.sh --config scenarios/equil/config-2026.xml --shares "0 100" \
  --seeds 7 --iterations 1 --jobs 2 --damage <equil-damage.csv> --tQuake 21660 \
  --hospitals <equil-hospitals.csv> --outdir /tmp/sweep-smoke
```

Cells land in `output/sweep/av{share}_seed{seed}_{quake|noquake}/` with events,
trips, and redirect logs; `cell-status.txt` tracks OK/FAIL and a summary table
prints redirect outcomes per quake cell. AV/redirect seeds fixed across cells so
only AV share varies. Regime (b): quake active every iteration, iteration 0
reads as the surprise day — see `docs/sweep-regime.md` (regime (a) documented
as a future option for the advisor).
