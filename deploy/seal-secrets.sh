#!/usr/bin/env bash
set -euo pipefail

deploy_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root_dir="$(cd "$deploy_dir/.." && pwd)"
source_file="${AR_SEAL_SOURCE:-$root_dir/secrets.env}"
cert_file="${AR_SEAL_CERT:-$root_dir/../robberts-infrastructure/manifests/cluster-bootstrap/cluster-cert.pem}"
required=(AR_PRODUCT_FACTORY_TOKEN AR_SOFTWARE_FACTORY_TOKEN AR_HKH_AUTOPILOT_TOKEN AR_HKH_TOKEN AR_WORKER_TOKEN AR_ADMIN_TOKEN AR_GOOGLE_CLIENT_ID AR_ADMIN_EMAILS AR_SESSION_SIGNING_SECRET AR_DB_USERNAME AR_DB_PASSWORD AR_DB_URL)

command -v kubeseal >/dev/null || { echo "kubeseal ontbreekt." >&2; exit 1; }
[[ -f "$source_file" && -f "$cert_file" ]] || { echo "Secretbron of clustercertificaat ontbreekt." >&2; exit 1; }
value_for() { awk -v key="$1" 'index($0,key "=")==1 {print substr($0,length(key)+2)}' "$source_file" | tail -1; }
for key in "${required[@]}"; do [[ -n "$(value_for "$key")" ]] || { echo "Verplichte key ontbreekt: $key" >&2; exit 1; }; done

for namespace in agent-runtime agent-runtime-acceptance; do
  plain="$(mktemp)"; sealed="$(mktemp)"
  trap 'rm -f "${plain:-}" "${sealed:-}"' EXIT
  chmod 600 "$plain" "$sealed"
  {
    printf 'apiVersion: v1\nkind: Secret\nmetadata:\n  name: agent-runtime-secrets\n  namespace: %s\ntype: Opaque\nstringData:\n' "$namespace"
    for key in "${required[@]}"; do printf '  %s: |-\n' "$key"; printf '%s\n' "$(value_for "$key")" | sed 's/^/    /'; done
  } > "$plain"
  kubeseal --cert "$cert_file" --format yaml < "$plain" > "$sealed"
  if [[ "$namespace" == agent-runtime ]]; then
    output="$deploy_dir/production/sealed-secret.yaml"
  else
    output="$deploy_dir/acceptance/sealed-secret.yaml"
  fi
  mv "$sealed" "$output"
  rm -f "$plain"
  trap - EXIT
  echo "SealedSecret voor $namespace geschreven." >&2
done
