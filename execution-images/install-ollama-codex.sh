#!/usr/bin/env bash
# Install an isolated local CLI launcher; leave ~/.codex/config.toml untouched.
set -euo pipefail
umask 077
model="${AR_MODEL:-qwen3.5:35b}"
context="${AR_OLLAMA_CONTEXT_WINDOW:-65536}"
base_url="${AR_OLLAMA_BASE_URL:-http://127.0.0.1:11434/v1}"
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
install_dir="$HOME/.local/share/agent-runtime/ollama"
mkdir -p "$install_dir/codex-home" "$HOME/.local/bin"
python3 "$script_dir/ollama-model-catalog.py" "$model" "$context" "$install_dir/models.json"
# Quote each value for bash; no credentials are copied or required.
{
  printf '#!/usr/bin/env bash\nset -euo pipefail\n'
  printf 'export CODEX_HOME=%q\n'
  printf 'if [[ "${1:-}" == exec ]]; then shift; set -- exec --ignore-user-config "$@"; fi\nexec codex' "$install_dir/codex-home"
  printf ' -c %q' "model_catalog_json=\"$install_dir/models.json\"" 'model_provider="ar-ollama"' \
    'model_providers.ar-ollama.name="Ollama"' "model_providers.ar-ollama.base_url=\"$base_url\"" \
    'model_providers.ar-ollama.wire_api="responses"' 'model_providers.ar-ollama.requires_openai_auth=false' \
    "model_context_window=$context" "model_auto_compact_token_limit=$((context * 3 / 4))" 'web_search="disabled"'
  printf ' -m %q "$@"\n' "$model"
} > "$HOME/.local/bin/codex-ollama"
chmod 700 "$HOME/.local/bin/codex-ollama"
printf 'Installed %s (model %s, context %s)\n' "$HOME/.local/bin/codex-ollama" "$model" "$context"
