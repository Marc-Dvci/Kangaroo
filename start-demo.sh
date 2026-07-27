#!/usr/bin/env bash
#
# Kangaroo — one-click demo launcher.
#
# Builds if needed, starts the real application, and opens the browser on the narrated walk-through.
# Everything the film shows is this process computing live; the demo only supplies the sensor input
# a laptop has no way to capture.
#
# Requires a JDK 26. Nothing else — the voice-over is committed, so recording needs neither Python
# nor a network connection.
set -uo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$root"

PORT="${KANGAROO_PORT:-8443}"
JAR="target/kangaroo.jar"

echo
echo "  Kangaroo — demo launcher"
echo "  ========================"
echo

# ---- find a JDK 26 -----------------------------------------------------------------------------
for candidate in "$root/.jdk" "$(dirname "$root")/.toolchain/jdk-26.0.1+8" "${JAVA_HOME:-}"; do
  if [ -n "$candidate" ] && [ -x "$candidate/bin/java" ]; then
    export JAVA_HOME="$candidate"
    export PATH="$JAVA_HOME/bin:$PATH"
    break
  fi
done

if ! command -v java >/dev/null 2>&1; then
  echo "  No Java found. Install a JDK 26, or run:  ./packaging/fetch-jdk26.sh"
  exit 1
fi
echo "  Java     $(java -version 2>&1 | head -1)"

# ---- build if the jar is missing ----------------------------------------------------------------
# Deliberately a plain existence check rather than a timestamp comparison: a launcher that silently
# runs a stale jar is worse than one that rebuilds, and "rm target/kangaroo.jar" forces it.
if [ ! -f "$JAR" ]; then
  echo "  Building (first run) …"
  ./mvnw -q -B package -DskipTests || { echo; echo "  The build failed."; exit 1; }
else
  echo "  Jar      $JAR"
fi

# ---- narration ---------------------------------------------------------------------------------
if [ ! -f "src/main/resources/web/demo/speech/manifest.json" ]; then
  echo
  echo "  The voice-over is missing. Generating it (needs Python + edge-tts, once) …"
  if python3 demo/generate-voiceover.py || python demo/generate-voiceover.py; then
    ./mvnw -q -B package -DskipTests
  else
    echo "  Could not generate the voice-over. The demo will run silently with captions."
  fi
fi

# ---- free the port ------------------------------------------------------------------------------
if command -v lsof >/dev/null 2>&1; then
  busy="$(lsof -ti tcp:"$PORT" 2>/dev/null || true)"
  if [ -n "$busy" ]; then
    echo "  Port $PORT is busy — stopping $busy"
    kill -9 $busy 2>/dev/null || true
    sleep 1
  fi
fi

url="http://localhost:$PORT/?demo=1"

cat <<EOF

  Starting Kangaroo on http://localhost:$PORT/

  -----------------------------------------------------------------
   RECORDING TIPS
     * Press F11 in the browser for full screen before you start.
     * Set the display to 1920x1080 and browser zoom to 100%.
     * The demo runs about 114 seconds, then holds on a closing card.
     * Ctrl-C here to stop the server when you are finished.
  -----------------------------------------------------------------

EOF

# Open the browser once the port answers, rather than immediately — otherwise the first load races
# the JVM and lands on a connection error.
(
  for _ in $(seq 1 60); do
    if curl -fsS "http://127.0.0.1:$PORT/api/status" >/dev/null 2>&1; then break; fi
    sleep 0.5
  done
  case "$(uname -s)" in
    Darwin) open "$url" ;;
    Linux)  xdg-open "$url" >/dev/null 2>&1 || true ;;
    *)      start "" "$url" 2>/dev/null || true ;;
  esac
) &

exec java --enable-preview --add-modules jdk.incubator.vector \
     --enable-native-access=ALL-UNNAMED \
     -jar "$JAR" --port "$PORT" --data "$root/target/demo-data"
