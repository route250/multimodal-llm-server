#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

if [ "$#" -eq 0 ]; then
  mvn -q -DskipTests compile exec:java -Dexec.mainClass=server.MainTest
else
  mvn -q -DskipTests compile exec:java -Dexec.mainClass=server.MainTest -Dexec.args="$*"
fi
