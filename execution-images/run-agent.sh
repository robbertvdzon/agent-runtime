#!/usr/bin/env bash
set -euo pipefail

umask 077
case "${AR_ENGINE:-}" in
  CODEX)
    cp -R /credential-source/. /home/agent/.codex/
    args=(exec --ephemeral --dangerously-bypass-approvals-and-sandbox --skip-git-repo-check -C /work -m "$AR_MODEL" -o /runtime/result.json)
    if [[ -s /runtime/response-schema.json ]]; then
      args+=(--output-schema /runtime/response-schema.json)
    fi
    codex "${args[@]}" "$(cat /runtime/prompt.txt)"
    ;;
  CLAUDE)
    cp -R /credential-source/. /home/agent/.claude/
    args=(-p --no-session-persistence --dangerously-skip-permissions --model "$AR_MODEL" --output-format text)
    if [[ -s /runtime/response-schema.json ]]; then
      args+=(--json-schema "$(cat /runtime/response-schema.json)")
    fi
    claude "${args[@]}" "$(cat /runtime/prompt.txt)" > /runtime/result.json
    ;;
  *)
    echo "Unsupported execution engine" >&2
    exit 64
    ;;
esac

test -s /runtime/result.json
