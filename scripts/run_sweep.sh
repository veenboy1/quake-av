#!/usr/bin/env bash
#
# Phase 5 sweep driver: AV penetration x seeds x {quake, noquake}.
#
# Each cell is one RunAvEarthquake run (regime (b): quake active every
# iteration; iteration 0 reads as the surprise day — see docs/sweep-regime.md).
# The AV seed and redirect seed are FIXED across cells while avShare varies, so
# AV sets stay nested and the same agents are redirected in every compared run.
#
# Usage:
#   scripts/run_sweep.sh [options]
#   Options:
#     --config <file>      MATSim config (default: scenarios/siouxfalls/config.xml)
#     --shares "0 10 ..."  AV penetration percents (default: 0 10 25 50 75 100)
#     --seeds "1 2 ..."    AV seeds, one cell each (default: 1 2 3 4 5)
#     --variants "quake noquake"  scenario variants (default: both)
#     --iterations <n>     lastIteration per cell (default: 10)
#     --jobs <n>           parallel cells (default: 4)
#     --damage <csv>       damage file for quake cells (default: scenarios/siouxfalls/damage-sample.csv)
#     --hospitals <csv>    hospitals file for quake cells (default: scenarios/siouxfalls/hospitals.csv)
#     --tQuake <s>         quake time (default: 30600)
#     --redirectSeed <n>   fixed redirect seed across cells (default: 9182)
#     --outdir <dir>       sweep root (default: output/sweep)
#     --no-build           skip the jar freshness check (default: rebuild if stale)
#   D5 splits use defaults (--homeShare 0.60 etc.); override by editing CELLS below.
#
set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

CONFIG="scenarios/siouxfalls/config.xml"
SHARES="0 10 25 50 75 100"
SEEDS="1 2 3 4 5"
VARIANTS="quake noquake"
ITERATIONS=10
JOBS=4
DAMAGE="scenarios/siouxfalls/damage-sample.csv"
HOSPITALS="scenarios/siouxfalls/hospitals.csv"
TQUAKE=30600
REDIRECT_SEED=9182
OUTDIR="output/sweep"
NO_BUILD=0

while [ $# -gt 0 ]; do
  case "$1" in
    --config) CONFIG="$2"; shift 2;;
    --shares) SHARES="$2"; shift 2;;
    --seeds) SEEDS="$2"; shift 2;;
    --variants) VARIANTS="$2"; shift 2;;
    --iterations) ITERATIONS="$2"; shift 2;;
    --jobs) JOBS="$2"; shift 2;;
    --damage) DAMAGE="$2"; shift 2;;
    --hospitals) HOSPITALS="$2"; shift 2;;
    --tQuake) TQUAKE="$2"; shift 2;;
    --redirectSeed) REDIRECT_SEED="$2"; shift 2;;
    --outdir) OUTDIR="$2"; shift 2;;
    --no-build) NO_BUILD=1; shift;;
    -h|--help) sed -n '2,30p' "$0"; exit 0;;
    *) echo "Unknown arg: $1" >&2; exit 1;;
  esac
done

command -v java >/dev/null || { echo "java not found" >&2; exit 1; }

JAR="$ROOT/matsim-example-project-0.0.1-SNAPSHOT.jar"
if [ "$NO_BUILD" -eq 0 ] && { [ ! -f "$JAR" ] || [ -n "$(find "$ROOT/src/main" -newer "$JAR" -print -quit)" ]; }; then
  echo "Packaging $JAR (missing or older than sources)..."
  ./mvnw -q -DskipTests package || exit 1
elif [ ! -f "$JAR" ]; then
  echo "Jar missing and --no-build set; aborting" >&2
  exit 1
fi

mkdir -p "$OUTDIR"
STATUS="$OUTDIR/cell-status.txt"
: > "$STATUS"

run_cell() { # share seed variant
  local share="$1" seed="$2" variant="$3"
  local cell="av${share}_seed${seed}_${variant}"
  local dir="$OUTDIR/$cell"
  local log="$dir/sweep-cell.log"
  mkdir -p "$dir"

  local args="--config $CONFIG --output $dir --lastIteration $ITERATIONS --avShare $(awk "BEGIN{print $share/100}") --avSeed $seed"
  if [ "$variant" = "quake" ]; then
    args="$args --damageFile $DAMAGE --tQuake $TQUAKE --redirect --redirectSeed $REDIRECT_SEED --hospitals $HOSPITALS"
  fi
  # shellcheck disable=SC2086
  if java -cp "$JAR" org.matsim.project.RunAvEarthquake $args > "$log" 2>&1; then
    echo "OK   $cell" >> "$STATUS"
  else
    echo "FAIL $cell (see $log)" >> "$STATUS"
  fi
}

count=0
for share in $SHARES; do
  for seed in $SEEDS; do
    for variant in $VARIANTS; do
      run_cell "$share" "$seed" "$variant" &
      count=$((count + 1))
      if [ $((count % JOBS)) -eq 0 ]; then
        wait
      fi
    done
  done
done
wait

echo "=== sweep done: $(grep -c '^OK' "$STATUS") ok, $(grep -c '^FAIL' "$STATUS") failed ==="
grep '^FAIL' "$STATUS" || true

echo "=== redirect outcomes per quake cell (home/hospital/stay/random) ==="
for share in $SHARES; do
  for seed in $SEEDS; do
    cell="av${share}_seed${seed}_quake"
    csv="$OUTDIR/$cell/redirect-log.csv"
    if [ -f "$csv" ]; then
      # last iteration only (file isolates it); columns: iteration,agent,...,outcome,...
      counts=$(awk -F, 'NR>1{c[$4]++} END{printf "home=%d hospital=%d stay=%d random=%d", c["home"]+0, c["hospital"]+0, c["stay"]+0, c["random"]+0}' "$csv")
      echo "$cell: $counts"
    fi
  done
done
