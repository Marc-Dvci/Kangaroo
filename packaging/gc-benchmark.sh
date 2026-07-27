#!/usr/bin/env bash
#
# Measure G1 under the load a Pod actually sees (JEP 522).
#
# JEP 522 improved G1's throughput by reducing synchronisation between application threads and the
# collector on the write-barrier path. The allocation pattern it helps is exactly the one Kangaroo
# produces on a Pod: many concurrent short-lived assessments, each allocating a decoded frame, a
# feature vector and a pile of small records, on a machine with few cores.
#
# This script drives that load and reports throughput and pause behaviour. To compare JDK 25 and 26,
# run it under each and diff the summaries -- the JEP's claim is about the delta, so a single-JDK
# number would not support it.
#
# Run from the repository root:  ./packaging/gc-benchmark.sh [concurrency] [requests]
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

# Pick a JDK 26 deliberately rather than inheriting whatever JAVA_HOME happens to say. These
# scripts once ran silently against the system's JDK 17 and produced baffling errors from tools
# that had simply never heard of the flags being passed.
#
# Order: a JDK fetched by packaging/fetch-jdk26.sh, then a pinned development toolchain beside the
# repository, then whatever is already on PATH (which the pom's enforcer will reject if it is too
# old, with a clear message).
for candidate in "$root/.jdk" "$(dirname "$root")/.toolchain/jdk-26.0.1+8"; do
  if [ -x "$candidate/bin/java" ]; then
    export JAVA_HOME="$candidate"
    export PATH="$JAVA_HOME/bin:$PATH"
    break
  fi
done

CONCURRENCY="${1:-12}"     # a Pod serving a dozen phones
REQUESTS="${2:-600}"
PORT=8902
DATA="target/gc-data"
JAR="target/kangaroo.jar"
GCLOG="target/gc.log"

[ -f "$JAR" ] || { echo "No jar at $JAR. Run ./mvnw package first." >&2; exit 1; }
command -v curl >/dev/null || { echo "curl is required" >&2; exit 1; }

rm -rf "$DATA" "$GCLOG"
mkdir -p "$DATA"

echo "==> $(java -version 2>&1 | head -1)"
echo "==> $CONCURRENCY concurrent clients, $REQUESTS assessments"
echo ""

java --enable-preview --add-modules=jdk.incubator.vector --enable-native-access=ALL-UNNAMED \
     -XX:+UseG1GC \
     -XX:MaxGCPauseMillis=100 \
     -Xlog:gc*:file="$GCLOG":time,uptime:filecount=1 \
     -jar "$JAR" --port "$PORT" --data "$DATA" > target/gc-server.log 2>&1 &
pid=$!
trap 'kill "$pid" 2>/dev/null || true' EXIT

for _ in $(seq 1 60); do
  curl -fsS "http://127.0.0.1:$PORT/api/status" > /dev/null 2>&1 && break
  sleep 0.5
done

payload='{"mode":"chw","age_days":6,"weight_kg":2.9,"sex":"female","respiratory_rate":52,
          "intake_text":"Mother reports the baby has been very sleepy since last night and is feeding less than usual. The cord stump looks slightly red. No fever."}'

# Warm up so the measurement covers steady-state behaviour rather than JIT compilation.
for _ in $(seq 1 50); do
  curl -fsS -X POST "http://127.0.0.1:$PORT/api/assess" \
       -H 'Content-Type: application/json' -d "$payload" > /dev/null
done

echo "==> Measuring"
start=$(date +%s%N)

per_client=$(( REQUESTS / CONCURRENCY ))
clients=()
for _ in $(seq 1 "$CONCURRENCY"); do
  (
    for _ in $(seq 1 "$per_client"); do
      curl -fsS -X POST "http://127.0.0.1:$PORT/api/assess" \
           -H 'Content-Type: application/json' -d "$payload" > /dev/null
    done
  ) &
  clients+=("$!")
done

# Wait for the client subshells only. A bare `wait` also waits on the server, which is a background
# job of this shell and never exits -- so the script hangs forever having done all the work.
wait "${clients[@]}"

elapsed_ms=$(( ($(date +%s%N) - start) / 1000000 ))
total=$(( per_client * CONCURRENCY ))

kill "$pid" 2>/dev/null || true
wait "$pid" 2>/dev/null || true
trap - EXIT

echo ""
echo "  ----------------------------------------------------------"
printf "  %-28s %s\n" "assessments" "$total"
printf "  %-28s %s ms\n" "wall clock" "$elapsed_ms"
printf "  %-28s %s\n" "throughput" "$(( total * 1000 / (elapsed_ms > 0 ? elapsed_ms : 1) )) /s"

if [ -f "$GCLOG" ]; then
  pauses=$(grep -c "Pause Young" "$GCLOG" 2>/dev/null || echo 0)
  printf "  %-28s %s\n" "young pauses" "$pauses"
  longest=$(grep -oE "Pause Young.*[0-9]+\.[0-9]+ms" "$GCLOG" 2>/dev/null \
            | grep -oE "[0-9]+\.[0-9]+ms" | tr -d 'ms' | sort -gr | head -1 || true)
  printf "  %-28s %s ms\n" "longest young pause" "${longest:-n/a}"
  total_pause=$(grep -oE "Pause Young.*[0-9]+\.[0-9]+ms" "$GCLOG" 2>/dev/null \
                | grep -oE "[0-9]+\.[0-9]+ms" | tr -d 'ms' \
                | awk '{s+=$1} END {printf "%.1f", s}' || true)
  printf "  %-28s %s ms\n" "total pause time" "${total_pause:-n/a}"
  if [ -n "${total_pause:-}" ] && [ "$elapsed_ms" -gt 0 ]; then
    printf "  %-28s %s%%\n" "time in GC pauses" \
      "$(awk -v p="$total_pause" -v e="$elapsed_ms" 'BEGIN {printf "%.2f", p*100/e}')"
  fi
fi
echo "  ----------------------------------------------------------"
echo ""
echo "  Full GC log: $GCLOG"
echo ""
echo "  To evidence JEP 522, run this under JDK 25 and JDK 26 on the same machine with the same"
echo "  arguments and compare. A single number from one JDK says nothing about a change between"
echo "  two of them."

rm -rf "$DATA"
