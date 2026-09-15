#!/usr/bin/env bash
set -euo pipefail
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)/immutable-worker-jar.sh"
test_directory="$(mktemp -d)"
trap 'rm -rf "$test_directory"' EXIT
mkdir -p "$test_directory/target"
printf 'first build' > "$test_directory/target/worker.jar"
first="$(stage_worker_jar "$test_directory/target/worker.jar" "$test_directory/installed")"
[[ "$first" == "$(stage_worker_jar "$test_directory/target/worker.jar" "$test_directory/installed")" ]]
printf 'second build' > "$test_directory/target/worker.jar"
second="$(stage_worker_jar "$test_directory/target/worker.jar" "$test_directory/installed")"
[[ "$first" != "$second" && "$(cat "$first")" == 'first build' ]]
rm -rf "$test_directory/target"
[[ "$(cat "$first")" == 'first build' && "$(cat "$second")" == 'second build' ]]
printf 'Workerinstallaties blijven intact na rebuild en clean.\n'
