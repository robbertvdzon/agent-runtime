import 'package:web/web.dart' as web;

class BrowserPlatform {
  static String readToken() =>
      web.window.sessionStorage.getItem('ar-token') ?? '';
  static void writeToken(String value) =>
      web.window.sessionStorage.setItem('ar-token', value);
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
