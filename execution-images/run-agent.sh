#!/usr/bin/env bash
set -euo pipefail

umask 077
result_file="${AR_RESULT_FILE:-/job/output/result.json}"

provider_prompt() {
  cat /job/input/prompt.md
  if [[ -n "${AR_OLLAMA_CORRECTION:-}" ]]; then printf '\n%s\n' "$AR_OLLAMA_CORRECTION"; fi
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
  CODEX|OLLAMA_CODEX)
    if [[ "$AR_ENGINE" == CODEX ]]; then copy_credentials /credential-source /home/agent/.codex; fi
    args=(exec --json --ephemeral --dangerously-bypass-approvals-and-sandbox --skip-git-repo-check -C /work -m "$AR_MODEL" -o "$result_file")
    if [[ "$AR_ENGINE" == OLLAMA_CODEX ]]; then
      # Dedicated provider: no cloud credentials/configuration and no web-search fallback.
      args+=(--ignore-user-config -c 'model_provider="ar-ollama"'
        -c 'model_providers.ar-ollama.name="Ollama"'
        -c "model_providers.ar-ollama.base_url=\"${AR_OLLAMA_BASE_URL:?}\""
        -c 'model_providers.ar-ollama.wire_api="responses"'
        -c 'model_providers.ar-ollama.requires_openai_auth=false'
        -c "model_context_window=${AR_OLLAMA_CONTEXT_WINDOW:-65536}"
        -c "model_auto_compact_token_limit=$(( ${AR_OLLAMA_CONTEXT_WINDOW:-65536} * 3 / 4 ))"
        -c 'web_search="disabled"')
    fi
    # Ollama response_format constrains intermediate turns too and can suppress tool calls.
    # Keep file access agentic; the worker/server validates the final schema and retries.
    if [[ "$AR_ENGINE" == CODEX && -s /job/input/response-schema.json ]]; then
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
      # De Claude-CLI kent de "$schema"-URI van draft 2020-12 niet en weigert het hele schema;
      # de server valideert het resultaat daarna nog steeds tegen het volledige schema.
      args+=(--json-schema "$(jq -c 'del(."$schema")' /job/input/response-schema.json)")
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
  OLLAMA)
    exec /opt/vibe/bin/python /usr/local/lib/agent-runtime/ollama-agent.py
    ;;
  MISTRAL)
    exec /opt/vibe/bin/python /usr/local/lib/agent-runtime/mistral-agent.py
    ;;
  *)
    echo "Unsupported execution engine" >&2
    exit 64
    ;;
esac

if [[ "${AR_ENGINE:-}" != OLLAMA_CODEX ]]; then test -s "$result_file"; fi
