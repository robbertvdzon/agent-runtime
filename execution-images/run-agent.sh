#!/usr/bin/env bash
set -euo pipefail

umask 077
result_file="${AR_RESULT_FILE:-/job/output/result.json}"
case "${AR_ENGINE:-}" in
  CODEX)
    cp -R /credential-source/. /home/agent/.codex/
    args=(exec --ephemeral --dangerously-bypass-approvals-and-sandbox --skip-git-repo-check -C /work -m "$AR_MODEL" -o "$result_file")
    if [[ -s /job/input/response-schema.json ]]; then
      args+=(--output-schema /job/input/response-schema.json)
    fi
    codex "${args[@]}" "$(cat /job/input/prompt.md)"
    ;;
  CLAUDE)
    if [[ -d /credential-source ]]; then
      cp -R /credential-source/. /home/agent/.claude/
    fi
    if [[ -f /credential-config.json ]]; then
      cp /credential-config.json /home/agent/.claude.json
    fi
    args=(-p --no-session-persistence --dangerously-skip-permissions --model "$AR_MODEL" --output-format text)
    if [[ -s /job/input/response-schema.json ]]; then
      args+=(--json-schema "$(cat /job/input/response-schema.json)")
    fi
    claude "${args[@]}" "$(cat /job/input/prompt.md)" > "$result_file"
    ;;
  *)
    echo "Unsupported execution engine" >&2
    exit 64
    ;;
esac

test -s "$result_file"
