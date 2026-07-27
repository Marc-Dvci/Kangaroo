#!/usr/bin/env bash
# Kangaroo build wrapper.
# Pins the exact JDK 26 build this project is developed and verified against, so a stray JAVA_HOME
# on the machine cannot silently build the project against an older release.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
toolchain="$(dirname "$root")/.toolchain"

jdk="$toolchain/jdk-26.0.1+8"
mvn="$toolchain/apache-maven-3.9.11/bin/mvn"

[ -d "$jdk" ] || { echo "JDK 26 not found at $jdk. See README 'Building'." >&2; exit 1; }
[ -x "$mvn" ] || { echo "Maven not found at $mvn. See README 'Building'." >&2; exit 1; }

export JAVA_HOME="$jdk"
export PATH="$jdk/bin:$PATH"

exec "$mvn" "$@"
