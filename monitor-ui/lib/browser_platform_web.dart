import 'package:web/web.dart' as web;

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
  static void download(String filename, String mimeType, String base64Content) {
    final anchor = web.HTMLAnchorElement()
      ..href = 'data:$mimeType;base64,$base64Content'
      ..download = filename;
    anchor.click();
  }
}
