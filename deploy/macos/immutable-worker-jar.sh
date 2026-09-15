#!/usr/bin/env bash

# Keep the running JVM away from Maven's replaceable target directory.
stage_worker_jar() (
    set -euo pipefail
    local source_jar="$1" installation_directory="$2"
    local temporary digest installed
    mkdir -p "$installation_directory"
    temporary="$(mktemp "$installation_directory/.worker.XXXXXX")"
    trap 'rm -f "$temporary"' EXIT
    cp "$source_jar" "$temporary"
    digest="$(shasum -a 256 "$temporary" | awk '{print $1}')"
    installed="$installation_directory/worker-$digest.jar"
    chmod 0444 "$temporary"
    # Hard-link creation never replaces a JAR used by an existing JVM.
    if ! ln "$temporary" "$installed" 2>/dev/null; then
        [[ -f "$installed" && ! -L "$installed" ]] && cmp -s "$temporary" "$installed" || {
            printf 'Fout: bestaande worker-JAR wijkt af: %s\n' "$installed" >&2
            exit 1
        }
    fi
    printf '%s' "$installed"
)
