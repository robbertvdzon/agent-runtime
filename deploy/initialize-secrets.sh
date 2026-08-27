#!/usr/bin/env bash
set -euo pipefail

deploy_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root_dir="$(cd "$deploy_dir/.." && pwd)"
target="$root_dir/secrets.env"
pf_source="${AR_PF_SECRET_SOURCE:-$root_dir/../product-factory/secrets.env}"
sf_source="${AR_SF_SECRET_SOURCE:-$root_dir/../softwarefactory/secrets.env}"

[[ -f "$pf_source" ]] || { echo "Product Factory-secretbron ontbreekt." >&2; exit 1; }
[[ -f "$sf_source" ]] || { echo "Software Factory-secretbron ontbreekt." >&2; exit 1; }

value_for() {
  local file="$1" key="$2"
  awk -v key="$key" 'index($0,key "=")==1 {print substr($0,length(key)+2)}' "$file" | tail -1
}
random_secret() { openssl rand -base64 48 | tr -d '\n'; }

if [[ -f "$target" ]]; then
  google_client="$(value_for "$sf_source" SF_GOOGLE_CLIENT_ID)"
  admin_emails="$(value_for "$sf_source" SF_ALLOWED_EMAILS)"
  session_secret="$(value_for "$sf_source" SF_DASHBOARD_REMEMBER_SECRET)"
  tmp="$(mktemp)"; trap 'rm -f "$tmp"' EXIT; chmod 600 "$tmp"; cp "$target" "$tmp"
  [[ -n "$(value_for "$target" AR_HKH_AUTOPILOT_TOKEN)" ]] || printf 'AR_HKH_AUTOPILOT_TOKEN=%s\n' "$(random_secret)" >> "$tmp"
  [[ -n "$(value_for "$target" AR_HKH_TOKEN)" ]] || printf 'AR_HKH_TOKEN=%s\n' "$(random_secret)" >> "$tmp"
  [[ -n "$(value_for "$target" AR_GOOGLE_CLIENT_ID)" ]] || printf 'AR_GOOGLE_CLIENT_ID=%s\n' "$google_client" >> "$tmp"
  [[ -n "$(value_for "$target" AR_ADMIN_EMAILS)" ]] || printf 'AR_ADMIN_EMAILS=%s\n' "$admin_emails" >> "$tmp"
  [[ -n "$(value_for "$target" AR_SESSION_SIGNING_SECRET)" ]] || printf 'AR_SESSION_SIGNING_SECRET=%s\n' "$session_secret" >> "$tmp"
  mv "$tmp" "$target"; chmod 600 "$target"; trap - EXIT
  echo "Bestaande secrets.env behouden en ontbrekende Google-beheerinstellingen zonder weergave aangevuld." >&2
  exit 0
fi

pf_token="$(value_for "$pf_source" PF_AGENT_WORKER_TOKEN)"
sf_token="$(value_for "$sf_source" SF_PRODUCT_FACTORY_TOKEN)"
google_client="$(value_for "$sf_source" SF_GOOGLE_CLIENT_ID)"
admin_emails="$(value_for "$sf_source" SF_ALLOWED_EMAILS)"
session_secret="$(value_for "$sf_source" SF_DASHBOARD_REMEMBER_SECRET)"
[[ -n "$pf_token" && -n "$sf_token" && -n "$google_client" && -n "$admin_emails" && -n "$session_secret" ]] || { echo "De herbruikbare v1-servicetokens of beheerinstellingen ontbreken." >&2; exit 1; }

tmp="$(mktemp)"
trap 'rm -f "$tmp"' EXIT
chmod 600 "$tmp"
{
  printf 'AR_PRODUCT_FACTORY_TOKEN=%s\n' "$pf_token"
  printf 'AR_SOFTWARE_FACTORY_TOKEN=%s\n' "$sf_token"
  printf 'AR_HKH_AUTOPILOT_TOKEN=%s\n' "$(random_secret)"
  printf 'AR_HKH_TOKEN=%s\n' "$(random_secret)"
  printf 'AR_WORKER_TOKEN=%s\n' "$(random_secret)"
  printf 'AR_ADMIN_TOKEN=%s\n' "$(random_secret)"
  printf 'AR_GOOGLE_CLIENT_ID=%s\n' "$google_client"
  printf 'AR_ADMIN_EMAILS=%s\n' "$admin_emails"
  printf 'AR_SESSION_SIGNING_SECRET=%s\n' "$session_secret"
  printf 'AR_DB_USERNAME=agent_runtime\n'
  printf 'AR_DB_PASSWORD=%s\n' "$(random_secret)"
  printf 'AR_DB_URL=jdbc:postgresql://agent-runtime-postgresql:5432/agent_runtime\n'
} > "$tmp"
mv "$tmp" "$target"
chmod 600 "$target"
echo "Lokale secretbron gemaakt; bestaande v1-servicetokens en Google-beheerinstellingen zijn zonder weergave overgenomen." >&2
