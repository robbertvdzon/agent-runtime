#!/usr/bin/env bash

set -euo pipefail

readonly LABEL="nl.vdzon.agent-runtime.worker"
readonly DOMAIN="gui/$(id -u)"
readonly SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
readonly REPOSITORY_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd -P)"
readonly TEMPLATE="$SCRIPT_DIR/$LABEL.plist.template"
readonly TARGET="$HOME/Library/LaunchAgents/$LABEL.plist"
readonly LOG_DIRECTORY="$REPOSITORY_ROOT/work/logs"
readonly STDOUT_PATH="$LOG_DIRECTORY/worker.log"
readonly STDERR_PATH="$LOG_DIRECTORY/worker-error.log"

usage() {
    printf 'Gebruik: %s check|migrate|install|uninstall\n' "$0"
    printf '  check      controleert de configuratie zonder de actieve service te wijzigen\n'
    printf '  migrate    migreert een bestaande worker eenmalig van secrets.env naar properties.env\n'
    printf '  install    installeert of vervangt de LaunchAgent en start de worker\n'
    printf '  uninstall  stopt de LaunchAgent en verwijdert alleen de geïnstalleerde plist\n'
}

fail() {
    printf 'Fout: %s\n' "$1" >&2
    exit 1
}

trim() {
    local value="$1"
    value="${value#"${value%%[![:space:]]*}"}"
    value="${value%"${value##*[![:space:]]}"}"
    printf '%s' "$value"
}

read_config_value() {
    local requested_key="$1"
    local value=""
    local file raw_line line key candidate

    for file in "$REPOSITORY_ROOT/properties.default.env" "$REPOSITORY_ROOT/properties.env"; do
        [[ -f "$file" ]] || continue
        while IFS= read -r raw_line || [[ -n "$raw_line" ]]; do
            line="$(trim "$raw_line")"
            [[ -n "$line" && "${line:0:1}" != "#" && "$line" == *"="* ]] || continue
            key="$(trim "${line%%=*}")"
            [[ "$key" == "$requested_key" ]] || continue
            candidate="$(trim "${line#*=}")"
            if [[ "${candidate:0:1}" == '"' && "${candidate: -1}" == '"' ]] ||
               [[ "${candidate:0:1}" == "'" && "${candidate: -1}" == "'" ]]; then
                candidate="${candidate:1:${#candidate}-2}"
            fi
            value="$candidate"
        done < "$file"
    done

    candidate="$(printenv "$requested_key" 2>/dev/null || true)"
    [[ -z "$candidate" ]] || value="$candidate"
    printf '%s' "$value"
}

require_owner_only_file() {
    local file="$1"
    [[ -f "$file" && ! -L "$file" ]] || fail "$file moet een regulier bestand zijn en mag geen symlink zijn."
    local mode
    mode="$(stat -f '%Lp' "$file")"
    [[ "$mode" == "600" ]] || fail "$file moet mode 0600 hebben; huidig: $mode. Voer uit: chmod 600 '$file'"
}

find_worker_jar() {
    local candidates=()
    local candidate
    shopt -s nullglob
    for candidate in "$REPOSITORY_ROOT"/agent-runtime-worker/target/agent-runtime-worker-*.jar; do
        case "$candidate" in
            *-sources.jar|*-javadoc.jar|*.original) continue ;;
        esac
        candidates+=("$candidate")
    done
    shopt -u nullglob
    [[ ${#candidates[@]} -eq 1 ]] || fail "verwacht precies één gebouwde worker-JAR. Voer eerst 'mvn -B --no-transfer-progress clean package' uit."
    printf '%s' "${candidates[0]}"
}

validate_provider_credentials() {
    local configured=0
    local codex_directory claude_directory claude_state
    codex_directory="$(read_config_value AR_CODEX_CREDENTIALS_DIR)"
    claude_directory="$(read_config_value AR_CLAUDE_CREDENTIALS_DIR)"

    if [[ -n "$codex_directory" ]]; then
        [[ "$codex_directory" == /* ]] || fail "AR_CODEX_CREDENTIALS_DIR moet een absoluut pad zijn; gebruik geen ~ of \$HOME."
        [[ -d "$codex_directory" ]] || fail "Codex-credentialmap bestaat niet: $codex_directory"
        require_owner_only_file "$codex_directory/auth.json"
        configured=1
    fi

    if [[ -n "$claude_directory" ]]; then
        [[ "$claude_directory" == /* ]] || fail "AR_CLAUDE_CREDENTIALS_DIR moet een absoluut pad zijn; gebruik geen ~ of \$HOME."
        [[ -d "$claude_directory" ]] || fail "Claude-credentialmap bestaat niet: $claude_directory"
        require_owner_only_file "$claude_directory/.credentials.json"
        claude_state="$(cd -- "$claude_directory/.." && pwd -P)/.claude.json"
        require_owner_only_file "$claude_state"
        configured=1
    fi

    [[ "$configured" -eq 1 ]] || fail "configureer ten minste AR_CODEX_CREDENTIALS_DIR of AR_CLAUDE_CREDENTIALS_DIR in properties.env."
}

migrate_legacy_config() {
    local properties="$REPOSITORY_ROOT/properties.env"
    local legacy="$REPOSITORY_ROOT/secrets.env"
    local temporary
    [[ -f "$properties" && ! -L "$properties" ]] || fail "$properties moet vóór migratie een regulier bestand zijn."
    require_owner_only_file "$legacy"
    temporary="$(mktemp -t agent-runtime-properties.XXXXXX.env)"
    trap 'rm -f "$temporary"' EXIT
    chmod 600 "$temporary"
    /usr/bin/awk '
        function trimmed(value) {
            sub(/^[[:space:]]+/, "", value)
            sub(/[[:space:]]+$/, "", value)
            return value
        }
        function config_key(line, separator, key) {
            separator = index(line, "=")
            if (separator == 0) return ""
            key = trimmed(substr(line, 1, separator - 1))
            return key
        }
        function config_value(line, separator, value) {
            separator = index(line, "=")
            value = trimmed(substr(line, separator + 1))
            return value
        }
        FNR == NR {
            key = config_key($0)
            if (key == "AR_WORKER_TOKEN" || key == "AR_CODEX_CREDENTIALS_DIR" || key == "AR_CLAUDE_CREDENTIALS_DIR") {
                legacy[key] = config_value($0)
            }
            next
        }
        {
            key = config_key($0)
            if (key == "AR_EXECUTION_IMAGE") next
            if (key == "AR_WORKER_TOKEN" || key == "AR_CODEX_CREDENTIALS_DIR" || key == "AR_CLAUDE_CREDENTIALS_DIR") {
                if (legacy[key] != "") print key "=" legacy[key]
                else print $0
                seen[key] = 1
                next
            }
            print
        }
        END {
            count = split("AR_WORKER_TOKEN AR_CODEX_CREDENTIALS_DIR AR_CLAUDE_CREDENTIALS_DIR", keys, " ")
            for (loop_index = 1; loop_index <= count; loop_index++) {
                key = keys[loop_index]
                if (!seen[key] && legacy[key] != "") print key "=" legacy[key]
            }
        }
    ' "$legacy" "$properties" > "$temporary"
    install -m 0600 "$temporary" "$properties"
    rm -f "$temporary"
    trap - EXIT
    printf 'Workerconfiguratie is zonder secretwaarden te tonen naar properties.env gemigreerd.\n'
    printf 'secrets.env is niet verwijderd omdat dit bestand ook een lokale OpenShift-secretbron kan zijn.\n'
}

render_plist() {
    local destination="$1"
    local java_bin="$2"
    local worker_jar="$3"
    cp "$TEMPLATE" "$destination"
    /usr/bin/plutil -replace ProgramArguments.0 -string "$java_bin" "$destination"
    /usr/bin/plutil -replace ProgramArguments.2 -string "$worker_jar" "$destination"
    /usr/bin/plutil -replace WorkingDirectory -string "$REPOSITORY_ROOT" "$destination"
    /usr/bin/plutil -replace StandardOutPath -string "$STDOUT_PATH" "$destination"
    /usr/bin/plutil -replace StandardErrorPath -string "$STDERR_PATH" "$destination"
    /usr/bin/plutil -lint "$destination" >/dev/null
}

preflight_and_render() {
    local destination="$1"
    [[ "$(uname -s)" == "Darwin" ]] || fail "deze installer is alleen voor macOS."
    [[ -f "$TEMPLATE" ]] || fail "plist-template ontbreekt: $TEMPLATE"
    require_owner_only_file "$REPOSITORY_ROOT/properties.env"
    if [[ -e "$REPOSITORY_ROOT/project-credentials.env" ]]; then
        require_owner_only_file "$REPOSITORY_ROOT/project-credentials.env"
    fi
    [[ -n "$(read_config_value AR_SERVER_URL)" ]] || fail "AR_SERVER_URL ontbreekt."
    [[ -n "$(read_config_value AR_WORKER_TOKEN)" ]] || fail "AR_WORKER_TOKEN ontbreekt in properties.env."
    validate_provider_credentials

    command -v docker >/dev/null || fail "Docker ontbreekt. Installeer en start Docker Desktop."
    docker info >/dev/null 2>&1 || fail "Docker Desktop draait niet."
    local execution_image
    execution_image="$(read_config_value AR_EXECUTION_IMAGE)"
    [[ -n "$execution_image" ]] || fail "AR_EXECUTION_IMAGE ontbreekt."

    local java_home java_bin worker_jar
    java_home="$(/usr/libexec/java_home -v 21 2>/dev/null)" || fail "JDK 21 ontbreekt."
    java_bin="$java_home/bin/java"
    [[ -x "$java_bin" ]] || fail "Java 21 is niet uitvoerbaar: $java_bin"
    worker_jar="$(find_worker_jar)"
    mkdir -p "$LOG_DIRECTORY"
    render_plist "$destination" "$java_bin" "$worker_jar"
}

action="${1:-}"
[[ $# -eq 1 ]] || { usage >&2; exit 2; }

case "$action" in
    check)
        temporary_plist="$(mktemp -t agent-runtime-worker.XXXXXX.plist)"
        trap 'rm -f "$temporary_plist"' EXIT
        preflight_and_render "$temporary_plist"
        printf 'Configuratie is geldig. De actieve LaunchAgent is niet gewijzigd.\n'
        ;;
    migrate)
        [[ "$(uname -s)" == "Darwin" ]] || fail "deze migratie is alleen voor macOS."
        migrate_legacy_config
        ;;
    install)
        temporary_plist="$(mktemp -t agent-runtime-worker.XXXXXX.plist)"
        trap 'rm -f "$temporary_plist"' EXIT
        preflight_and_render "$temporary_plist"
        mkdir -p "$(dirname -- "$TARGET")"
        if launchctl print "$DOMAIN/$LABEL" >/dev/null 2>&1; then
            launchctl bootout "$DOMAIN/$LABEL"
        fi
        install -m 0644 "$temporary_plist" "$TARGET"
        launchctl bootstrap "$DOMAIN" "$TARGET"
        launchctl enable "$DOMAIN/$LABEL"
        launchctl kickstart -k "$DOMAIN/$LABEL"
        printf 'Worker geïnstalleerd en gestart. Status: launchctl print %s/%s\n' "$DOMAIN" "$LABEL"
        printf 'Logs: tail -F %q %q\n' "$STDOUT_PATH" "$STDERR_PATH"
        ;;
    uninstall)
        [[ "$(uname -s)" == "Darwin" ]] || fail "deze installer is alleen voor macOS."
        if launchctl print "$DOMAIN/$LABEL" >/dev/null 2>&1; then
            launchctl bootout "$DOMAIN/$LABEL"
        fi
        if [[ -f "$TARGET" ]]; then
            rm "$TARGET"
            printf 'LaunchAgent gestopt en plist verwijderd: %s\n' "$TARGET"
        else
            printf 'LaunchAgent was niet geïnstalleerd.\n'
        fi
        ;;
    *)
        usage >&2
        exit 2
        ;;
esac
