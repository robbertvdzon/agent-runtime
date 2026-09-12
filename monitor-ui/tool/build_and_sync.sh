#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
monitor_dir="$(cd "$script_dir/.." && pwd)"
repository_dir="$(cd "$monitor_dir/.." && pwd)"
static_dir="$repository_dir/agent-runtime-server/src/main/resources/static"

input_hashes="$({
  git -C "$repository_dir" ls-files -- \
    monitor-ui/lib \
    monitor-ui/web \
    monitor-ui/pubspec.yaml \
    monitor-ui/pubspec.lock
} | LC_ALL=C sort | while IFS= read -r relative_path; do
  git -C "$repository_dir" hash-object "$repository_dir/$relative_path"
done)"
frontend_build_id="$(printf '%s\n' "$input_hashes" | git hash-object --stdin | cut -c1-16)"

cd "$monitor_dir"
flutter build web --release \
  --pwa-strategy=none \
  --dart-define="AGENT_RUNTIME_FRONTEND_BUILD_ID=$frontend_build_id"

bundle_hash="$(git hash-object build/web/main.dart.js | cut -c1-12)"
bundle_name="main.$bundle_hash.js"
mv build/web/main.dart.js "build/web/$bundle_name"
BUNDLE_NAME="$bundle_name" perl -pi -e 's/main\.dart\.js/$ENV{BUNDLE_NAME}/g' \
  build/web/flutter_bootstrap.js \
  build/web/flutter.js

if rg -l 'main\.dart\.js' build/web/flutter_bootstrap.js build/web/flutter.js >/dev/null; then
  echo "A fixed main.dart.js reference remains in the Flutter loader." >&2
  exit 1
fi

printf '{"buildId":"%s","bundle":"%s"}\n' "$frontend_build_id" "$bundle_name" \
  > build/web/version.json

cat > build/web/flutter_service_worker.js <<'EOF'
self.addEventListener('install', event => {
  event.waitUntil(self.skipWaiting());
});
self.addEventListener('activate', event => {
  event.waitUntil((async () => {
    try {
      const cacheKeys = await caches.keys();
      await Promise.all(cacheKeys.map(cacheKey => caches.delete(cacheKey)));
      await self.registration.unregister();
      const clients = await self.clients.matchAll();
      clients.forEach(client => client.navigate(client.url));
    } catch (_) {
      // A future navigation still bypasses this unregistered service worker.
    }
  })());
});
EOF

rsync -a --delete --delete-excluded --exclude=.last_build_id build/web/ "$static_dir/"
echo "Synced frontend $frontend_build_id using $bundle_name"
