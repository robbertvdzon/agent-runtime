#!/usr/bin/env bash
# Drie sequentiële naspel-runs: glm-5.3 herhaling, mistral-large-4, glm-5.3-flash.
set -uo pipefail
cd "$(dirname "$0")"
./run-naspel.sh output-glm-herhaal glm-5-3
./run-naspel.sh output-large4 mistral-large-4-0-3
./run-naspel.sh output-flash zai-glm-5-3-flash
echo "ALLE RUNS KLAAR"
