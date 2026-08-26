import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;

import 'browser_platform.dart';

void main() => runApp(const RuntimeMonitor());

class RuntimeMonitor extends StatelessWidget {
  const RuntimeMonitor({super.key});
  @override
  Widget build(BuildContext context) => MaterialApp(
    title: 'Agent Runtime',
    debugShowCheckedModeBanner: false,
    theme: ThemeData(
      colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xff08755d)),
      scaffoldBackgroundColor: const Color(0xfff4f7f4),
      cardTheme: const CardThemeData(margin: EdgeInsets.zero, elevation: 0),
      useMaterial3: true,
    ),
    home: const MonitorShell(),
  );
}

enum ViewKind { active, queue, completed, workers }

class ApiClient {
  String token = BrowserPlatform.readToken();
  Map<String, String> get headers => {'Authorization': 'Bearer $token'};

  Future<Map<String, dynamic>> get(String path) async {
    final response = await http
        .get(Uri.parse(path), headers: headers)
        .timeout(const Duration(seconds: 20));
    if (response.statusCode == 401)
      throw const ApiError('Sessie verlopen', unauthorized: true);
    if (response.statusCode < 200 || response.statusCode >= 300)
      throw ApiError('Backend antwoordde met HTTP ${response.statusCode}');
    return jsonDecode(response.body) as Map<String, dynamic>;
  }

  void saveToken(String value) {
    token = value.trim();
    BrowserPlatform.writeToken(token);
  }

  Future<void> download(String path, String filename, String mimeType) async {
    final response = await http
        .get(Uri.parse(path), headers: headers)
        .timeout(const Duration(seconds: 30));
    if (response.statusCode < 200 || response.statusCode >= 300)
      throw ApiError('Download antwoordde met HTTP ${response.statusCode}');
    BrowserPlatform.download(
      filename,
      mimeType,
      base64Encode(response.bodyBytes),
    );
  }
}

class ApiError implements Exception {
  final String message;
  final bool unauthorized;
  const ApiError(this.message, {this.unauthorized = false});
}

class MonitorShell extends StatefulWidget {
  const MonitorShell({super.key});
  @override
  State<MonitorShell> createState() => _MonitorShellState();
}

class _MonitorShellState extends State<MonitorShell> {
  final api = ApiClient();
  ViewKind selected = ViewKind.active;
  Map<String, dynamic>? snapshot;
  String environment = '…';
  String? error;
  DateTime? snapshotAt;
  Timer? timer;
  String search = Uri.base.queryParameters['search'] ?? '';
  String? cursor = Uri.base.queryParameters['cursor'];
  String? previousCursor;
  String? nextCursor;

  @override
  void initState() {
    super.initState();
    timer = Timer.periodic(const Duration(seconds: 5), (_) {
      if (selected == ViewKind.active || selected == ViewKind.queue)
        refresh(silent: true);
    });
    WidgetsBinding.instance.addPostFrameCallback(
      (_) => api.token.isEmpty ? login() : refresh(),
    );
  }

  @override
  void dispose() {
    timer?.cancel();
    super.dispose();
  }

  Future<void> login() async {
    final controller = TextEditingController(text: api.token);
    final value = await showDialog<String>(
      context: context,
      barrierDismissible: false,
      builder: (context) => AlertDialog(
        title: const Text('Inloggen'),
        content: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 420),
          child: TextField(
            controller: controller,
            obscureText: true,
            autofocus: true,
            decoration: const InputDecoration(labelText: 'Beheertoken'),
          ),
        ),
        actions: [
          FilledButton(
            onPressed: () => Navigator.pop(context, controller.text),
            child: const Text('Inloggen'),
          ),
        ],
      ),
    );
    if (value != null) {
      api.saveToken(value);
      await refresh();
    }
  }

  Future<void> refresh({bool silent = false}) async {
    if (api.token.isEmpty) return;
    try {
      final env = await api.get('/v1/management/environment');
      final path = switch (selected) {
        ViewKind.active => '/v1/management/jobs/running',
        ViewKind.queue => '/v1/management/queue',
        ViewKind.completed =>
          '/v1/management/jobs/completed?limit=30&search=${Uri.encodeQueryComponent(search)}${cursor == null ? '' : '&cursor=${Uri.encodeQueryComponent(cursor!)}'}',
        ViewKind.workers => '/v1/management/workers',
      };
      final data = await api.get(path);
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
      if (e.unauthorized) await login();
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
          ],
        ),
      );
    }
    return Scaffold(
      body: Row(
        children: [
          NavigationRail(
            backgroundColor: const Color(0xff0b3533),
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
            ],
          ),
          Expanded(child: body),
        ],
      ),
    );
  }

  Widget _content() {
    if (snapshot == null)
      return const Center(child: CircularProgressIndicator());
    final items = (snapshot!['items'] as List? ?? const [])
        .cast<Map<String, dynamic>>();
    if (selected == ViewKind.completed) {
      return Column(
        children: [
          SearchBar(
            hintText: 'Zoek op job-ID, technische naam of applicatie',
            leading: const Icon(Icons.search),
            onSubmitted: (value) {
              search = value;
              cursor = null;
              _writeUrl();
              refresh();
            },
          ),
          const SizedBox(height: 16),
          Expanded(
            child: JobList(
              items: items,
              emptyText: search.isEmpty
                  ? 'Er zijn nog geen afgeronde jobs'
                  : 'Geen jobs gevonden voor deze zoekterm',
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

  void _writeUrl() => BrowserPlatform.replaceQuery(
    'search=${Uri.encodeQueryComponent(search)}${cursor == null ? '' : '&cursor=${Uri.encodeQueryComponent(cursor!)}'}',
  );
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
    if (items.isEmpty)
      return Center(
        child: Semantics(
          liveRegion: true,
          child: Text(
            emptyText,
            style: Theme.of(context).textTheme.titleMedium,
          ),
        ),
      );
    return ListView.separated(
      itemCount: items.length,
      separatorBuilder: (_, __) => const SizedBox(height: 10),
      itemBuilder: (context, index) {
        final item = items[index];
        return Card(
          child: ListTile(
            contentPadding: const EdgeInsets.all(16),
            title: Text(
              item['technicalName']?.toString() ?? item['id'].toString(),
              style: const TextStyle(fontWeight: FontWeight.w700),
            ),
            subtitle: Text(
              [
                item['application'],
                item['jobKind'],
                '${item['provider']} · ${item['model']}',
                item['waitingReason'],
                item['progressMessage'],
              ].where((x) => x != null && x.toString().isNotEmpty).join('\n'),
            ),
            trailing: StatusLabel(
              item['status']?.toString() ??
                  item['phase']?.toString() ??
                  'ONBEKEND',
            ),
            onTap: () => Navigator.push(
              context,
              MaterialPageRoute(
                builder: (_) => JobDetail(api: api, id: item['id'].toString()),
              ),
            ),
          ),
        );
      },
    );
  }
}

class WorkerList extends StatelessWidget {
  final List<Map<String, dynamic>> items;
  const WorkerList({super.key, required this.items});
  @override
  Widget build(BuildContext context) {
    if (items.isEmpty)
      return const Center(child: Text('Er zijn geen workers geregistreerd'));
    return ListView.separated(
      itemCount: items.length,
      separatorBuilder: (_, __) => const SizedBox(height: 10),
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
      final value = await widget.api.get('/v1/management/jobs/${widget.id}');
      if (mounted) setState(() => detail = value);
      await loadTranscript();
    } catch (_) {
      if (mounted) setState(() => transcriptStatus = 'Verbinding onderbroken');
    }
  }

  Future<void> loadTranscript() async {
    try {
      final after = transcript.isEmpty ? null : transcript.last['sequence'];
      final page = await widget.api.get(
        '/v1/management/jobs/${widget.id}/transcript${after == null ? '' : '?afterSequence=$after'}',
      );
      final incoming = (page['items'] as List? ?? [])
          .cast<Map<String, dynamic>>();
      if (mounted)
        setState(() {
          final known = transcript.map((x) => x['partId']).toSet();
          transcript.addAll(
            incoming.where((x) => !known.contains(x['partId'])),
          );
          transcriptStatus = page['active'] == true ? 'Live' : 'Afgerond';
        });
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
                Padding(
                  padding: const EdgeInsets.only(bottom: 16),
                  child: Card(
                    child: Padding(
                      padding: const EdgeInsets.all(16),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          const Text(
                            'Artifacts',
                            style: TextStyle(
                              fontSize: 18,
                              fontWeight: FontWeight.w700,
                            ),
                          ),
                          ...(detail!['result']['artifacts'] as List).map(
                            (artifact) => ListTile(
                              title: Text(artifact['filename'].toString()),
                              subtitle: Text(
                                '${artifact['mimeType']} · ${artifact['sizeBytes']} bytes · SHA-256 ${artifact['sha256']}',
                              ),
                              trailing: const Icon(Icons.download),
                              onTap: () => widget.api.download(
                                '/v1/jobs/${widget.id}/artifacts/${artifact['id']}',
                                artifact['filename'].toString(),
                                artifact['mimeType'].toString(),
                              ),
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
                ),
              _section(
                'Technische attempts',
                const JsonEncoder.withIndent('  ').convert(detail!['attempts']),
              ),
              _section(
                'Outputpogingen',
                const JsonEncoder.withIndent(
                  '  ',
                ).convert(detail!['outputAttempts']),
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
