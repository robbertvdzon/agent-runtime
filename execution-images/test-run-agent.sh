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
cat > "$temporary/stream.jsonl" <<'JSON'
{"type":"assistant","message":{"id":"m1","usage":{"input_tokens":100}}}
{"type":"result","subtype":"success","result":"ignored prose","structured_output":{"answer":"done"},"usage":{"input_tokens":100,"output_tokens":20}}
JSON
capture_claude_result "$temporary/stream.jsonl" "$temporary/result.json"
test "$(jq -r .answer "$temporary/result.json")" = done
printf '%s\n' '{"type":"result","result":"{\"answer\":\"text\"}"}' '{"truncated":' > "$temporary/stream.jsonl"
capture_claude_result "$temporary/stream.jsonl" "$temporary/result.json"
test "$(jq -r .answer "$temporary/result.json")" = text
printf '%s\n' '{"type":"result","is_error":true,"usage":{"input_tokens":50}}' > "$temporary/stream.jsonl"
capture_claude_result "$temporary/stream.jsonl" "$temporary/result.json"
test ! -s "$temporary/result.json"
printf 'Execution-image credentialkopie en Flutter-pin zijn geldig.\n'
printf 'Claude-streamresultaten blijven gescheiden van usage, ook bij afgebroken streams.\n'
