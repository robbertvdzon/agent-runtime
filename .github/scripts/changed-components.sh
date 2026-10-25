#!/usr/bin/env bash
set -euo pipefail
# Compare pushes against the last green run, not just the previous push: cancelled
# or failed runs must not hide changes. PRs compare against their target branch.
if [[ "${GITHUB_EVENT_NAME:-}" == pull_request ]]; then
  base=$(jq -r .pull_request.base.sha "$GITHUB_EVENT_PATH")
else
  base=$(gh api "repos/$GITHUB_REPOSITORY/actions/workflows/verify.yml/runs?branch=main&status=success&event=push&per_page=1" --jq '.workflow_runs[0].head_sha // empty')
fi
if [[ -z "$base" ]] || ! git cat-file -e "$base^{commit}" 2>/dev/null; then
  base=$(git hash-object -t tree /dev/null)
fi
changed=$(git diff --name-only "$base" HEAD)
for component in java ui execution deploy server; do
  case "$component" in
    java) pattern='^(pom.xml|agent-runtime-(contracts|server|worker)/|\.github/)' ;;
    ui) pattern='^(monitor-ui/|\.github/)' ;;
    execution) pattern='^(execution-images/|\.github/)' ;;
    deploy) pattern='^(deploy/|\.github/)' ;;
    server) pattern='^(pom.xml|Dockerfile|\.dockerignore|agent-runtime-(contracts|server)/|\.github/)' ;;
  esac
  component_changes="$changed"
  if [[ "$component" == server && -f deploy/base/kustomization.yaml ]]; then
    # A newer docs/worker push can cancel publication before GitOps is pinned.
    # Keep building the server until the deployed pin includes its source changes.
    pinned=$(sed -nE 's/.*newTag: sha-([0-9a-f]{40}).*/\1/p' deploy/base/kustomization.yaml)
    if [[ -n "$pinned" ]] && git cat-file -e "$pinned^{commit}" 2>/dev/null; then
      component_changes=$(git diff --name-only "$pinned" HEAD)
    fi
  fi
  value=false
  if [[ "${GITHUB_EVENT_NAME:-}" == workflow_dispatch ]] || grep -Eq "$pattern" <<< "$component_changes"; then value=true; fi
  echo "$component=$value" >> "$GITHUB_OUTPUT"
done
