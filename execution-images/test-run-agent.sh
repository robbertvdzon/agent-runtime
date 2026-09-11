#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
temporary="$(mktemp -d)"
trap 'rm -rf "$temporary"' EXIT

grep -qF 'ARG FLUTTER_VERSION=3.44.6' "$root_dir/Dockerfile"
grep -qF -- '--branch "$FLUTTER_VERSION"' "$root_dir/Dockerfile"

mkdir -p "$temporary/source/sessions" "$temporary/source/ipc" "$temporary/target"
printf 'credential\n' > "$temporary/source/auth.json"
printf 'session\n' > "$temporary/source/sessions/session.json"
printf 'runtime socket placeholder\n' > "$temporary/source/ipc/ipc.sock"

# shellcheck source=run-agent.sh
source "$root_dir/run-agent.sh"
copy_credentials "$temporary/source" "$temporary/target"

test "$(cat "$temporary/target/auth.json")" = credential
test ! -e "$temporary/target/ipc"
test ! -e "$temporary/target/sessions"
printf 'Execution-image credentialkopie en Flutter-pin zijn geldig.\n'
