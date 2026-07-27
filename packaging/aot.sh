#!/usr/bin/env bash
#
# Build an ahead-of-time cache (JEP 516) and report the cold-start difference.
#
# JEP 516 is what makes this worth doing at all: earlier AOT caching restricted which garbage
# collector you could use, so you chose between a fast start and your production collector. In
# JDK 26 that restriction is gone, and the Pod can have both.
#
# The two-run cycle is the documented shape:
#   1. -XX:AOTCacheOutput   record what the application actually touches during a real startup
#   2. -XX:AOTCache         use it
#
# Run from the repository root:  ./packaging/aot.sh
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

toolchain="$(dirname "$root")/.toolchain"
if [ -d "$toolchain/jdk-26.0.1+8" ]; then
  export JAVA_HOME="$toolchain/jdk-26.0.1+8"
  export PATH="$JAVA_HOME/bin:$PATH"
fi

JAR="target/kangaroo.jar"
CACHE="packaging/kangaroo.aot"
PORT=8901
DATA="target/aot-data"

[ -f "$JAR" ] || { echo "No jar at $JAR. Run ./mvnw package first." >&2; exit 1; }

base_flags=(
  --enable-preview
  --add-modules=jdk.incubator.vector
  --enable-native-access=ALL-UNNAMED
  -XX:+UseG1GC
)

rm -rf "$DATA" "$CACHE"
mkdir -p "$DATA"

# ---------------------------------------------------------------- a real training run
#
# The training run has to exercise what a real startup exercises, or the cache records the wrong
# classes. So it starts the server, waits for it to answer, and runs one full assessment through
# it -- which is what pulls in the rule engine, the gradient-boosted head and the WHO tables.
#
# --warmup-and-exit starts the server, runs one of everything through the real pipeline, and then
# returns from main. That last part is the point: the cache is written on a normal JVM exit, and a
# process taken down by a signal does not qualify -- on Windows a kill is a hard TerminateProcess
# that does not even run shutdown hooks.
echo "==> Training run (recording the AOT cache)"

java "${base_flags[@]}" -XX:AOTCacheOutput="$CACHE" \
     -jar "$JAR" --port "$PORT" --data "$DATA" --warmup-and-exit > target/aot-train.log 2>&1

if [ ! -f "$CACHE" ]; then
  echo "The AOT cache was not produced. Training log:" >&2
  tail -30 target/aot-train.log >&2
  exit 1
fi

echo "    cache written: $CACHE ($(du -h "$CACHE" | cut -f1))"

# ---------------------------------------------------------------- measure
#
# Measured to a *useful* startup, not to the point main() returns. --warmup-and-exit starts the
# server, loads both gradient-boosted models and the WHO tables, and assesses four encounters, so
# the number covers the whole path from cold process to "this device can classify a baby".
#
# Timing the whole process rather than polling an endpoint also avoids measuring curl's poll
# granularity instead of the JVM.
measure() {
  local best=99999
  for _ in 1 2 3 4 5 6 7; do
    rm -rf "$DATA"; mkdir -p "$DATA"
    local start
    start=$(date +%s%N)
    java "$@" -jar "$JAR" --port "$PORT" --data "$DATA" --warmup-and-exit > /dev/null 2>&1
    local ms=$(( ($(date +%s%N) - start) / 1000000 ))
    [ "$ms" -lt "$best" ] && best=$ms
  done
  echo "$best"
}

echo "==> Measuring cold start to first completed assessment (best of 7)"

without=$(measure "${base_flags[@]}")
with=$(measure "${base_flags[@]}" -XX:AOTCache="$CACHE")

echo ""
echo "  ------------------------------------------------"
printf "  %-30s %6s ms\n" "without the AOT cache" "$without"
printf "  %-30s %6s ms\n" "with the AOT cache" "$with"
delta=$((without - with))
if [ "$delta" -gt 0 ]; then
  printf "  %-30s %6s ms faster (%d%%)\n" "difference" "$delta" $(( delta * 100 / without ))
else
  printf "  %-30s %6s ms\n" "difference" "$delta"
fi
echo "  ------------------------------------------------"
echo ""
echo "  Host: $(uname -s) $(uname -m)"
echo "  $(java -version 2>&1 | head -1)"
echo ""
echo "  Note: Kangaroo's startup is deliberately lazy -- the WHO tables and both models are"
echo "  JEP 526 lazy constants, so almost nothing loads until it is first used. That makes the"
echo "  AOT cache and the lazy constants partly substitutes for each other: much of what JEP 516"
echo "  would have accelerated at startup, JEP 526 already declined to do. The cache still pays"
echo "  for itself on the first assessment, which is what this measures, and it pays more on a"
echo "  slower device. Re-run this on the Pi; that number is the one that matters."

rm -rf "$DATA"
