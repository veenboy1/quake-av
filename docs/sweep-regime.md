# Sweep regime: (a) equilibrate-then-quake vs (b) quake every iteration

*Status: (b) implemented; (a) documented future option. Audience: the project's
advisor — the open question is whether (b)'s surprise readout suffices or (a)
should be built.*

## Research question

How do AVs vs conventional drivers compare in the ~24h after an earthquake:
emergency access (hospital arrivals), return-home completion, network delay —
across AV penetration rates {0, 10, 25, 50, 75, 100} × seeds × {quake, no-quake}?

## The two regimes

**Regime (b) — quake active in every iteration (current).** Each cell is a single
10-iteration run with damage events and the 08:30 destination redirect active
throughout. Iteration 0 is read as the surprise day; iterations 1–10 as the
adapted response. The destination redirect (home/hospital/stay/random, fixed
seed) surprises agents in every iteration; AV *routes* anticipate damage from
iteration 0 (observed-time routing), conventional routes never adapt
(pre-quake snapshot routing).

**Regime (a) — equilibrate clean, then one quake day (future).** Each cell is
two stages: N iterations with no damage (population learns the normal day),
then the final plans feed a single evaluation iteration with damage + redirect.
Nobody's routes anticipate the quake — pure surprise, including for AVs.

## What each measures — and the key asymmetry

- For **conventional drivers** the regimes nearly coincide: blind routing means
  their quake-day behavior equals (b)'s iteration 0 regardless.
- For **AVs** they differ by construction: (b) grants pre-trip damage knowledge
  from iteration 0; (a) withholds it until the shock. Under (b), the AV claim is
  "AVs reroute and adapt around damage"; under (a) it could additionally be
  "AVs recover from total ignorance faster."
- (b) additionally yields the **adaptation trajectory**: AVs learn across
  iterations while humans don't, so the AV/conventional gap widens — itself a
  finding, and arguably the more policy-relevant one (aftershocks, prolonged
  disruption).

## Limitations to weigh

- (b)'s "surprise" is muted for AV *routes* (not for destinations: the redirect
  is a genuine shock every iteration). A strict reviewer may ask for (a).
- (a) costs real machinery (plans handoff, iteration numbering, per-stage
  configs) and new failure modes (stale routes/vehicles across stages); estimate
  1–2 days. Its cells are also directly comparable to (b)'s — same grid, only
  the driver gains a two-stage mode, so (b) results are not wasted if (a) follows.
- Both regimes share the single-day scope: each iteration is one 24h day
  (quake 08:30, all activity resolved by evening); multi-day sheltering/return
  dynamics are out of scope for either.

## Recommendation (built)

Ship (b): it is implemented, it answers the shock *and* the adaptation
question, and per-iteration redirect logs isolate iteration 0 as the surprise
readout. Revisit (a) if review demands a pure-surprise AV measurement.

## Applicability to San Francisco

The regime discussion is network-agnostic; the **cost and data assumptions are
not**.

- The implemented 60-cell sweep in `scripts/run_sweep.sh` runs on **Sioux
  Falls** (3,608 persons / 76 links, ~7 min/cell).
- San Francisco is the large-network verification case (75,228 persons /
  656,557 links). One iteration is ~16.5 min and ~9.3 GB RAM; a 60-cell SF
  sweep is therefore many hours to days of compute, not minutes, and needs
  ~10 GB RAM per concurrent cell. Do **not** launch the full grid on SF without
  a cluster/parallel-disk plan.
- For SF verification, run single cells with `--lastIteration 0` (iteration 0
  is the surprise readout under regime (b)) rather than a sweep.
- Regime (b) still applies conceptually, but SF currently has **no damage file
  and no hospital file**, so quake cells are not runnable until those are
  supplied with SF link IDs.
- The AV/conventional asymmetry is unchanged: AVs route on observed times,
  conventional cars on a pre-quake snapshot in damage runs.
- The "trips run shorter under quake" caveat is stronger on SF because the
  population has more long commutes that redirect truncates; compare
  within-variant only.
