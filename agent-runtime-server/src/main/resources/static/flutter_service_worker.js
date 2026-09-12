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
