#!/usr/bin/env bash
set -euo pipefail

deploy_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root_dir="$(cd "${deploy_dir}/.." && pwd)"
source_file="${AR_HKH_RUNTIME_SECRET_SOURCE:-${root_dir}/secrets.env}"
target_file="${AR_HKH_SECRET_TARGET:-${root_dir}/../hkh/secrets.env}"

[[ -f "${source_file}" && ! -L "${source_file}" ]] || {
  echo "Veilige Agent Runtime-secretbron ontbreekt." >&2
  exit 1
}
[[ -f "${target_file}" && ! -L "${target_file}" ]] || {
  echo "Veilige HKH-secrets.env ontbreekt." >&2
  exit 1
}

value_for() {
  local file="$1" key="$2"
  awk -v key="${key}" 'index($0,key "=")==1 {print substr($0,length(key)+2)}' "${file}" | tail -1
}

token="$(value_for "${source_file}" AR_HKH_TOKEN)"
[[ ${#token} -ge 24 ]] || {
  echo "AR_HKH_TOKEN ontbreekt of is te kort." >&2
  exit 1
}

upsert() {
  local key="$1" value="$2" temporary
  temporary="$(mktemp)"
  chmod 600 "${temporary}"
  awk -v key="${key}" 'index($0,key "=")!=1 {print}' "${target_file}" > "${temporary}"
  printf '%s=%s\n' "${key}" "${value}" >> "${temporary}"
  mv "${temporary}" "${target_file}"
  chmod 600 "${target_file}"
}

upsert HKH_AGENT_RUNTIME_URL https://agent-runtime.vdzonsoftware.nl
upsert HKH_AGENT_RUNTIME_TOKEN "${token}"
upsert HKH_AGENT_RUNTIME_PROJECT_PREFIX HKH
upsert HKH_AGENT_RUNTIME_PROVIDER CODEX
upsert HKH_AGENT_RUNTIME_MODEL gpt-5.6-sol
upsert HKH_AGENT_RUNTIME_EXECUTION_TIMEOUT_SECONDS 3600

echo "Lokale HKH-consumentconfiguratie bijgewerkt zonder secretwaarden te tonen." >&2
