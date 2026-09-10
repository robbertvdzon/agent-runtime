import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:flutter/foundation.dart' show kIsWeb;
import 'package:flutter/material.dart';
import 'package:google_sign_in/google_sign_in.dart';
import 'package:http/http.dart' as http;

import 'browser_platform.dart';
import 'google_signin_button_stub.dart'
    if (dart.library.html) 'google_signin_button_web.dart'
    as gis_button;

void main() => runApp(const RuntimeMonitor());

class RuntimeMonitor extends StatelessWidget {
  const RuntimeMonitor({super.key});
  @override
  Widget build(BuildContext context) {
    final colors =
        ColorScheme.fromSeed(
          seedColor: const Color(0xff007b62),
          brightness: Brightness.light,
        ).copyWith(
          primary: const Color(0xff006b55),
          onPrimary: Colors.white,
          surface: Colors.white,
          onSurface: const Color(0xff10211c),
          outline: const Color(0xff60736c),
        );
    return MaterialApp(
      title: 'Agent Runtime',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        colorScheme: colors,
        scaffoldBackgroundColor: const Color(0xfff7faf8),
        cardTheme: const CardThemeData(
          margin: EdgeInsets.zero,
          elevation: 0,
          color: Colors.white,
          shape: RoundedRectangleBorder(
            side: BorderSide(color: Color(0xffc7d4cf)),
            borderRadius: BorderRadius.all(Radius.circular(14)),
          ),
        ),
        navigationBarTheme: const NavigationBarThemeData(
          backgroundColor: Color(0xffeef8f4),
          indicatorColor: Color(0xffbde8db),
          labelTextStyle: WidgetStatePropertyAll(
            TextStyle(color: Color(0xff10211c), fontWeight: FontWeight.w600),
          ),
          iconTheme: WidgetStatePropertyAll(
            IconThemeData(color: Color(0xff17483d)),
          ),
        ),
        useMaterial3: true,
      ),
      home: const MonitorShell(),
    );
  }
}

enum ViewKind { active, queue, completed, workers, usage }

class ApiClient {
  String token = BrowserPlatform.readToken();
  Map<String, String> get headers => {'Authorization': 'Bearer $token'};

  Future<Map<String, dynamic>> get(String path) async {
    final response = await http
        .get(Uri.parse(path), headers: headers)
        .timeout(const Duration(seconds: 20));
    if (response.statusCode == 401) {
      throw const ApiError('Sessie verlopen', unauthorized: true);
    }
    if (response.statusCode < 200 || response.statusCode >= 300) {
      throw ApiError('Backend antwoordde met HTTP ${response.statusCode}');
    }
    return jsonDecode(response.body) as Map<String, dynamic>;
  }

  void saveToken(String value) {
    token = value.trim();
    BrowserPlatform.writeToken(token);
  }

  void clearToken() => saveToken('');

  Future<AuthConfig> authConfig() async {
    final response = await http
        .get(Uri.parse('/v1/auth/config'))
        .timeout(const Duration(seconds: 20));
    if (response.statusCode < 200 || response.statusCode >= 300) {
      throw ApiError('Google-login kon niet worden geladen');
    }
    final body = jsonDecode(response.body) as Map<String, dynamic>;
    return AuthConfig(
      googleClientId: body['googleClientId']?.toString() ?? '',
      googleEnabled: body['googleEnabled'] == true,
    );
  }

  Future<void> loginWithGoogle(String idToken) async {
    final response = await http
        .post(
          Uri.parse('/v1/auth/google'),
          headers: const {'Content-Type': 'application/json'},
          body: jsonEncode({'idToken': idToken}),
        )
        .timeout(const Duration(seconds: 20));
    if (response.statusCode < 200 || response.statusCode >= 300) {
      var message = 'Google-login is geweigerd';
      try {
        final body = jsonDecode(response.body) as Map<String, dynamic>;
        message = body['message']?.toString() ?? message;
      } catch (_) {}
      throw ApiError(message);
    }
    final body = jsonDecode(response.body) as Map<String, dynamic>;
    final sessionToken = body['token']?.toString() ?? '';
    if (sessionToken.isEmpty) {
      throw const ApiError('De server gaf geen sessietoken terug');
    }
    saveToken(sessionToken);
  }

  Future<void> download(String path, String filename, String mimeType) async {
    final bytes = await getBytes(path);
    BrowserPlatform.download(filename, mimeType, base64Encode(bytes));
  }

  Future<Uint8List> getBytes(String path) async {
    final response = await http
        .get(Uri.parse(path), headers: headers)
        .timeout(const Duration(seconds: 30));
    if (response.statusCode == 401) {
      throw const ApiError('Sessie verlopen', unauthorized: true);
    }
    if (response.statusCode < 200 || response.statusCode >= 300) {
      throw ApiError('Download antwoordde met HTTP ${response.statusCode}');
    }
    return response.bodyBytes;
  }
}

class AuthConfig {
  final String googleClientId;
  final bool googleEnabled;
  const AuthConfig({required this.googleClientId, required this.googleEnabled});
}

class ApiError implements Exception {
  final String message;
  final bool unauthorized;
  const ApiError(this.message, {this.unauthorized = false});
}

class LoginDialog extends StatefulWidget {
  final ApiClient api;
  final bool allowCancel;
  const LoginDialog({super.key, required this.api, required this.allowCancel});

  @override
  State<LoginDialog> createState() => _LoginDialogState();
}

class _LoginDialogState extends State<LoginDialog> {
  final tokenController = TextEditingController();
  GoogleSignIn? googleSignIn;
  StreamSubscription<GoogleSignInAccount?>? authSubscription;
  bool loading = true;
  bool showTokenLogin = false;
  String? error;

  @override
  void initState() {
    super.initState();
    _initialize();
  }

  @override
  void dispose() {
    authSubscription?.cancel();
    tokenController.dispose();
    super.dispose();
  }

  Future<void> _initialize() async {
    try {
      final config = await widget.api.authConfig();
      if (!config.googleEnabled || config.googleClientId.isEmpty) {
        throw const ApiError('Google-login is niet geconfigureerd');
      }
      final signIn = GoogleSignIn(
        clientId: config.googleClientId,
        scopes: const ['email'],
      );
      if (kIsWeb) {
        authSubscription = signIn.onCurrentUserChanged.listen(_loginAccount);
      }
      if (!mounted) return;
      setState(() {
        googleSignIn = signIn;
        loading = false;
      });
    } on ApiError catch (e) {
      if (!mounted) return;
      setState(() {
        error = e.message;
        loading = false;
      });
    } catch (_) {
      if (!mounted) return;
      setState(() {
        error = 'Google-login kon niet worden geladen';
        loading = false;
      });
    }
  }

  Future<void> _startGoogleLogin() async {
    final signIn = googleSignIn;
    if (signIn == null || loading) return;
    setState(() {
      loading = true;
      error = null;
    });
    try {
      final account = await signIn.signIn();
      if (account != null) await _loginAccount(account);
    } catch (_) {
      if (!mounted) return;
      setState(() {
        error = 'Google-login is niet gelukt';
        loading = false;
      });
    }
  }

  Future<void> _loginAccount(GoogleSignInAccount? account) async {
    if (account == null) return;
    if (mounted) {
      setState(() {
        loading = true;
        error = null;
      });
    }
    try {
      final authentication = await account.authentication;
      final idToken = authentication.idToken;
      if (idToken == null) {
        throw const ApiError('Google gaf geen ID-token terug');
      }
      await widget.api.loginWithGoogle(idToken);
      if (mounted) Navigator.pop(context, true);
    } on ApiError catch (e) {
      await googleSignIn?.signOut().catchError((_) => null);
      if (!mounted) return;
      setState(() {
        error = e.message;
        loading = false;
      });
    } catch (_) {
      await googleSignIn?.signOut().catchError((_) => null);
      if (!mounted) return;
      setState(() {
        error = 'Google-login is niet gelukt';
        loading = false;
      });
    }
  }

  void _loginWithToken() {
    if (tokenController.text.trim().isEmpty) {
      setState(() => error = 'Vul een beheertoken in');
      return;
    }
    widget.api.saveToken(tokenController.text);
    Navigator.pop(context, true);
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
    title: const Text('Inloggen bij Agent Runtime'),
    content: ConstrainedBox(
      constraints: const BoxConstraints(maxWidth: 420),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          const Text(
            'Gebruik je Google-account om de uitvoeringsmonitor te openen.',
          ),
          const SizedBox(height: 24),
          if (loading)
            const Center(child: CircularProgressIndicator())
          else if (googleSignIn != null && kIsWeb)
            Center(
              child: SizedBox(
                height: 40,
                child: gis_button.renderGoogleButton(),
              ),
            )
          else if (googleSignIn != null)
            FilledButton.icon(
              onPressed: _startGoogleLogin,
              icon: const Icon(Icons.login),
              label: const Text('Inloggen met Google'),
            ),
          if (error != null)
            Padding(
              padding: const EdgeInsets.only(top: 12),
              child: Text(error!, style: const TextStyle(color: Colors.red)),
            ),
          const SizedBox(height: 16),
          TextButton(
            onPressed: () => setState(() => showTokenLogin = !showTokenLogin),
            child: Text(
              showTokenLogin
                  ? 'Beheertoken verbergen'
                  : 'Inloggen met een beheertoken',
            ),
          ),
          if (showTokenLogin) ...[
            const SizedBox(height: 8),
            TextField(
              controller: tokenController,
              obscureText: true,
              autofocus: true,
              onSubmitted: (_) => _loginWithToken(),
              decoration: const InputDecoration(
                labelText: 'Beheertoken',
                border: OutlineInputBorder(),
              ),
            ),
            const SizedBox(height: 12),
            OutlinedButton(
              onPressed: _loginWithToken,
              child: const Text('Inloggen met beheertoken'),
            ),
          ],
        ],
      ),
    ),
    actions: [
      if (widget.allowCancel)
        TextButton(
          onPressed: () => Navigator.pop(context, false),
          child: const Text('Annuleren'),
        ),
    ],
  );
}

class MonitorShell extends StatefulWidget {
  const MonitorShell({super.key});
  @override
  State<MonitorShell> createState() => _MonitorShellState();
}

class _MonitorShellState extends State<MonitorShell> {
  final api = ApiClient();
  late final TextEditingController searchController;
  ViewKind selected = ViewKind.active;
  Map<String, dynamic>? snapshot;
  String environment = '…';
  String? error;
  DateTime? snapshotAt;
  Timer? timer;
  String search =
      Uri.base.queryParameters['title'] ??
      Uri.base.queryParameters['search'] ??
      '';
  String consumerFilter = Uri.base.queryParameters['consumer'] ?? '';
  DateTime? completedFrom = DateTime.tryParse(
    Uri.base.queryParameters['from'] ?? '',
  )?.toLocal();
  DateTime? completedUntil = DateTime.tryParse(
    Uri.base.queryParameters['until'] ?? '',
  )?.toLocal();
  String? cursor = Uri.base.queryParameters['cursor'];
  String? previousCursor;
  String? nextCursor;
  bool loginOpen = false;

  @override
  void initState() {
    super.initState();
    searchController = TextEditingController(text: search);
    timer = Timer.periodic(const Duration(seconds: 5), (_) {
      if (selected == ViewKind.active || selected == ViewKind.queue) {
        refresh(silent: true);
      }
    });
    WidgetsBinding.instance.addPostFrameCallback(
      (_) => api.token.isEmpty ? login() : refresh(),
    );
  }

  @override
  void dispose() {
    timer?.cancel();
    searchController.dispose();
    super.dispose();
  }

  Future<void> login() async {
    if (loginOpen || !mounted) return;
    loginOpen = true;
    final hadToken = api.token.isNotEmpty;
    bool? loggedIn;
    try {
      loggedIn = await showDialog<bool>(
        context: context,
        barrierDismissible: hadToken,
        builder: (context) => LoginDialog(api: api, allowCancel: hadToken),
      );
    } finally {
      loginOpen = false;
    }
    if (loggedIn == true) await refresh();
  }

  Future<void> refresh({bool silent = false}) async {
    if (api.token.isEmpty) return;
    try {
      final env = await api.get('/v1/management/environment');
      final path = switch (selected) {
        ViewKind.active => '/v2/management/jobs/running',
        ViewKind.queue => '/v2/management/queue',
        ViewKind.completed => _completedPath(),
        ViewKind.workers => '/v2/management/workers',
        ViewKind.usage =>
          '/v2/management/usage/summary?groupBy=TENANT,VENDOR,MODEL,MODE,TASK_TYPE&from=${Uri.encodeQueryComponent(DateTime.now().toUtc().subtract(const Duration(days: 30)).toIso8601String())}&until=${Uri.encodeQueryComponent(DateTime.now().toUtc().toIso8601String())}',
      };
      final data = await api.get(path);
      if (selected == ViewKind.usage) {
        final overview = await api.get('/v2/management/consumers');
        data['consumers'] = overview['items'];
        data['consumerPeriodFrom'] = overview['from'];
        data['consumerPeriodUntil'] = overview['until'];
      }
      if (!mounted) return;
      setState(() {
        environment = env['environment']?.toString() ?? '…';
        snapshot = data;
        previousCursor = data['previousCursor']?.toString();
        nextCursor = data['nextCursor']?.toString();
        snapshotAt = DateTime.now();
        error = null;
      });
    } on ApiError catch (e) {
      if (!mounted) return;
      setState(() => error = e.message);
      if (e.unauthorized) {
        api.clearToken();
        await login();
      }
    } catch (_) {
      if (mounted) setState(() => error = 'Verbinding onderbroken');
    }
  }

  void choose(ViewKind value) {
    setState(() {
      selected = value;
      snapshot = null;
      error = null;
      cursor = null;
    });
    refresh();
  }

  @override
  Widget build(BuildContext context) {
    final narrow = MediaQuery.sizeOf(context).width < 760;
    final body = Column(
      children: [
        _Header(
          environment: environment,
          onRefresh: refresh,
          onLogin: login,
          error: error,
          snapshotAt: snapshotAt,
        ),
        Expanded(
          child: Padding(
            padding: EdgeInsets.all(narrow ? 12 : 24),
            child: _content(),
          ),
        ),
      ],
    );
    if (narrow) {
      return Scaffold(
        body: body,
        bottomNavigationBar: NavigationBar(
          selectedIndex: selected.index,
          onDestinationSelected: (i) => choose(ViewKind.values[i]),
          destinations: const [
            NavigationDestination(
              icon: Icon(Icons.play_circle_outline),
              label: 'Actief',
            ),
            NavigationDestination(
              icon: Icon(Icons.schedule),
              label: 'Wachtrij',
            ),
            NavigationDestination(
              icon: Icon(Icons.check_circle_outline),
              label: 'Afgerond',
            ),
            NavigationDestination(icon: Icon(Icons.computer), label: 'Workers'),
            NavigationDestination(
              icon: UsageCostsIcon(),
              selectedIcon: UsageCostsIcon(selected: true),
              label: 'Gebruik',
            ),
          ],
        ),
      );
    }
    return Scaffold(
      body: Row(
        children: [
          NavigationRail(
            minWidth: 154,
            backgroundColor: const Color(0xffeef8f4),
            indicatorColor: const Color(0xffbde8db),
            selectedIconTheme: const IconThemeData(
              color: Color(0xff004f3f),
              size: 30,
            ),
            unselectedIconTheme: const IconThemeData(
              color: Color(0xff245348),
              size: 28,
            ),
            selectedLabelTextStyle: const TextStyle(
              color: Color(0xff003d31),
              fontWeight: FontWeight.w800,
            ),
            unselectedLabelTextStyle: const TextStyle(
              color: Color(0xff173f36),
              fontWeight: FontWeight.w600,
            ),
            selectedIndex: selected.index,
            onDestinationSelected: (i) => choose(ViewKind.values[i]),
            labelType: NavigationRailLabelType.all,
            destinations: const [
              NavigationRailDestination(
                icon: Icon(Icons.play_circle_outline),
                selectedIcon: Icon(Icons.play_circle),
                label: Text('Actieve jobs'),
              ),
              NavigationRailDestination(
                icon: Icon(Icons.schedule),
                label: Text('Wachtrij'),
              ),
              NavigationRailDestination(
                icon: Icon(Icons.check_circle_outline),
                label: Text('Afgeronde jobs'),
              ),
              NavigationRailDestination(
                icon: Icon(Icons.computer),
                label: Text('Workers'),
              ),
              NavigationRailDestination(
                icon: UsageCostsIcon(),
                selectedIcon: UsageCostsIcon(selected: true),
                label: Text('Gebruik & kosten'),
              ),
            ],
          ),
          Expanded(child: body),
        ],
      ),
    );
  }

  Widget _content() {
    if (snapshot == null) {
      return const Center(child: CircularProgressIndicator());
    }
    if (selected == ViewKind.usage) {
      final rows = (snapshot!['rows'] as List? ?? const [])
          .cast<Map<String, dynamic>>();
      final consumers = (snapshot!['consumers'] as List? ?? const [])
          .cast<Map<String, dynamic>>();
      return UsageList(rows: rows, consumers: consumers);
    }
    final items = (snapshot!['items'] as List? ?? const [])
        .cast<Map<String, dynamic>>();
    if (selected == ViewKind.completed) {
      final consumers = (snapshot!['consumers'] as List? ?? const [])
          .map((value) => value.toString())
          .toList();
      return Column(
        children: [
          _CompletedFilters(
            searchController: searchController,
            consumer: consumerFilter,
            consumers: consumers,
            from: completedFrom,
            until: completedUntil,
            onSearch: (value) => _changeCompletedFilters(search: value),
            onConsumer: (value) => _changeCompletedFilters(consumer: value),
            onFrom: () => _pickCompletedDateTime(isFrom: true),
            onUntil: () => _pickCompletedDateTime(isFrom: false),
            onClear: _clearCompletedFilters,
          ),
          const SizedBox(height: 16),
          Expanded(
            child: JobList(
              items: items,
              emptyText: !_hasCompletedFilters
                  ? 'Er zijn nog geen afgeronde jobs'
                  : 'Geen jobs gevonden met deze filters',
              api: api,
            ),
          ),
          Row(
            mainAxisAlignment: MainAxisAlignment.end,
            children: [
              TextButton(
                onPressed: previousCursor == null
                    ? null
                    : () => _page(previousCursor),
                child: const Text('Vorige'),
              ),
              TextButton(
                onPressed: nextCursor == null ? null : () => _page(nextCursor),
                child: const Text('Volgende'),
              ),
            ],
          ),
        ],
      );
    }
    if (selected == ViewKind.workers) return WorkerList(items: items);
    return JobList(
      items: items,
      emptyText: selected == ViewKind.active
          ? 'Er worden nu geen jobs uitgevoerd'
          : 'De wachtrij is leeg',
      api: api,
    );
  }

  void _page(String? value) {
    cursor = value;
    _writeUrl();
    refresh();
  }

  bool get _hasCompletedFilters =>
      search.isNotEmpty ||
      consumerFilter.isNotEmpty ||
      completedFrom != null ||
      completedUntil != null;

  String _completedPath() {
    final parameters = <String, String>{'limit': '30'};
    if (search.isNotEmpty) parameters['title'] = search;
    if (consumerFilter.isNotEmpty) parameters['consumer'] = consumerFilter;
    if (completedFrom != null) {
      parameters['from'] = completedFrom!.toUtc().toIso8601String();
    }
    if (completedUntil != null) {
      parameters['until'] = completedUntil!.toUtc().toIso8601String();
    }
    if (cursor != null) parameters['cursor'] = cursor!;
    return Uri(
      path: '/v2/management/jobs/completed',
      queryParameters: parameters,
    ).toString();
  }

  void _changeCompletedFilters({String? search, String? consumer}) {
    setState(() {
      if (search != null) this.search = search.trim();
      if (consumer != null) consumerFilter = consumer;
      cursor = null;
    });
    _writeUrl();
    refresh();
  }

  Future<void> _pickCompletedDateTime({required bool isFrom}) async {
    final initial = (isFrom ? completedFrom : completedUntil) ?? DateTime.now();
    final date = await showDatePicker(
      context: context,
      initialDate: initial,
      firstDate: DateTime(2020),
      lastDate: DateTime(2100),
    );
    if (date == null || !mounted) return;
    final time = await showTimePicker(
      context: context,
      initialTime: TimeOfDay.fromDateTime(initial),
    );
    if (time == null || !mounted) return;
    final value = DateTime(
      date.year,
      date.month,
      date.day,
      time.hour,
      time.minute,
    );
    setState(() {
      if (isFrom) {
        completedFrom = value;
      } else {
        completedUntil = value;
      }
      cursor = null;
    });
    _writeUrl();
    refresh();
  }

  void _clearCompletedFilters() {
    searchController.clear();
    setState(() {
      search = '';
      consumerFilter = '';
      completedFrom = null;
      completedUntil = null;
      cursor = null;
    });
    _writeUrl();
    refresh();
  }

  void _writeUrl() {
    final parameters = <String, String>{};
    if (search.isNotEmpty) parameters['title'] = search;
    if (consumerFilter.isNotEmpty) parameters['consumer'] = consumerFilter;
    if (completedFrom != null) {
      parameters['from'] = completedFrom!.toUtc().toIso8601String();
    }
    if (completedUntil != null) {
      parameters['until'] = completedUntil!.toUtc().toIso8601String();
    }
    if (cursor != null) parameters['cursor'] = cursor!;
    BrowserPlatform.replaceQuery(Uri(queryParameters: parameters).query);
  }
}

class UsageCostsIcon extends StatelessWidget {
  final bool selected;
  const UsageCostsIcon({super.key, this.selected = false});

  @override
  Widget build(BuildContext context) {
    final color = selected ? const Color(0xff004f3f) : const Color(0xff245348);
    Widget bar(double height) => Container(
      width: 5,
      height: height,
      decoration: BoxDecoration(
        color: color,
        borderRadius: BorderRadius.circular(2),
      ),
    );
    return Semantics(
      label: 'Gebruik en kosten',
      child: SizedBox(
        width: 28,
        height: 28,
        child: Row(
          mainAxisAlignment: MainAxisAlignment.center,
          crossAxisAlignment: CrossAxisAlignment.end,
          children: [
            bar(10),
            const SizedBox(width: 3),
            bar(18),
            const SizedBox(width: 3),
            bar(25),
          ],
        ),
      ),
    );
  }
}

class _Header extends StatelessWidget {
  final String environment;
  final Future<void> Function({bool silent}) onRefresh;
  final Future<void> Function() onLogin;
  final String? error;
  final DateTime? snapshotAt;
  const _Header({
    required this.environment,
    required this.onRefresh,
    required this.onLogin,
    this.error,
    this.snapshotAt,
  });
  @override
  Widget build(BuildContext context) => Material(
    color: Colors.white,
    child: SafeArea(
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
        child: Row(
          children: [
            const Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    'Agent Runtime',
                    style: TextStyle(fontSize: 24, fontWeight: FontWeight.w800),
                  ),
                  Text('Uitvoeringsmonitor'),
                ],
              ),
            ),
            if (error != null)
              Tooltip(
                message: snapshotAt == null
                    ? error!
                    : '$error · laatste momentopname ${snapshotAt!.toLocal()}',
                child: const Icon(Icons.cloud_off, color: Colors.orange),
              ),
            const SizedBox(width: 10),
            Chip(label: Text(environment)),
            IconButton(
              tooltip: 'Verversen',
              onPressed: () => onRefresh(),
              icon: const Icon(Icons.refresh),
            ),
            IconButton(
              tooltip: 'Opnieuw inloggen',
              onPressed: onLogin,
              icon: const Icon(Icons.account_circle_outlined),
            ),
          ],
        ),
      ),
    ),
  );
}

class _CompletedFilters extends StatelessWidget {
  final TextEditingController searchController;
  final String consumer;
  final List<String> consumers;
  final DateTime? from;
  final DateTime? until;
  final ValueChanged<String> onSearch;
  final ValueChanged<String> onConsumer;
  final VoidCallback onFrom;
  final VoidCallback onUntil;
  final VoidCallback onClear;

  const _CompletedFilters({
    required this.searchController,
    required this.consumer,
    required this.consumers,
    required this.from,
    required this.until,
    required this.onSearch,
    required this.onConsumer,
    required this.onFrom,
    required this.onUntil,
    required this.onClear,
  });

  @override
  Widget build(BuildContext context) => LayoutBuilder(
    builder: (context, constraints) => Wrap(
      spacing: 12,
      runSpacing: 12,
      crossAxisAlignment: WrapCrossAlignment.center,
      children: [
        SizedBox(
          width: constraints.maxWidth < 480 ? constraints.maxWidth : 480,
          child: SearchBar(
            controller: searchController,
            hintText: 'Filter op titel of job-ID',
            leading: const Icon(Icons.search),
            trailing: [
              IconButton(
                tooltip: 'Zoeken',
                onPressed: () => onSearch(searchController.text),
                icon: const Icon(Icons.arrow_forward),
              ),
            ],
            onSubmitted: onSearch,
          ),
        ),
        SizedBox(
          width: constraints.maxWidth < 250 ? constraints.maxWidth : 250,
          child: DropdownButtonFormField<String>(
            key: ValueKey('consumer-$consumer-${consumers.join(',')}'),
            initialValue: consumer,
            decoration: const InputDecoration(
              labelText: 'Consumer',
              border: OutlineInputBorder(),
            ),
            items: [
              const DropdownMenuItem(value: '', child: Text('Alle consumers')),
              ...consumers.map(
                (value) => DropdownMenuItem(value: value, child: Text(value)),
              ),
            ],
            onChanged: (value) => onConsumer(value ?? ''),
          ),
        ),
        OutlinedButton.icon(
          onPressed: onFrom,
          icon: const Icon(Icons.calendar_today_outlined),
          label: Text(
            from == null ? 'Vanaf' : 'Vanaf ${_formatFilterDateTime(from!)}',
          ),
        ),
        OutlinedButton.icon(
          onPressed: onUntil,
          icon: const Icon(Icons.event_outlined),
          label: Text(
            until == null ? 'Tot' : 'Tot ${_formatFilterDateTime(until!)}',
          ),
        ),
        TextButton.icon(
          onPressed: onClear,
          icon: const Icon(Icons.filter_alt_off_outlined),
          label: const Text('Wis filters'),
        ),
      ],
    ),
  );
}

class JobList extends StatelessWidget {
  final List<Map<String, dynamic>> items;
  final String emptyText;
  final ApiClient api;
  const JobList({
    super.key,
    required this.items,
    required this.emptyText,
    required this.api,
  });
  @override
  Widget build(BuildContext context) {
    if (items.isEmpty) {
      return Center(
        child: Semantics(
          liveRegion: true,
          child: Text(
            emptyText,
            style: Theme.of(context).textTheme.titleMedium,
          ),
        ),
      );
    }
    return ListView.separated(
      itemCount: items.length,
      separatorBuilder: (_, _) => const SizedBox(height: 10),
      itemBuilder: (context, index) {
        final item = items[index];
        return Card(
          clipBehavior: Clip.antiAlias,
          child: InkWell(
            onTap: () => Navigator.push(
              context,
              MaterialPageRoute(
                builder: (_) => JobDetail(api: api, id: item['id'].toString()),
              ),
            ),
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        item['technicalName']?.toString() ??
                            item['id'].toString(),
                        style: const TextStyle(
                          fontSize: 16,
                          fontWeight: FontWeight.w800,
                        ),
                      ),
                      const SizedBox(height: 8),
                      StatusLabel(
                        item['status']?.toString() ??
                            item['phase']?.toString() ??
                            'ONBEKEND',
                      ),
                    ],
                  ),
                  const SizedBox(height: 8),
                  Text(
                    [
                          item['application'],
                          item['jobKind'],
                          '${item['provider']} · ${item['model']}',
                          item['waitingReason'],
                          item['progressMessage'],
                        ]
                        .where(
                          (value) =>
                              value != null && value.toString().isNotEmpty,
                        )
                        .join(' · '),
                  ),
                  const SizedBox(height: 10),
                  Wrap(
                    spacing: 16,
                    runSpacing: 8,
                    children: [
                      _JobFact(
                        icon: Icons.schedule,
                        label: 'Aangemaakt',
                        value: _formatInstant(item['createdAt']),
                      ),
                      if (item['completedAt'] != null)
                        _JobFact(
                          icon: Icons.event_available_outlined,
                          label: 'Afgerond',
                          value: _formatInstant(item['completedAt']),
                        ),
                      _JobFact(
                        icon: Icons.timer_outlined,
                        label: 'Looptijd',
                        value: _formatDuration(item['durationMillis']),
                      ),
                      _JobFact(
                        icon: Icons.attach_money,
                        label: 'Kosten',
                        value: _formatJobCosts(item),
                      ),
                    ],
                  ),
                  const SizedBox(height: 12),
                  _ListPreview(
                    label: 'Prompt · eerste 240 tekens',
                    value: item['promptPreview']?.toString() ?? '',
                  ),
                  if ((item['outputPreview']?.toString() ?? '').isNotEmpty) ...[
                    const SizedBox(height: 10),
                    _ListPreview(
                      label: 'Output · eerste 240 tekens',
                      value: item['outputPreview'].toString(),
                    ),
                  ],
                  const SizedBox(height: 12),
                  Wrap(
                    spacing: 8,
                    runSpacing: 8,
                    children: [
                      FileCountLabel(
                        icon: Icons.attach_file,
                        count: item['inputAttachmentCount'] as int? ?? 0,
                        singular: 'attachment',
                        plural: 'attachments',
                      ),
                      FileCountLabel(
                        icon: Icons.image_outlined,
                        count: item['artifactCount'] as int? ?? 0,
                        singular: 'artifact',
                        plural: 'artifacts',
                      ),
                    ],
                  ),
                ],
              ),
            ),
          ),
        );
      },
    );
  }
}

class _JobFact extends StatelessWidget {
  final IconData icon;
  final String label;
  final String value;
  const _JobFact({
    required this.icon,
    required this.label,
    required this.value,
  });

  @override
  Widget build(BuildContext context) => ConstrainedBox(
    constraints: const BoxConstraints(maxWidth: 320),
    child: Row(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.only(top: 2),
          child: Icon(icon, size: 17, color: const Color(0xff31564c)),
        ),
        const SizedBox(width: 5),
        Flexible(child: Text('$label: $value')),
      ],
    ),
  );
}

class _ListPreview extends StatelessWidget {
  final String label;
  final String value;
  const _ListPreview({required this.label, required this.value});

  @override
  Widget build(BuildContext context) => Container(
    width: double.infinity,
    padding: const EdgeInsets.all(12),
    decoration: BoxDecoration(
      color: const Color(0xfff0f6f3),
      borderRadius: BorderRadius.circular(10),
    ),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          label,
          style: const TextStyle(
            color: Color(0xff31564c),
            fontSize: 12,
            fontWeight: FontWeight.w800,
          ),
        ),
        const SizedBox(height: 4),
        Text(value, maxLines: 3, overflow: TextOverflow.ellipsis),
      ],
    ),
  );
}

class FileCountLabel extends StatelessWidget {
  final IconData icon;
  final int count;
  final String singular;
  final String plural;
  const FileCountLabel({
    super.key,
    required this.icon,
    required this.count,
    required this.singular,
    required this.plural,
  });

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
    decoration: BoxDecoration(
      color: count > 0 ? const Color(0xffd7f1e8) : const Color(0xffedf1ef),
      border: Border.all(
        color: count > 0 ? const Color(0xff5c9d89) : const Color(0xffb8c2be),
      ),
      borderRadius: BorderRadius.circular(20),
    ),
    child: Wrap(
      crossAxisAlignment: WrapCrossAlignment.center,
      spacing: 6,
      runSpacing: 2,
      children: [
        Icon(icon, size: 17, color: const Color(0xff17483d)),
        Text(
          '$count ${count == 1 ? singular : plural}',
          style: const TextStyle(fontWeight: FontWeight.w700),
        ),
      ],
    ),
  );
}

class WorkerList extends StatelessWidget {
  final List<Map<String, dynamic>> items;
  const WorkerList({super.key, required this.items});
  @override
  Widget build(BuildContext context) {
    if (items.isEmpty) {
      return const Center(child: Text('Er zijn geen workers geregistreerd'));
    }
    return ListView.separated(
      itemCount: items.length,
      separatorBuilder: (_, _) => const SizedBox(height: 10),
      itemBuilder: (_, i) {
        final wrapper = items[i],
            worker = wrapper['worker'] as Map<String, dynamic>;
        return Card(
          child: ListTile(
            contentPadding: const EdgeInsets.all(16),
            title: Text(
              worker['workerId'].toString(),
              style: const TextStyle(fontWeight: FontWeight.w700),
            ),
            subtitle: Text(
              'Capaciteit ${wrapper['activeJobs']}/${worker['maxConcurrency']}\nProviders: ${(worker['providers'] as List? ?? []).join(', ')}\nCapabilities: ${(worker['capabilities'] as List? ?? []).join(', ')}\nActuele job: ${wrapper['currentTechnicalName'] ?? 'geen'}',
            ),
            trailing: StatusLabel(worker['status'].toString()),
          ),
        );
      },
    );
  }
}

class StatusLabel extends StatelessWidget {
  final String value;
  const StatusLabel(this.value, {super.key});
  @override
  Widget build(BuildContext context) => Semantics(
    label: 'Status $value',
    child: Chip(label: Text(value.replaceAll('_', ' '))),
  );
}

class JobDetail extends StatefulWidget {
  final ApiClient api;
  final String id;
  const JobDetail({super.key, required this.api, required this.id});
  @override
  State<JobDetail> createState() => _JobDetailState();
}

class _JobDetailState extends State<JobDetail> {
  Map<String, dynamic>? detail;
  final transcript = <Map<String, dynamic>>[];
  int? transcriptCursor;
  String transcriptStatus = 'Live';
  Timer? timer;
  @override
  void initState() {
    super.initState();
    load();
    timer = Timer.periodic(const Duration(seconds: 3), (_) => loadTranscript());
  }

  @override
  void dispose() {
    timer?.cancel();
    super.dispose();
  }

  Future<void> load() async {
    try {
      final value = await widget.api.get('/v2/management/jobs/${widget.id}');
      if (mounted) setState(() => detail = value);
      await loadTranscript();
    } catch (_) {
      if (mounted) setState(() => transcriptStatus = 'Verbinding onderbroken');
    }
  }

  Future<void> loadTranscript() async {
    try {
      final after = transcriptCursor;
      final page = await widget.api.get(
        '/v2/management/jobs/${widget.id}/transcript${after == null ? '' : '?afterSequence=$after'}',
      );
      final incoming = (page['items'] as List? ?? [])
          .cast<Map<String, dynamic>>();
      if (mounted) {
        setState(() {
          final known = transcript.map((x) => x['partId']).toSet();
          transcript.addAll(
            incoming.where((x) => !known.contains(x['partId'])),
          );
          transcriptCursor = page['nextSequence'] as int?;
          transcriptStatus = page['active'] == true ? 'Live' : 'Afgerond';
        });
      }
    } catch (_) {
      if (mounted) setState(() => transcriptStatus = 'Verbinding onderbroken');
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(
      title: Text(detail?['job']?['technicalName']?.toString() ?? 'Jobdetail'),
      actions: [
        IconButton(
          onPressed: load,
          tooltip: 'Verversen',
          icon: const Icon(Icons.refresh),
        ),
      ],
    ),
    body: detail == null
        ? const Center(child: CircularProgressIndicator())
        : ListView(
            padding: const EdgeInsets.all(16),
            children: [
              _section(
                'Metadata',
                const JsonEncoder.withIndent('  ').convert(detail!['job']),
              ),
              _section('Volledige prompt', detail!['prompt']?.toString() ?? ''),
              if ((detail!['inputAttachments'] as List? ?? const []).isNotEmpty)
                FileCollection(
                  title: 'Meegegeven attachments',
                  emptyText: 'Geen attachments meegegeven',
                  items: (detail!['inputAttachments'] as List)
                      .cast<Map<String, dynamic>>(),
                  api: widget.api,
                  pathFor: (item) =>
                      '/v2/management/jobs/${widget.id}/attachments/${item['id']}',
                ),
              if (detail!['errorCode'] != null)
                _section(
                  'Fout',
                  '${detail!['errorCode']}\n${detail!['errorMessage'] ?? ''}',
                ),
              if (detail!['result'] != null)
                _section(
                  'Resultaat',
                  const JsonEncoder.withIndent(
                    '  ',
                  ).convert(detail!['result']['result']),
                ),
              if (detail!['result'] != null &&
                  (detail!['result']['artifacts'] as List? ?? const [])
                      .isNotEmpty)
                FileCollection(
                  title: 'Teruggekomen artifacts',
                  emptyText: 'Geen artifacts teruggekomen',
                  items: (detail!['result']['artifacts'] as List)
                      .cast<Map<String, dynamic>>(),
                  api: widget.api,
                  pathFor: (item) =>
                      '/v2/management/jobs/${widget.id}/artifacts/${item['id']}',
                ),
              _section(
                'Technische attempts',
                const JsonEncoder.withIndent('  ').convert(detail!['attempts']),
              ),
              Text(
                'Transcript · $transcriptStatus',
                style: Theme.of(context).textTheme.titleLarge,
              ),
              const SizedBox(height: 8),
              if (transcript.isEmpty)
                const Card(
                  child: Padding(
                    padding: EdgeInsets.all(16),
                    child: Text('Nog geen zichtbaar transcript beschikbaar.'),
                  ),
                ),
              ...transcript.map(
                (part) => Card(
                  child: Padding(
                    padding: const EdgeInsets.all(16),
                    child: SelectableText(
                      '${part['kind']}${part['redacted'] == true ? ' · Waarde door Agent Runtime afgeschermd' : ''}\n\n${part['text']}',
                    ),
                  ),
                ),
              ),
            ],
          ),
  );

  Widget _section(String title, String value) => Padding(
    padding: const EdgeInsets.only(bottom: 16),
    child: Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              title,
              style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w700),
            ),
            const SizedBox(height: 8),
            SelectableText(value),
          ],
        ),
      ),
    ),
  );
}

class FileCollection extends StatelessWidget {
  final String title;
  final String emptyText;
  final List<Map<String, dynamic>> items;
  final ApiClient api;
  final String Function(Map<String, dynamic>) pathFor;
  const FileCollection({
    super.key,
    required this.title,
    required this.emptyText,
    required this.items,
    required this.api,
    required this.pathFor,
  });

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(bottom: 16),
    child: Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              title,
              style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w800),
            ),
            const SizedBox(height: 12),
            if (items.isEmpty) Text(emptyText),
            ...items.map(
              (item) => Padding(
                padding: const EdgeInsets.only(bottom: 12),
                child: _FileItem(item: item, api: api, path: pathFor(item)),
              ),
            ),
          ],
        ),
      ),
    ),
  );
}

class _FileItem extends StatefulWidget {
  final Map<String, dynamic> item;
  final ApiClient api;
  final String path;
  const _FileItem({required this.item, required this.api, required this.path});

  @override
  State<_FileItem> createState() => _FileItemState();
}

class _FileItemState extends State<_FileItem> {
  Future<Uint8List>? preview;

  bool get isImage =>
      widget.item['mimeType']?.toString().startsWith('image/') == true;

  @override
  void initState() {
    super.initState();
    if (isImage) preview = widget.api.getBytes(widget.path);
  }

  @override
  void didUpdateWidget(covariant _FileItem oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.path != widget.path ||
        oldWidget.item['mimeType'] != widget.item['mimeType']) {
      preview = isImage ? widget.api.getBytes(widget.path) : null;
    }
  }

  @override
  Widget build(BuildContext context) {
    final filename = widget.item['filename']?.toString() ?? 'bestand';
    final mimeType =
        widget.item['mimeType']?.toString() ?? 'application/octet-stream';
    final size = widget.item['sizeBytes'] as int? ?? 0;
    return Container(
      width: double.infinity,
      decoration: BoxDecoration(
        color: const Color(0xfff5f9f7),
        border: Border.all(color: const Color(0xffc7d4cf)),
        borderRadius: BorderRadius.circular(12),
      ),
      clipBehavior: Clip.antiAlias,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (isImage)
            FutureBuilder<Uint8List>(
              future: preview,
              builder: (context, snapshot) {
                if (snapshot.hasData) {
                  return Container(
                    width: double.infinity,
                    constraints: const BoxConstraints(maxHeight: 480),
                    color: Colors.white,
                    child: Image.memory(
                      snapshot.data!,
                      fit: BoxFit.contain,
                      semanticLabel: 'Voorbeeld van $filename',
                    ),
                  );
                }
                if (snapshot.hasError) {
                  return const Padding(
                    padding: EdgeInsets.all(20),
                    child: Text(
                      'Afbeeldingsvoorbeeld kon niet worden geladen.',
                    ),
                  );
                }
                return const SizedBox(
                  height: 120,
                  child: Center(child: CircularProgressIndicator()),
                );
              },
            ),
          ListTile(
            leading: Icon(isImage ? Icons.image_outlined : Icons.description),
            title: Text(filename),
            subtitle: Text(
              '$mimeType · ${_formatBytes(size)}\nSHA-256 ${widget.item['sha256']}',
            ),
            trailing: IconButton(
              tooltip: 'Download $filename',
              icon: const Icon(Icons.download),
              onPressed: () =>
                  widget.api.download(widget.path, filename, mimeType),
            ),
          ),
        ],
      ),
    );
  }
}

class UsageList extends StatelessWidget {
  final List<Map<String, dynamic>> rows;
  final List<Map<String, dynamic>> consumers;
  const UsageList({super.key, required this.rows, required this.consumers});

  @override
  Widget build(BuildContext context) {
    if (rows.isEmpty && consumers.isEmpty) {
      return const Center(child: Text('Er is nog geen gebruik gemeten'));
    }
    return ListView(
      children: [
        Text(
          'Consumers',
          style: Theme.of(
            context,
          ).textTheme.headlineSmall?.copyWith(fontWeight: FontWeight.w800),
        ),
        const SizedBox(height: 4),
        const Text(
          'Jobaantallen per consumer en kosten over de afgelopen 30 dagen.',
        ),
        const SizedBox(height: 14),
        ...consumers.expand(
          (consumer) => [
            _ConsumerUsageCard(consumer: consumer),
            const SizedBox(height: 12),
          ],
        ),
        const SizedBox(height: 12),
        Text(
          'Gebruik per model · afgelopen 30 dagen',
          style: Theme.of(
            context,
          ).textTheme.headlineSmall?.copyWith(fontWeight: FontWeight.w800),
        ),
        const SizedBox(height: 14),
        if (rows.isEmpty)
          const Card(
            child: Padding(
              padding: EdgeInsets.all(16),
              child: Text('Er is in deze periode nog geen v2-gebruik gemeten.'),
            ),
          ),
        ...rows.expand((row) {
          final dimensions = (row['dimensions'] as Map? ?? const {}).map(
            (key, value) => MapEntry(key.toString(), value.toString()),
          );
          final metrics = (row['metrics'] as List? ?? const [])
              .cast<Map<String, dynamic>>();
          final shares = (row['usageShares'] as List? ?? const [])
              .cast<Map<String, dynamic>>();
          final costs = (row['costs'] as List? ?? const [])
              .cast<Map<String, dynamic>>();
          String dimension(String key) => dimensions[key] ?? '—';
          final metricText = metrics
              .map((metric) {
                final share = shares
                    .where((item) => item['metric'] == metric['metric'])
                    .firstOrNull;
                final suffix = share == null
                    ? ''
                    : ' (${share['percentage']}%)';
                return '${metric['metric']}: ${metric['quantity']} ${metric['unit']}$suffix';
              })
              .join(' · ');
          final costText = costs.isEmpty
              ? 'Geen eurobedrag beschikbaar'
              : costs
                    .map(
                      (cost) =>
                          '${cost['currency']} ${cost['amount']} (${cost['kind']})',
                    )
                    .join(' · ');
          return [
            Card(
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      dimension('tenantId'),
                      style: Theme.of(context).textTheme.titleLarge?.copyWith(
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                    const SizedBox(height: 4),
                    Text(
                      '${dimension('vendorId')} · ${dimension('model')} · ${dimension('mode')} · ${dimension('taskType')}',
                    ),
                    const SizedBox(height: 12),
                    Text(
                      '${row['jobCount']} jobs · ${row['attemptCount']} uitvoeringen · ${row['unknownUsageAttemptCount']} zonder meetbare usage',
                    ),
                    if (metricText.isNotEmpty) ...[
                      const SizedBox(height: 6),
                      Text(metricText),
                    ],
                    const SizedBox(height: 6),
                    Text(
                      costText,
                      style: const TextStyle(fontWeight: FontWeight.w600),
                    ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 12),
          ];
        }),
      ],
    );
  }
}

class _ConsumerUsageCard extends StatelessWidget {
  final Map<String, dynamic> consumer;
  const _ConsumerUsageCard({required this.consumer});

  @override
  Widget build(BuildContext context) {
    final models = (consumer['models'] as List? ?? const [])
        .cast<Map<String, dynamic>>();
    final costs = (consumer['costsInPeriod'] as List? ?? const [])
        .cast<Map<String, dynamic>>();
    final legacyJobs = consumer['legacyJobsInPeriod'] as int? ?? 0;
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              consumer['consumer']?.toString() ?? 'Onbekende consumer',
              style: Theme.of(
                context,
              ).textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w800),
            ),
            const SizedBox(height: 12),
            Wrap(
              spacing: 20,
              runSpacing: 10,
              children: [
                _Statistic(label: 'Totaal', value: '${consumer['totalJobs']}'),
                _Statistic(
                  label: '24 uur',
                  value: '${consumer['jobsLast24Hours']}',
                ),
                _Statistic(
                  label: '7 dagen',
                  value: '${consumer['jobsLast7Days']}',
                ),
                _Statistic(
                  label: '30 dagen',
                  value: '${consumer['jobsLast30Days']}',
                ),
                _Statistic(
                  label: 'Kosten 30 dagen',
                  value: _formatCosts(costs),
                ),
              ],
            ),
            const SizedBox(height: 12),
            Text(
              models.isEmpty
                  ? 'Nog geen modellen gebruikt'
                  : 'Modellen: ${models.map((model) => '${model['vendorId']} / ${model['model']}${model['mode'] == null ? '' : ' / ${model['mode']}'} (${model['jobCount']})').join(' · ')}',
            ),
            if (legacyJobs > 0) ...[
              const SizedBox(height: 6),
              Text(
                '$legacyJobs v1-${legacyJobs == 1 ? 'job heeft' : 'jobs hebben'} geen betrouwbare kostenregistratie.',
                style: const TextStyle(color: Color(0xff6b5440)),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

class _Statistic extends StatelessWidget {
  final String label;
  final String value;
  const _Statistic({required this.label, required this.value});

  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      Text(label, style: const TextStyle(color: Color(0xff52665f))),
      Text(
        value,
        style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w800),
      ),
    ],
  );
}

String _formatInstant(dynamic raw) {
  final value = DateTime.tryParse(raw?.toString() ?? '')?.toLocal();
  return value == null ? 'Onbekend' : _formatDateTime(value);
}

String _formatDateTime(DateTime value) =>
    '${value.day.toString().padLeft(2, '0')}-${value.month.toString().padLeft(2, '0')}-${value.year} '
    '${value.hour.toString().padLeft(2, '0')}:${value.minute.toString().padLeft(2, '0')}:${value.second.toString().padLeft(2, '0')}';

String _formatFilterDateTime(DateTime value) =>
    '${value.day.toString().padLeft(2, '0')}-${value.month.toString().padLeft(2, '0')} '
    '${value.hour.toString().padLeft(2, '0')}:${value.minute.toString().padLeft(2, '0')}';

String _formatDuration(dynamic raw) {
  final milliseconds = raw is num ? raw.toInt() : int.tryParse('$raw');
  if (milliseconds == null) return 'Niet beschikbaar';
  final duration = Duration(milliseconds: milliseconds);
  final parts = <String>[];
  if (duration.inHours > 0) parts.add('${duration.inHours}u');
  final minutes = duration.inMinutes.remainder(60);
  if (minutes > 0 || duration.inHours > 0) parts.add('${minutes}m');
  final seconds = duration.inSeconds.remainder(60);
  parts.add('${seconds}s');
  return parts.join(' ');
}

String _formatJobCosts(Map<String, dynamic> job) {
  if (job['costAvailable'] != true) return 'Niet beschikbaar';
  return _formatCosts(
    (job['costs'] as List? ?? const []).cast<Map<String, dynamic>>(),
  );
}

String _formatCosts(List<Map<String, dynamic>> costs) {
  if (costs.isEmpty) return 'Geen bedrag beschikbaar';
  return costs
      .map((cost) => '${cost['currency']} ${cost['amount']}')
      .join(' · ');
}

String _formatBytes(int bytes) {
  if (bytes >= 1024 * 1024) {
    return '${(bytes / (1024 * 1024)).toStringAsFixed(1)} MB';
  }
  if (bytes >= 1024) return '${(bytes / 1024).toStringAsFixed(1)} kB';
  return '$bytes bytes';
}
