# Earthquake damage file contract (D4)

The externally simulated earthquake output is a **per-link CSV** using the
converter's link IDs (`from_to`, e.g. `10_11`). The converter turns each row
into a MATSim `NetworkChangeEvent` at `t_quake` (default 08:30 = 30600 s).

## Format

```csv
link_id,damage_state,freespeed_factor,capacity_factor,closed
10_11,severe,,,true
11_10,severe,,,true
8_9,moderate,0.5,0.5,false
9_8,moderate,0.5,0.5,false
```

| Column | Meaning |
|---|---|
| `link_id` | MATSim link ID; must exist in the network (unknown IDs fail fast) |
| `damage_state` | Label only (`none/minor/moderate/severe`); does not affect the event |
| `freespeed_factor` | Multiplies link freespeed (`FACTOR` change); (0,1]; ignored when closed (effective-closure × 0.3 applies) |
| `capacity_factor` | Multiplies link flow capacity (`FACTOR` change); (0,1]; ignored when closed (effective-closure × 0.02 applies) |
| `closed` | `true` → effective closure (capacity × 0.02, freespeed × 0.3 — see semantics below); `false` → graded factors applied |

## Closed-link semantics (verified on MATSim 2026.0)

`closed=true` applies an **effective closure**: capacity × 0.02, freespeed × 0.3
(`CLOSED_CAPACITY_FACTOR` / `CLOSED_FREESPEED_FACTOR`), not absolute zero.

Why not true zero capacity: zero-capacity mid-run closures hang the QSim
**irrecoverably** (observed 2026-10-14, twice). Fixed-route agents queue behind
the closure; node-waiters are never stuck-removed and on-link vehicles never
drain, so the run never finishes — 1,607 agents trapped past sim-hour 10,000 on
Sioux Falls, and all 100 agents trapped past hour 5,000 on tiny equil. This held
at every scale under every stuck configuration tried (default push-forward,
`removeStuckVehicles=true` + 600 s). Related trap: closing a *destination*
link is vacuous (arrival happens on link entry), so closure tests must target
through-links. True zero-capacity closures need Phase 4 within-day rerouting
(en-route agents reroute instead of queueing); revisit then.

The 2% trickle keeps every agent moving (no traps, mass conserved) while pricing
the link out of routes: verified on equil, closing through-link 10 moved its
usage 89 → 0 with arrivals identical to baseline and zero stuck events.

## Wiring

- File: `DamageToNetworkChangeEvents` parses the CSV and either writes
  change-events XML (`--output`, loaded via config `inputChangeEventsFile` +
  `timeVariantNetwork = true`) or attaches events in-memory.
- Run class: `RunAvEarthquake --damageFile <csv> --tQuake <seconds>`
  attaches events to the loaded scenario's network before the Controler runs.
  No config change needed; ideal for the Phase 5 sweep.

## Applicability to San Francisco

The contract is network-agnostic, but the **link IDs are not**. For
`scenarios/Simulation_SF/`:

- Every `link_id` must be a real Bay Area MATSim link ID from
  `san_francisco_network.xml.gz`; the converter fails fast on unknown IDs.
- The Sioux Falls `damage-sample.csv` cannot be reused — its IDs (`8_9`,
  `10_11`, ...) do not exist in the SF network.
- No SF damage file exists yet. Generate one with SF link IDs (e.g. from an
  external shake-map/link-fragility model) before running an SF quake.
- Target **through-links** for closure tests, not destination links: arrival
  happens on link entry, so closing a final link looks vacuous.
- At SF scale an iteration is much heavier than Sioux Falls (~16 min / ~0.8 GB
  for iteration 0; see `docs/san-francisco-network.md`). Validate a damage file
  on a short `--lastIteration 0` run first.
- Redirect (`--redirect`) additionally needs an SF hospital file; the default
  `--hospitals` path points at the Sioux Falls file and will fail fast.
- Keep `flowCapacityFactor = storageCapacityFactor = 0.01`; damage factors are
  applied on top of the already-scaled capacities.
