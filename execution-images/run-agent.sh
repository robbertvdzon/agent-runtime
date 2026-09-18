#!/usr/bin/env bash
set -euo pipefail

umask 077
result_file="${AR_RESULT_FILE:-/job/output/result.json}"

provider_prompt() {
  cat /job/input/prompt.md
  if [[ "${AR_JOB_KIND:-}" == "APPLICATION_WORK" ]]; then
    printf '\n\nReturn only the complete JSON result as your final response and satisfy /job/input/response-schema.json when it exists. The runtime captures that response automatically; do not create or write a result file yourself.\n'
  fi
}

copy_credentials() {
  local source="$1" target="$2"
  for filename in auth.json .credentials.json; do
    if [[ -f "$source/$filename" && ! -L "$source/$filename" ]]; then
      cp -- "$source/$filename" "$target/$filename"
    fi
  done
}

capture_claude_result() {
  # An interrupted stream may end with a truncated line. Retain any complete result.
  jq -Rrs 'split("\n") | map(fromjson? | select(.type == "result")) | last |
    if .structured_output != null then .structured_output | tojson
    elif .result != null then .result else empty end' "$1" > "$2"
}

# Maak de helper zonder side effects sourcebaar voor de regressietest.
if [[ "${BASH_SOURCE[0]}" != "$0" ]]; then
  return 0
fi

# Prompt gaat via stdin, nooit als CLI-argument: een lang prompt.md (grote frozen
# context) liet exec() eerder stuklopen op "Argument list too long" (E2BIG, exit 126).
case "${AR_ENGINE:-}" in
  CODEX)
    copy_credentials /credential-source /home/agent/.codex
    args=(exec --json --ephemeral --dangerously-bypass-approvals-and-sandbox --skip-git-repo-check -C /work -m "$AR_MODEL" -o "$result_file")
    if [[ -s /job/input/response-schema.json ]]; then
      args+=(--output-schema /job/input/response-schema.json)
    fi
    provider_prompt | codex "${args[@]}"
    ;;
  CLAUDE)
    if [[ -d /credential-source ]]; then
      copy_credentials /credential-source /home/agent/.claude
    fi
    args=(-p --no-session-persistence --dangerously-skip-permissions --model "$AR_MODEL" --output-format stream-json --verbose --include-partial-messages)
    if [[ -s /job/input/response-schema.json ]]; then
      args+=(--json-schema "$(cat /job/input/response-schema.json)")
    fi
    # Keep usage events visible to the worker even when Claude exits unsuccessfully.
    # The consumer result remains the JSON/text payload, not the stream envelope.
    stream_file="$(mktemp)"
    trap 'rm -f "$stream_file"' EXIT
    provider_status=0
    provider_prompt | claude "${args[@]}" | tee "$stream_file" || provider_status=$?
    capture_claude_result "$stream_file" "$result_file"
    if (( provider_status != 0 )); then exit "$provider_status"; fi
    ;;
  *)
    echo "Unsupported execution engine" >&2
    exit 64
    ;;
esac

test -s "$result_file"
