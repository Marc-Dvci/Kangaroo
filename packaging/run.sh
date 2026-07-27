#!/usr/bin/env bash
#
# Launch Kangaroo with the flags it is meant to run under in production.
#
# Every flag here is deliberate and is explained. Copy this into a systemd unit for a Pod.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

JAR="${KANGAROO_JAR:-target/kangaroo.jar}"
PORT="${KANGAROO_PORT:-8443}"
DATA="${KANGAROO_DATA:-$HOME/.kangaroo}"
AOT_CACHE="${KANGAROO_AOT:-packaging/kangaroo.aot}"

[ -f "$JAR" ] || { echo "No jar at $JAR. Run ./mvnw package first." >&2; exit 1; }

flags=(
  # --- required by the release features this project is built on -----------------------------
  --enable-preview                      # JEP 525 structured concurrency, 526 lazy constants,
                                        # 530 primitive patterns, 524 PEM encodings
  --add-modules=jdk.incubator.vector    # JEP 529 Vector API

  # --- integrity ------------------------------------------------------------------------------
  #
  # JEP 500, "prepare to make final mean final". For clinical software, "no library can quietly
  # mutate a final field in my dosing table" is a safety property rather than a checkbox. Kangaroo
  # has zero runtime dependencies, so nothing in the process has any business doing this, and
  # denying it outright costs nothing.
  --illegal-final-field-access=deny

  # The FFM layer needs explicit permission to call native code. Naming it here rather than
  # suppressing the warning means an unexpected native call is still visible.
  --enable-native-access=ALL-UNNAMED

  # --- garbage collection ---------------------------------------------------------------------
  #
  # G1 explicitly rather than by default. JEP 522 improved its throughput by reducing
  # synchronisation on exactly the allocation pattern a Pod serving a dozen concurrent
  # assessments produces. See packaging/gc-benchmark.sh for the measurement.
  -XX:+UseG1GC
  -XX:MaxGCPauseMillis=100

  # --- flight recorder ------------------------------------------------------------------------
  #
  # The clinical audit trail. Always on, in production, on a Raspberry Pi: a disabled event costs
  # a predicate and an enabled one costs tens of nanoseconds. The recording is what a supervisor
  # opens in JDK Mission Control, and what a later build replays to prove a fix changed an outcome.
  -XX:StartFlightRecording=name=kangaroo,settings=profile,maxsize=256m,maxage=30d,dumponexit=true,filename="$DATA/audit.jfr"
)

# JEP 516: ahead-of-time object caching, now usable with any GC. Only added when the cache exists,
# so a fresh checkout still starts.
if [ -f "$AOT_CACHE" ]; then
  flags+=( "-XX:AOTCache=$AOT_CACHE" )
  echo "Using AOT cache: $AOT_CACHE"
else
  echo "No AOT cache (run packaging/aot.sh to build one). Starting without it."
fi

mkdir -p "$DATA"

exec java "${flags[@]}" -jar "$JAR" --port "$PORT" --data "$DATA" "$@"
