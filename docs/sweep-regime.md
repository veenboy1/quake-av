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
