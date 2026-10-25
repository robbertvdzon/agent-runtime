#!/usr/bin/env bash
# Naspel van agent-runtime-job e24e334a (HKH-uitwerking) door Mistral Vibe CLI.
# Gebruik: run-naspel.sh <outputmap> <model-alias of -> [result.json-in-outputmap]
# Zie ../BEVINDINGEN.md voor context en resultaten.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
OUT="$1"; MODEL="${2:--}"
mkdir -p "$ROOT/$OUT"
ARGS=(--prompt-file "$ROOT/job/input/prompt.md" --output json --auto-approve --trust
      --workdir "$ROOT" --output-dir "$ROOT/$OUT" --time-limit 1500 --max-turns 60 --max-tokens 2000000)
if [ "$MODEL" != "-" ]; then export VIBE_ACTIVE_MODEL="$MODEL"; fi
START=$(date +%s)
vibe "${ARGS[@]}" > "$ROOT/$OUT/vibe-journal.json" 2> "$ROOT/$OUT/vibe-stderr.log"
CODE=$?
echo "EXIT=$CODE DUUR=$(( $(date +%s) - START ))s MODEL=$MODEL" >> "$ROOT/$OUT/vibe-stderr.log"
exit $CODE
