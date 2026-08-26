class BrowserPlatform {
  static String _token = '';
  static String readToken() => _token;
  static void writeToken(String value) => _token = value;
  static void replaceQuery(String query) {}
  static void download(
    String filename,
    String mimeType,
    String base64Content,
  ) {}
}
