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
  # Codex Desktop houdt onder .codex/ipc een Unix-socket open. Een socket kan
  # niet uit een read-only Docker bind mount worden gekopieerd en is ook geen
  # credential. Kopieer daarom alleen gewone bestanden en mappen.
  tar --exclude='./ipc' --exclude='*.sock' -C "$source" -cf - . | tar -C "$target" -xf -
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
    args=(exec --ephemeral --dangerously-bypass-approvals-and-sandbox --skip-git-repo-check -C /work -m "$AR_MODEL" -o "$result_file")
    if [[ -s /job/input/response-schema.json ]]; then
      args+=(--output-schema /job/input/response-schema.json)
    fi
    provider_prompt | codex "${args[@]}"
    ;;
  CLAUDE)
    if [[ -d /credential-source ]]; then
      copy_credentials /credential-source /home/agent/.claude
    fi
    if [[ -f /credential-config.json ]]; then
      cp /credential-config.json /home/agent/.claude.json
    fi
    args=(-p --no-session-persistence --dangerously-skip-permissions --model "$AR_MODEL" --output-format text)
    if [[ -s /job/input/response-schema.json ]]; then
      args+=(--json-schema "$(cat /job/input/response-schema.json)")
    fi
    provider_prompt | claude "${args[@]}" > "$result_file"
    ;;
  *)
    echo "Unsupported execution engine" >&2
    exit 64
    ;;
esac

test -s "$result_file"
