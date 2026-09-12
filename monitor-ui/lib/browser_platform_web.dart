import 'dart:js_interop';

import 'package:web/web.dart' as web;

@JS('navigator.serviceWorker')
external _ServiceWorkerContainer? get _serviceWorker;

@JS('caches')
external _CacheStorage? get _caches;

extension type _ServiceWorkerContainer._(JSObject _) implements JSObject {
  external JSPromise<JSArray<_ServiceWorkerRegistration>> getRegistrations();
}

extension type _ServiceWorkerRegistration._(JSObject _) implements JSObject {
  external JSPromise<JSBoolean> unregister();
}

extension type _CacheStorage._(JSObject _) implements JSObject {
  external JSPromise<JSArray<JSString>> keys();
  external JSPromise<JSBoolean> delete(JSString key);
}

class BrowserPlatform {
  static String readToken() =>
      web.window.localStorage.getItem('ar-token') ??
      web.window.sessionStorage.getItem('ar-token') ??
      '';
  static void writeToken(String value) {
    if (value.isEmpty) {
      web.window.localStorage.removeItem('ar-token');
      web.window.sessionStorage.removeItem('ar-token');
      return;
    }
    web.window.localStorage.setItem('ar-token', value);
    // Migreer bestaande sessies weg uit sessionStorage.
    web.window.sessionStorage.removeItem('ar-token');
  }

  static void replaceQuery(String query) => web.window.history.replaceState(
    null,
    '',
    '${web.window.location.pathname}?$query',
  );

  static Future<void> reloadLatest() async {
    try {
      final registrations = await _serviceWorker?.getRegistrations().toDart;
      if (registrations != null) {
        for (final registration in registrations.toDart) {
          await registration.unregister().toDart;
        }
      }
    } catch (_) {
      // Best effort: a reload must still happen when cleanup is unavailable.
    }
    try {
      final cacheKeys = await _caches?.keys().toDart;
      if (cacheKeys != null) {
        for (final cacheKey in cacheKeys.toDart) {
          await _caches?.delete(cacheKey).toDart;
        }
      }
    } catch (_) {
      // Best effort: fixed-name resources are also protected by HTTP headers.
    }
    web.window.location.reload();
  }

  static void download(String filename, String mimeType, String base64Content) {
    final anchor = web.HTMLAnchorElement()
      ..href = 'data:$mimeType;base64,$base64Content'
      ..download = filename;
    anchor.click();
  }
}
