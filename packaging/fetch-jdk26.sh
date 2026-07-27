#!/usr/bin/env bash
#
# Fetch a JDK 26 into ./.jdk and print how to use it.
#
# Kangaroo needs Java 26 and most machines do not have it yet. Rather than making that the reader's
# problem, this downloads a Temurin build for the current platform, unpacks it beside the
# repository, and tells you the one line to run. Nothing is installed system-wide and nothing is
# modified outside this directory.
#
# Run from the repository root:  ./packaging/fetch-jdk26.sh
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

DEST="$root/.jdk"
FEATURE=26

case "$(uname -s)" in
  Linux*)   OS=linux ;;
  Darwin*)  OS=mac ;;
  MINGW*|MSYS*|CYGWIN*) OS=windows ;;
  *)        echo "Unrecognised platform: $(uname -s)" >&2; exit 1 ;;
esac

case "$(uname -m)" in
  x86_64|amd64) ARCH=x64 ;;
  aarch64|arm64) ARCH=aarch64 ;;
  *) echo "Unrecognised architecture: $(uname -m)" >&2; exit 1 ;;
esac

# Already have one that will do?
if command -v java > /dev/null 2>&1; then
  have=$(java -version 2>&1 | head -1 | grep -oE '"[0-9]+' | tr -d '"' || echo 0)
  if [ "${have:-0}" -ge "$FEATURE" ]; then
    echo "Java $have is already on your PATH. Nothing to do:"
    echo ""
    echo "  ./mvnw package"
    exit 0
  fi
fi

if [ -d "$DEST" ] && [ -x "$DEST/bin/java" ]; then
  echo "A JDK is already unpacked at $DEST"
else
  api="https://api.adoptium.net/v3/binary/latest/$FEATURE/ga/$OS/$ARCH/jdk/hotspot/normal/eclipse"
  echo "==> Downloading Temurin JDK $FEATURE for $OS/$ARCH"
  echo "    $api"

  tmp="$root/.jdk-download"
  rm -rf "$tmp" "$DEST"
  mkdir -p "$tmp"

  if [ "$OS" = "windows" ]; then
    curl -fL "$api" -o "$tmp/jdk.zip"
    (cd "$tmp" && unzip -q jdk.zip)
  else
    curl -fL "$api" -o "$tmp/jdk.tar.gz"
    (cd "$tmp" && tar xzf jdk.tar.gz)
  fi

  # The archive unpacks into a single versioned directory whose name we do not know in advance.
  inner=$(find "$tmp" -maxdepth 2 -name "bin" -type d | head -1)
  [ -n "$inner" ] || { echo "Could not find bin/ in the downloaded archive" >&2; exit 1; }
  mv "$(dirname "$inner")" "$DEST"
  rm -rf "$tmp"
fi

echo ""
echo "==> $("$DEST/bin/java" -version 2>&1 | head -1)"
echo ""
echo "    Use it for this build:"
echo ""
echo "      export JAVA_HOME=\"$DEST\""
echo "      export PATH=\"\$JAVA_HOME/bin:\$PATH\""
echo "      ./mvnw package"
echo ""
echo "    ./.jdk is gitignored. Delete it when you are done."
