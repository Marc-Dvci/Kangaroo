#!/usr/bin/env bash
#
# Build a custom runtime image containing only the modules Kangaroo actually uses.
#
# On a Pod this matters twice over: it is what fits the whole product onto a small SD card, and a
# runtime with no modules you do not use is a runtime with no attack surface you did not intend.
#
# Run from the repository root:  ./packaging/jlink.sh
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

# Pin the toolchain unconditionally when it is present. Deferring to an inherited JAVA_HOME is
# how these scripts silently ran against the system's JDK 17 and produced baffling errors from
# tools that had simply never heard of the flags being passed.
toolchain="$(dirname "$root")/.toolchain"
if [ -d "$toolchain/jdk-26.0.1+8" ]; then
  export JAVA_HOME="$toolchain/jdk-26.0.1+8"
  export PATH="$JAVA_HOME/bin:$PATH"
fi

JAR="target/kangaroo.jar"
IMAGE="target/kangaroo-runtime"

[ -f "$JAR" ] || { echo "No jar at $JAR. Run ./mvnw package first." >&2; exit 1; }

# What Kangaroo actually needs, and why each one is here.
MODULES=$(cat <<'EOF'
java.base
java.net.http
java.desktop
java.logging
java.management
jdk.httpserver
jdk.jfr
jdk.incubator.vector
EOF
)
MODULES=$(echo "$MODULES" | tr '\n' ',' | sed 's/,$//')

echo "==> Modules"
echo "    java.base            everything"
echo "    java.net.http        the cloud rung, HTTP/3 (JEP 517)"
echo "    java.desktop         javax.imageio, to decode a captured frame"
echo "    java.logging         the bridge llama.cpp's logging is routed into"
echo "    java.management      uptime, for the console"
echo "    jdk.httpserver       the server"
echo "    jdk.jfr              the clinical audit trail"
echo "    jdk.incubator.vector the colorimetry kernels (JEP 529)"
echo ""

# Confirm against what jdeps thinks, so this list cannot quietly go stale.
echo "==> jdeps says the jar requires:"
jdeps --multi-release 26 --print-module-deps --ignore-missing-deps "$JAR" 2>/dev/null \
  | tr ',' '\n' | sed 's/^/    /' || echo "    (jdeps could not analyse; the list above is used)"
echo ""

rm -rf "$IMAGE"

jlink \
  --add-modules "$MODULES" \
  --output "$IMAGE" \
  --strip-debug \
  --no-header-files \
  --no-man-pages \
  --compress zip-9

echo "==> Image built"
printf "    %-24s %s\n" "custom runtime" "$(du -sh "$IMAGE" | cut -f1)"
printf "    %-24s %s\n" "full JDK" "$(du -sh "$JAVA_HOME" | cut -f1)"
printf "    %-24s %s\n" "application jar" "$(du -sh "$JAR" | cut -f1)"
echo ""
echo "    Run it with:"
echo "      $IMAGE/bin/java --enable-preview --add-modules jdk.incubator.vector \\"
echo "        --enable-native-access=ALL-UNNAMED -jar $JAR"
