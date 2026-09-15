import 'dart:math' as math;

import 'package:flutter/material.dart';

const _ink = Color(0xff203c32);
const _muted = Color(0xff697c72);
const _line = Color(0xffe1e9e4);
const _soft = Color(0xffeff5f1);
const _api = Color(0xff2d8058);
const _subscription = Color(0xffa7c9b3);

enum UsageSource { all, api, subscription }

class UsageSelection {
  UsageSelection({DateTime? now}) {
    final today = now ?? DateTime.now();
    through = DateTime(today.year, today.month, today.day);
    from = DateTime(today.year, today.month, today.day - 29);
  }
  late DateTime from;
  late DateTime through;
  UsageSource source = UsageSource.all;
  String? project;
  String currency = 'USD';
  bool weekly = false;
  String sort = 'cost';
}

String usageDate(DateTime date) =>
    '${date.year}-${date.month.toString().padLeft(2, '0')}-${date.day.toString().padLeft(2, '0')}';

String _shortDate(DateTime date) =>
    '${date.day} ${const ['jan', 'feb', 'mrt', 'apr', 'mei', 'jun', 'jul', 'aug', 'sep', 'okt', 'nov', 'dec'][date.month - 1]}';
String _range(DateTime from, DateTime through) =>
    '${_shortDate(from)}${from.year != through.year ? ' ${from.year}' : ''} – ${_shortDate(through)} ${through.year}';
String _runs(int count) => '$count ${count == 1 ? 'run' : 'runs'}';
String _models(int count) => '$count ${count == 1 ? 'model' : 'modellen'}';
String usageMoney(double amount, String currency, {bool precise = false}) {
  final symbol = switch (currency) {
    'USD' => 'US\$',
    'EUR' => '€',
    _ => currency,
  };
  if (!precise && amount > 0 && amount < .01) return '< $symbol 0,01';
  final text = amount.toStringAsFixed(precise ? 6 : 2).split('.');
  final whole = text[0].replaceAllMapped(
    RegExp(r'(\d)(?=(\d{3})+(?!\d))'),
    (m) => '${m[1]}.',
  );
  return '$symbol $whole,${text[1]}';
}

class UsageRow {
  UsageRow(Map<String, dynamic> json)
    : project = json['project'] as String,
      date = DateTime.parse(json['date'] as String),
      vendor = json['vendorId'] as String,
      model = json['model'] as String,
      mode = json['mode'] as String,
      runs = (json['attemptCount'] as num).toInt(),
      unpriced = (json['unpricedAttemptCount'] as num).toInt(),
      partial = (json['partialAttemptCount'] as num).toInt(),
      costs = (json['costs'] as List).cast<Map<String, dynamic>>();
  final String project, vendor, model, mode;
  final DateTime date;
  final int runs, unpriced, partial;
  final List<Map<String, dynamic>> costs;
  bool matches(UsageSource source) => switch (source) {
    UsageSource.all => true,
    UsageSource.api => mode == 'API',
    UsageSource.subscription => mode == 'SUBSCRIPTION',
  };
}

class UsageTotals {
  UsageTotals(Iterable<UsageRow> rows, String currency) {
    for (final row in rows) {
      runs += row.runs;
      unpriced += row.unpriced;
      partial += row.partial;
      models.add('${row.vendor}/${row.model}');
      for (final cost in row.costs.where((c) => c['currency'] == currency)) {
        final amount = double.parse(cost['amount'].toString());
        if (row.mode == 'API') {
          api += amount;
        } else {
          subscription += amount;
        }
      }
    }
  }
  int runs = 0, unpriced = 0, partial = 0;
  double api = 0, subscription = 0;
  final Set<String> models = {};
  double get total => api + subscription;
  bool get unknown => runs > 0 && unpriced == runs;
  String amount(String currency) =>
      unknown ? 'Onbekend' : usageMoney(total, currency);
}

class UsageDashboard extends StatefulWidget {
  const UsageDashboard({
    super.key,
    required this.load,
    required this.selection,
  });
  final Future<Map<String, dynamic>> Function(String path) load;
  final UsageSelection selection;
  @override
  State<UsageDashboard> createState() => UsageDashboardState();
}

class UsageDashboardState extends State<UsageDashboard> {
  List<UsageRow>? rows;
  List<String> projects = [];
  bool loading = true;
  String? error;
  int request = 0;
  final scroll = ScrollController();
  UsageSelection get selection => widget.selection;

  @override
  void initState() {
    super.initState();
    reload();
  }

  @override
  void dispose() {
    request++;
    scroll.dispose();
    super.dispose();
  }

  Future<void> reload({bool newPeriod = false}) async {
    final current = ++request;
    setState(() {
      loading = true;
      error = null;
      if (newPeriod) rows = null;
    });
    try {
      final data = await widget.load(
        Uri(
          path: '/v2/management/usage/dashboard',
          queryParameters: {
            'from': usageDate(selection.from),
            'through': usageDate(selection.through),
            'timeZone': 'Europe/Amsterdam',
          },
        ).toString(),
      );
      if (!mounted || current != request) return;
      final nextRows = (data['rows'] as List)
          .map((r) => UsageRow(r as Map<String, dynamic>))
          .toList();
      setState(() {
        rows = nextRows;
        projects = (data['projects'] as List).cast<String>();
        final currencies = _currencies;
        if (!currencies.contains(selection.currency)) {
          selection.currency = currencies.first;
        }
        loading = false;
      });
    } catch (_) {
      if (!mounted || current != request) return;
      setState(() {
        loading = false;
        error = rows == null
            ? 'Het verbruik kon niet worden geladen.'
            : 'Verversen is niet gelukt. Je ziet de vorige momentopname.';
      });
    }
  }

  List<String> get _currencies {
    final result =
        (rows ?? [])
            .expand((r) => r.costs)
            .map((c) => c['currency'] as String)
            .toSet()
            .toList()
          ..sort();
    return result.isEmpty ? ['USD'] : result;
  }

  void _openProject(String? project) {
    setState(() => selection.project = project);
    if (scroll.hasClients) scroll.jumpTo(0);
  }

  Future<void> _pickPeriod() async {
    final range = await showDialog<DateTimeRange>(
      context: context,
      builder: (_) =>
          _PeriodDialog(from: selection.from, through: selection.through),
    );
    if (range == null || !mounted) return;
    selection.from = range.start;
    selection.through = range.end;
    await reload(newPeriod: true);
  }

  @override
  Widget build(BuildContext context) {
    final filtered = (rows ?? [])
        .where((r) => r.matches(selection.source))
        .toList();
    final own = filtered
        .where(
          (r) => selection.project == null || r.project == selection.project,
        )
        .toList();
    final totals = UsageTotals(own, selection.currency);
    return DefaultTextStyle.merge(
      style: const TextStyle(color: _ink),
      child: ListView(
        controller: scroll,
        children: [
          if (selection.project != null)
            Align(
              alignment: Alignment.centerLeft,
              child: TextButton.icon(
                onPressed: () => _openProject(null),
                icon: const Icon(Icons.arrow_back, size: 16),
                label: const Text('Alle projecten'),
              ),
            )
          else
            const Text(
              'GEBRUIK & KOSTEN',
              style: TextStyle(
                fontSize: 11,
                letterSpacing: 1.6,
                color: _muted,
                fontWeight: FontWeight.w600,
              ),
            ),
          const SizedBox(height: 8),
          Text(
            selection.project ?? 'Grip op je AI-verbruik.',
            style: const TextStyle(
              fontSize: 28,
              letterSpacing: -.8,
              fontWeight: FontWeight.w600,
            ),
          ),
          const SizedBox(height: 6),
          Text(
            selection.project == null
                ? 'Alle projecten. Eén helder overzicht.'
                : '${_runs(totals.runs)} in deze periode',
            style: const TextStyle(color: _muted),
          ),
          const SizedBox(height: 24),
          LayoutBuilder(
            builder: (context, constraints) => Wrap(
              alignment: WrapAlignment.spaceBetween,
              crossAxisAlignment: WrapCrossAlignment.center,
              spacing: 12,
              runSpacing: 12,
              children: [
                _Segments<UsageSource>(
                  values: const {
                    UsageSource.all: 'Alles',
                    UsageSource.api: 'API',
                    UsageSource.subscription: 'Abonnement',
                  },
                  value: selection.source,
                  onChanged: (s) => setState(() => selection.source = s),
                ),
                OutlinedButton.icon(
                  key: const Key('usage-period'),
                  onPressed: _pickPeriod,
                  icon: const Icon(Icons.calendar_month_outlined, size: 17),
                  label: Text(_range(selection.from, selection.through)),
                  style: OutlinedButton.styleFrom(
                    foregroundColor: _ink,
                    backgroundColor: Colors.white,
                    side: const BorderSide(color: _line),
                    padding: const EdgeInsets.symmetric(
                      horizontal: 14,
                      vertical: 16,
                    ),
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(9),
                    ),
                  ),
                ),
                if (_currencies.length > 1)
                  DropdownButton<String>(
                    value: selection.currency,
                    hint: const Text('Valuta'),
                    items: _currencies
                        .map((c) => DropdownMenuItem(value: c, child: Text(c)))
                        .toList(),
                    onChanged: (c) => setState(() => selection.currency = c!),
                  ),
              ],
            ),
          ),
          const SizedBox(height: 20),
          if (loading) const LinearProgressIndicator(minHeight: 2),
          if (error != null)
            Padding(
              padding: const EdgeInsets.only(bottom: 16),
              child: _Panel(
                child: Wrap(
                  spacing: 16,
                  crossAxisAlignment: WrapCrossAlignment.center,
                  children: [
                    Text(error!),
                    TextButton(
                      onPressed: reload,
                      child: const Text('Opnieuw proberen'),
                    ),
                  ],
                ),
              ),
            ),
          if (rows != null) ...[
            _summaryCards(totals, own),
            const SizedBox(height: 14),
            const _Note(
              'Abonnementswaarde is een schatting tegen API-tarieven, geen extra kosten. Je vaste abonnementsprijs is niet meegerekend.',
            ),
            if (totals.unpriced > 0 || totals.partial > 0)
              Padding(
                padding: const EdgeInsets.only(top: 8),
                child: _Note(
                  '${totals.unpriced > 0 ? '${_runs(totals.unpriced)} zonder prijsberekening. ' : ''}${totals.partial > 0 ? '${_runs(totals.partial)} gedeeltelijk gemeten of geprijsd. ' : ''}Het bekende bedrag kan onvolledig zijn.',
                  warning: true,
                ),
              ),
            const SizedBox(height: 20),
            _Panel(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Wrap(
                    alignment: WrapAlignment.spaceBetween,
                    crossAxisAlignment: WrapCrossAlignment.center,
                    spacing: 20,
                    runSpacing: 12,
                    children: [
                      Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          const _Heading('Verbruik door de tijd'),
                          const SizedBox(height: 4),
                          Text(
                            '${selection.currency} · ${selection.project ?? 'alle projecten'}',
                            style: const TextStyle(fontSize: 12, color: _muted),
                          ),
                        ],
                      ),
                      _Segments<bool>(
                        values: const {false: 'Dag', true: 'Week'},
                        value: selection.weekly,
                        onChanged: (v) => setState(() => selection.weekly = v),
                      ),
                    ],
                  ),
                  const SizedBox(height: 18),
                  UsageTimeline(
                    rows: own,
                    from: selection.from,
                    through: selection.through,
                    weekly: selection.weekly,
                    currency: selection.currency,
                  ),
                  const SizedBox(height: 10),
                  Wrap(
                    spacing: 20,
                    runSpacing: 8,
                    children: [
                      if (selection.source != UsageSource.subscription)
                        const _Legend('API-kosten', _api),
                      if (selection.source != UsageSource.api)
                        const _Legend('Abonnementswaarde', _subscription),
                    ],
                  ),
                ],
              ),
            ),
            const SizedBox(height: 24),
            if (selection.project == null)
              _projectList(filtered)
            else
              _modelList(own, totals),
            const SizedBox(height: 20),
            const Text(
              'API & abonnement · per startdatum van de uitvoering · Europe/Amsterdam\nInclusief retries. Lokale uitvoeringen, mocks en v1-jobs vallen buiten dit overzicht.',
              style: TextStyle(fontSize: 11, color: _muted, height: 1.6),
            ),
            const SizedBox(height: 16),
          ],
        ],
      ),
    );
  }

  Widget _summaryCards(UsageTotals totals, List<UsageRow> own) {
    final currency = selection.currency;
    final apiTotals = UsageTotals(own.where((r) => r.mode == 'API'), currency);
    final subTotals = UsageTotals(
      own.where((r) => r.mode == 'SUBSCRIPTION'),
      currency,
    );
    final cards = [
      _Stat(
        label: switch (selection.source) {
          UsageSource.all => 'Totale verbruikswaarde · $currency',
          UsageSource.api => 'Totale API-kosten · $currency',
          UsageSource.subscription => 'Totale abonnementswaarde · $currency',
        },
        value: totals.amount(currency),
        note: selection.source == UsageSource.all
            ? 'API + geschatte abonnementswaarde'
            : _runs(totals.runs),
        primary: true,
      ),
      _Stat(
        label: 'API-kosten',
        value: selection.source == UsageSource.subscription
            ? '—'
            : apiTotals.amount(currency),
        note: selection.source == UsageSource.subscription
            ? 'Buiten dit filter'
            : 'Gerapporteerd of berekend via API-tarieven',
        dot: _api,
      ),
      _Stat(
        label: 'Abonnementswaarde',
        value: selection.source == UsageSource.api
            ? '—'
            : subTotals.amount(currency),
        note: selection.source == UsageSource.api
            ? 'Buiten dit filter'
            : 'Geschat tegen API-tarieven',
        dot: _subscription,
      ),
    ];
    return LayoutBuilder(
      builder: (context, c) {
        final stack =
            c.maxWidth < 620 || MediaQuery.textScalerOf(context).scale(14) > 20;
        return stack
            ? Column(
                children: [
                  for (var i = 0; i < cards.length; i++)
                    Padding(
                      padding: EdgeInsets.only(
                        bottom: i < cards.length - 1 ? 10 : 0,
                      ),
                      child: cards[i],
                    ),
                ],
              )
            : IntrinsicHeight(
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    for (var i = 0; i < cards.length; i++) ...[
                      if (i > 0) const SizedBox(width: 12),
                      Expanded(child: cards[i]),
                    ],
                  ],
                ),
              );
      },
    );
  }

  Widget _projectList(List<UsageRow> filtered) {
    final overall = UsageTotals(filtered, selection.currency);
    final ranked = projects
        .map(
          (p) => (
            p,
            UsageTotals(
              filtered.where((r) => r.project == p),
              selection.currency,
            ),
          ),
        )
        .toList();
    ranked.sort(
      (a, b) => switch (selection.sort) {
        'name' => a.$1.compareTo(b.$1),
        'runs' => b.$2.runs.compareTo(a.$2.runs),
        _ => b.$2.total.compareTo(a.$2.total),
      },
    );
    final byCost = [...ranked]
      ..sort((a, b) => b.$2.total.compareTo(a.$2.total));
    final top = byCost.firstOrNull;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Wrap(
          alignment: WrapAlignment.spaceBetween,
          crossAxisAlignment: WrapCrossAlignment.center,
          spacing: 16,
          runSpacing: 10,
          children: [
            Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                _Heading('Projecten ${projects.length}'),
                const SizedBox(height: 4),
                Text(
                  top != null && overall.total > 0
                      ? '${top.$1} gebruikt het meest · ${_share(top.$2.total, overall.total)} van de bekende waarde'
                      : 'Nog geen geprijsd gebruik in deze periode',
                  style: const TextStyle(fontSize: 12, color: _muted),
                ),
              ],
            ),
            PopupMenuButton<String>(
              key: const Key('usage-sort'),
              tooltip: 'Sorteer projecten',
              initialValue: selection.sort,
              itemBuilder: (_) => const [
                PopupMenuItem(
                  value: 'cost',
                  child: Text('Hoogste verbruik eerst'),
                ),
                PopupMenuItem(value: 'runs', child: Text('Meeste runs eerst')),
                PopupMenuItem(value: 'name', child: Text('Projectnaam A–Z')),
              ],
              onSelected: (v) => setState(() => selection.sort = v),
              child: Padding(
                padding: const EdgeInsets.symmetric(vertical: 12),
                child: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Flexible(
                      child: Text(switch (selection.sort) {
                        'name' => 'Projectnaam A–Z',
                        'runs' => 'Meeste runs eerst',
                        _ => 'Hoogste verbruik eerst',
                      }, style: const TextStyle(fontSize: 12, color: _muted)),
                    ),
                    const SizedBox(width: 8),
                    const Icon(Icons.expand_more, size: 16, color: _muted),
                  ],
                ),
              ),
            ),
          ],
        ),
        const SizedBox(height: 12),
        _Panel(
          padding: EdgeInsets.zero,
          child: Column(
            children: [
              if (ranked.isEmpty)
                const Padding(
                  padding: EdgeInsets.all(24),
                  child: Text('Er zijn nog geen projecten.'),
                ),
              for (var i = 0; i < ranked.length; i++) ...[
                if (i > 0) const Divider(height: 1, color: _line),
                _UsageItem(
                  key: Key('usage-project-${ranked[i].$1}'),
                  name: ranked[i].$1,
                  subtitle: ranked[i].$2.runs == 0
                      ? 'Geen gebruik in deze periode'
                      : '${_runs(ranked[i].$2.runs)} · ${_models(ranked[i].$2.models.length)}${ranked[i].$2.unpriced > 0 ? ' · ${ranked[i].$2.unpriced} ongeprijsd' : ''}',
                  totals: ranked[i].$2,
                  overall: overall.total,
                  currency: selection.currency,
                  onTap: () => _openProject(ranked[i].$1),
                ),
              ],
            ],
          ),
        ),
      ],
    );
  }

  Widget _modelList(List<UsageRow> own, UsageTotals totals) {
    final groups = <(String, String, String), List<UsageRow>>{};
    for (final row in own) {
      groups.putIfAbsent((row.vendor, row.model, row.mode), () => []).add(row);
    }
    final ranked =
        groups.entries
            .map((e) => (e.key, UsageTotals(e.value, selection.currency)))
            .toList()
          ..sort((a, b) => b.$2.total.compareTo(a.$2.total));
    return _Panel(
      padding: EdgeInsets.zero,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Padding(
            padding: EdgeInsets.all(20),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                _Heading('Welke modellen worden gebruikt?'),
                SizedBox(height: 4),
                Text(
                  'Gesorteerd op verbruikswaarde in deze periode',
                  style: TextStyle(fontSize: 12, color: _muted),
                ),
              ],
            ),
          ),
          if (ranked.isEmpty)
            const Padding(
              padding: EdgeInsets.all(24),
              child: Text(
                'Geen gebruik voor dit project binnen deze periode en dit filter.',
              ),
            ),
          for (final item in ranked) ...[
            const Divider(height: 1, color: _line),
            _UsageItem(
              key: Key('usage-model-${item.$1.$1}-${item.$1.$2}-${item.$1.$3}'),
              name: item.$1.$2,
              subtitle:
                  '${item.$1.$1} · ${item.$1.$3 == 'API' ? 'API' : 'Abonnement · schatting'} · ${_runs(item.$2.runs)}${item.$2.unpriced > 0 ? ' · ${item.$2.unpriced} ongeprijsd' : ''}',
              totals: item.$2,
              overall: totals.total,
              currency: selection.currency,
            ),
          ],
          const Divider(height: 1, color: _line),
          Padding(
            padding: const EdgeInsets.all(18),
            child: Text(
              '${_runs(totals.runs)} · ${_models(totals.models.length)}${totals.runs > 0 && !totals.unknown ? ' · Gemiddeld ${usageMoney(totals.total / totals.runs, selection.currency)} bekende waarde per run' : ''}',
              style: const TextStyle(fontSize: 12, color: _muted),
            ),
          ),
        ],
      ),
    );
  }
}

String _share(double value, double total) => total <= 0 || value == 0
    ? '0%'
    : value / total < .01
    ? '<1%'
    : '${(100 * value / total).round()}%';

class _PeriodDialog extends StatefulWidget {
  const _PeriodDialog({required this.from, required this.through});
  final DateTime from, through;
  @override
  State<_PeriodDialog> createState() => _PeriodDialogState();
}

class _PeriodDialogState extends State<_PeriodDialog> {
  late DateTime from = widget.from, through = widget.through;
  String? error;
  Future<void> pick(bool first) async {
    final date = await showDatePicker(
      context: context,
      initialDate: first ? from : through,
      firstDate: DateTime(2020),
      lastDate: DateTime(DateTime.now().year + 1),
      helpText: first ? 'Vanaf datum' : 'Tot en met datum',
      cancelText: 'Annuleren',
      confirmText: 'Kiezen',
    );
    if (date != null && mounted) {
      setState(() {
        if (first) {
          from = date;
        } else {
          through = date;
        }
        error = null;
      });
    }
  }

  void preset(String value) {
    final now = DateTime.now();
    final today = DateTime(now.year, now.month, now.day);
    final start = switch (value) {
      'month' => DateTime(now.year, now.month),
      'previous' => DateTime(now.year, now.month - 1),
      _ => DateTime(now.year, now.month, now.day - int.parse(value) + 1),
    };
    Navigator.pop(
      context,
      DateTimeRange(
        start: start,
        end: value == 'previous' ? DateTime(now.year, now.month, 0) : today,
      ),
    );
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
    title: const Text('Kies een periode'),
    content: SizedBox(
      width: 440,
      child: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                for (final e in const {
                  '7': 'Laatste 7 dagen',
                  '30': 'Laatste 30 dagen',
                  'month': 'Deze maand',
                  'previous': 'Vorige maand',
                  '90': 'Laatste 90 dagen',
                }.entries)
                  OutlinedButton(
                    onPressed: () => preset(e.key),
                    child: Text(e.value),
                  ),
              ],
            ),
            const SizedBox(height: 20),
            const Text(
              'Eigen periode',
              style: TextStyle(fontWeight: FontWeight.w600),
            ),
            const SizedBox(height: 8),
            Wrap(
              spacing: 12,
              runSpacing: 12,
              children: [
                OutlinedButton(
                  key: const Key('usage-from'),
                  onPressed: () => pick(true),
                  child: Text('Van: ${_shortDate(from)} ${from.year}'),
                ),
                OutlinedButton(
                  key: const Key('usage-through'),
                  onPressed: () => pick(false),
                  child: Text(
                    'Tot en met: ${_shortDate(through)} ${through.year}',
                  ),
                ),
              ],
            ),
            const SizedBox(height: 12),
            const Text(
              'Kalenderdagen in Europe/Amsterdam. Maximaal 366 dagen per overzicht.',
              style: TextStyle(fontSize: 12, color: _muted),
            ),
            if (error != null)
              Padding(
                padding: const EdgeInsets.only(top: 12),
                child: Text(
                  error!,
                  style: TextStyle(color: Theme.of(context).colorScheme.error),
                ),
              ),
          ],
        ),
      ),
    ),
    actions: [
      TextButton(
        onPressed: () => Navigator.pop(context),
        child: const Text('Annuleren'),
      ),
      FilledButton(
        onPressed: () {
          final days = DateTime.utc(
            through.year,
            through.month,
            through.day,
          ).difference(DateTime.utc(from.year, from.month, from.day)).inDays;
          if (days < 0 || days >= 366) {
            setState(
              () => error =
                  'Kies een einddatum op of na de startdatum, binnen 366 dagen.',
            );
            return;
          }
          Navigator.pop(context, DateTimeRange(start: from, end: through));
        },
        child: const Text('Periode toepassen'),
      ),
    ],
  );
}

class _Panel extends StatelessWidget {
  const _Panel({
    required this.child,
    this.padding = const EdgeInsets.all(20),
    this.color = Colors.white,
  });
  final Widget child;
  final EdgeInsets padding;
  final Color color;
  @override
  Widget build(BuildContext context) => Container(
    width: double.infinity,
    clipBehavior: Clip.antiAlias,
    decoration: BoxDecoration(
      color: color,
      border: Border.all(color: _line),
      borderRadius: BorderRadius.circular(12),
    ),
    child: Padding(padding: padding, child: child),
  );
}

class _Heading extends StatelessWidget {
  const _Heading(this.text);
  final String text;
  @override
  Widget build(BuildContext context) => Text(
    text,
    style: const TextStyle(
      fontSize: 16,
      fontWeight: FontWeight.w600,
      color: _ink,
    ),
  );
}

class _Note extends StatelessWidget {
  const _Note(this.text, {this.warning = false});
  final String text;
  final bool warning;
  @override
  Widget build(BuildContext context) => Row(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      Icon(
        warning ? Icons.info : Icons.info_outline,
        size: 16,
        color: warning ? const Color(0xff896032) : _muted,
      ),
      const SizedBox(width: 8),
      Expanded(
        child: Text(
          text,
          style: const TextStyle(fontSize: 12, color: _muted, height: 1.5),
        ),
      ),
    ],
  );
}

class _Legend extends StatelessWidget {
  const _Legend(this.text, this.color);
  final String text;
  final Color color;
  @override
  Widget build(BuildContext context) => Row(
    mainAxisSize: MainAxisSize.min,
    children: [
      Container(
        width: 8,
        height: 8,
        decoration: BoxDecoration(
          color: color,
          borderRadius: BorderRadius.circular(2),
        ),
      ),
      const SizedBox(width: 6),
      Flexible(
        child: Text(text, style: const TextStyle(fontSize: 12, color: _muted)),
      ),
    ],
  );
}

class _Stat extends StatelessWidget {
  const _Stat({
    required this.label,
    required this.value,
    required this.note,
    this.primary = false,
    this.dot,
  });
  final String label, value, note;
  final bool primary;
  final Color? dot;
  @override
  Widget build(BuildContext context) => _Panel(
    color: primary ? _soft : Colors.white,
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        dot == null
            ? Text(label, style: const TextStyle(fontSize: 12, color: _muted))
            : _Legend(label, dot!),
        const SizedBox(height: 9),
        Text(
          value,
          style: const TextStyle(
            fontSize: 29,
            fontWeight: FontWeight.w600,
            letterSpacing: -1,
          ),
        ),
        const SizedBox(height: 6),
        Text(note, style: const TextStyle(fontSize: 12, color: _muted)),
      ],
    ),
  );
}

class _Segments<T> extends StatelessWidget {
  const _Segments({
    required this.values,
    required this.value,
    required this.onChanged,
  });
  final Map<T, String> values;
  final T value;
  final ValueChanged<T> onChanged;
  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.all(3),
    decoration: BoxDecoration(
      color: _soft,
      border: Border.all(color: _line),
      borderRadius: BorderRadius.circular(9),
    ),
    child: Wrap(
      spacing: 3,
      runSpacing: 3,
      children: values.entries
          .map(
            (e) => Semantics(
              selected: value == e.key,
              child: TextButton(
                onPressed: () => onChanged(e.key),
                style: TextButton.styleFrom(
                  foregroundColor: value == e.key ? _ink : _muted,
                  backgroundColor: value == e.key
                      ? Colors.white
                      : Colors.transparent,
                  minimumSize: const Size(44, 40),
                  padding: const EdgeInsets.symmetric(horizontal: 13),
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(6),
                  ),
                ),
                child: Text(e.value, style: const TextStyle(fontSize: 13)),
              ),
            ),
          )
          .toList(),
    ),
  );
}

class _UsageItem extends StatelessWidget {
  const _UsageItem({
    super.key,
    required this.name,
    required this.subtitle,
    required this.totals,
    required this.overall,
    required this.currency,
    this.onTap,
  });
  final String name, subtitle, currency;
  final UsageTotals totals;
  final double overall;
  final VoidCallback? onTap;
  @override
  Widget build(BuildContext context) => Material(
    color: Colors.transparent,
    child: InkWell(
      onTap: onTap,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 18),
        child: LayoutBuilder(
          builder: (context, c) {
            final compact =
                c.maxWidth < 600 ||
                MediaQuery.textScalerOf(context).scale(14) > 20;
            final title = Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  name,
                  style: const TextStyle(
                    fontSize: 14,
                    fontWeight: FontWeight.w600,
                  ),
                ),
                const SizedBox(height: 3),
                Text(
                  subtitle,
                  style: const TextStyle(fontSize: 12, color: _muted),
                ),
              ],
            );
            final amount = Tooltip(
              message: totals.unknown
                  ? 'Geen betrouwbare prijsberekening beschikbaar'
                  : usageMoney(totals.total, currency, precise: true),
              child: Text(
                totals.amount(currency),
                style: const TextStyle(
                  fontSize: 14,
                  fontWeight: FontWeight.w600,
                ),
              ),
            );
            if (compact) {
              return Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  title,
                  const SizedBox(height: 10),
                  Row(
                    children: [
                      Expanded(child: amount),
                      Text(
                        _share(totals.total, overall),
                        style: const TextStyle(color: _muted, fontSize: 12),
                      ),
                      if (onTap != null)
                        const Padding(
                          padding: EdgeInsets.only(left: 12),
                          child: Icon(
                            Icons.chevron_right,
                            size: 17,
                            color: _muted,
                          ),
                        ),
                    ],
                  ),
                ],
              );
            }
            return Row(
              children: [
                Expanded(flex: 5, child: title),
                const SizedBox(width: 20),
                Expanded(
                  flex: 3,
                  child: Row(
                    children: [
                      Expanded(
                        child: _ShareBar(totals: totals, overall: overall),
                      ),
                      const SizedBox(width: 10),
                      SizedBox(
                        width: 35,
                        child: Text(
                          _share(totals.total, overall),
                          textAlign: TextAlign.right,
                          style: const TextStyle(fontSize: 12, color: _muted),
                        ),
                      ),
                    ],
                  ),
                ),
                const SizedBox(width: 24),
                SizedBox(
                  width: 115,
                  child: Align(alignment: Alignment.centerRight, child: amount),
                ),
                if (onTap != null)
                  const Padding(
                    padding: EdgeInsets.only(left: 14),
                    child: Icon(Icons.chevron_right, size: 17, color: _muted),
                  ),
              ],
            );
          },
        ),
      ),
    ),
  );
}

class _ShareBar extends StatelessWidget {
  const _ShareBar({required this.totals, required this.overall});
  final UsageTotals totals;
  final double overall;
  @override
  Widget build(BuildContext context) => ClipRRect(
    borderRadius: BorderRadius.circular(3),
    child: LayoutBuilder(
      builder: (_, c) => SizedBox(
        height: 6,
        child: Stack(
          children: [
            Container(color: _soft),
            Positioned(
              left: 0,
              top: 0,
              bottom: 0,
              width: overall > 0 ? c.maxWidth * totals.api / overall : 0,
              child: Container(color: _api),
            ),
            Positioned(
              left: overall > 0 ? c.maxWidth * totals.api / overall : 0,
              top: 0,
              bottom: 0,
              width: overall > 0
                  ? c.maxWidth * totals.subscription / overall
                  : 0,
              child: Container(color: _subscription),
            ),
          ],
        ),
      ),
    ),
  );
}

class UsageBucket {
  UsageBucket(this.from, this.through, this.totals);
  final DateTime from, through;
  final UsageTotals totals;
}

List<UsageBucket> usageBuckets(
  List<UsageRow> rows,
  DateTime from,
  DateTime through,
  bool weekly,
  String currency,
) {
  final groups = <DateTime, List<UsageRow>>{};
  DateTime bucket(DateTime date) => weekly
      ? DateTime(date.year, date.month, date.day - date.weekday + 1)
      : date;
  for (
    var day = from;
    !day.isAfter(through);
    day = DateTime(day.year, day.month, day.day + 1)
  ) {
    groups.putIfAbsent(bucket(day), () => []);
  }
  for (final row in rows) {
    groups[bucket(row.date)]?.add(row);
  }
  return groups.entries.map((e) {
    final end = weekly
        ? DateTime(e.key.year, e.key.month, e.key.day + 6)
        : e.key;
    return UsageBucket(
      e.key.isBefore(from) ? from : e.key,
      end.isAfter(through) ? through : end,
      UsageTotals(e.value, currency),
    );
  }).toList();
}

class UsageTimeline extends StatefulWidget {
  const UsageTimeline({
    super.key,
    required this.rows,
    required this.from,
    required this.through,
    required this.weekly,
    required this.currency,
  });
  final List<UsageRow> rows;
  final DateTime from, through;
  final bool weekly;
  final String currency;
  @override
  State<UsageTimeline> createState() => _UsageTimelineState();
}

class _UsageTimelineState extends State<UsageTimeline> {
  int? selected;
  @override
  void didUpdateWidget(covariant UsageTimeline oldWidget) {
    super.didUpdateWidget(oldWidget);
    selected = null;
  }

  @override
  Widget build(BuildContext context) {
    final buckets = usageBuckets(
      widget.rows,
      widget.from,
      widget.through,
      widget.weekly,
      widget.currency,
    );
    if (buckets.isEmpty) return const Text('Geen periode geselecteerd');
    final current = selected == null ? null : buckets[selected!];
    final noValue = buckets.every((b) => b.totals.total == 0);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        SizedBox(
          height: 224,
          child: LayoutBuilder(
            builder: (context, c) {
              void point(double x) {
                final index =
                    ((x - 54) / math.max(1, c.maxWidth - 66) * buckets.length)
                        .floor()
                        .clamp(0, buckets.length - 1);
                if (index != selected) setState(() => selected = index);
              }

              return Semantics(
                label:
                    'Kosten in ${widget.currency} per ${widget.weekly ? 'week' : 'dag'}. Gebruik Vorige periode en Volgende periode voor exacte bedragen.',
                child: MouseRegion(
                  onHover: (e) => point(e.localPosition.dx),
                  child: GestureDetector(
                    onTapDown: (e) => point(e.localPosition.dx),
                    child: CustomPaint(
                      size: Size(c.maxWidth, 224),
                      painter: _TimelinePainter(
                        buckets,
                        selected,
                        widget.currency,
                      ),
                      child: noValue
                          ? Center(
                              child: Padding(
                                padding: const EdgeInsets.only(left: 54),
                                child: Text(
                                  widget.rows.isEmpty
                                      ? 'Geen gebruik in deze periode'
                                      : 'Geen geprijsd gebruik',
                                  style: const TextStyle(
                                    fontSize: 12,
                                    color: _muted,
                                  ),
                                ),
                              ),
                            )
                          : null,
                    ),
                  ),
                ),
              );
            },
          ),
        ),
        Wrap(
          crossAxisAlignment: WrapCrossAlignment.center,
          spacing: 8,
          runSpacing: 6,
          children: [
            IconButton(
              tooltip: 'Vorige periode',
              visualDensity: VisualDensity.compact,
              onPressed: () => setState(
                () => selected = selected == null
                    ? buckets.length - 1
                    : math.max(0, selected! - 1),
              ),
              icon: const Icon(Icons.chevron_left, size: 18),
            ),
            IconButton(
              tooltip: 'Volgende periode',
              visualDensity: VisualDensity.compact,
              onPressed: () => setState(
                () => selected = selected == null
                    ? 0
                    : math.min(buckets.length - 1, selected! + 1),
              ),
              icon: const Icon(Icons.chevron_right, size: 18),
            ),
            Text(
              current == null
                  ? 'Beweeg over een balk of kies een periode'
                  : '${_shortDate(current.from)}${current.from != current.through ? ' – ${_shortDate(current.through)}' : ''} · ${_runs(current.totals.runs)}',
              style: const TextStyle(fontSize: 12, color: _muted),
            ),
          ],
        ),
        if (current != null)
          Padding(
            padding: const EdgeInsets.only(bottom: 6),
            child: Text(
              current.totals.unknown
                  ? 'Kosten onbekend · geen prijsberekening beschikbaar'
                  : 'API ${usageMoney(current.totals.api, widget.currency, precise: true)} · Abonnement ${usageMoney(current.totals.subscription, widget.currency, precise: true)}${current.totals.unpriced + current.totals.partial > 0 ? ' · onvolledig' : ''}',
              style: const TextStyle(fontSize: 12, color: _ink),
            ),
          ),
      ],
    );
  }
}

class _TimelinePainter extends CustomPainter {
  _TimelinePainter(this.buckets, this.selected, this.currency);
  final List<UsageBucket> buckets;
  final int? selected;
  final String currency;
  void label(Canvas canvas, String text, Offset at, {bool right = false}) {
    final painter = TextPainter(
      text: TextSpan(
        text: text,
        style: const TextStyle(fontSize: 11, color: _muted),
      ),
      textDirection: TextDirection.ltr,
    )..layout();
    painter.paint(canvas, Offset(right ? at.dx - painter.width : at.dx, at.dy));
  }

  @override
  void paint(Canvas canvas, Size size) {
    const left = 54.0, top = 18.0, bottom = 184.0;
    final width = math.max(1.0, size.width - left - 12), height = bottom - top;
    final largest = buckets.map((b) => b.totals.total).fold(0.0, math.max);
    final rawStep = largest == 0 ? .25 : largest * 1.1 / 4;
    final power = math
        .pow(10, (math.log(rawStep) / math.ln10).floor())
        .toDouble();
    final fraction = rawStep / power;
    final stepSize =
        (fraction <= 1
            ? 1
            : fraction <= 2
            ? 2
            : fraction <= 2.5
            ? 2.5
            : fraction <= 5
            ? 5
            : 10) *
        power;
    final maximum = stepSize * 4;
    final decimals = math.max(
      0,
      -(math.log(stepSize) / math.ln10).floor() +
          (fraction > 2 && fraction <= 2.5 ? 1 : 0),
    );
    label(canvas, currency, const Offset(left, 0));
    for (var i = 0; i <= 4; i++) {
      final y = bottom - height * i / 4;
      canvas.drawLine(
        Offset(left, y),
        Offset(left + width, y),
        Paint()..color = _line,
      );
      final value = stepSize * i;
      label(
        canvas,
        (value == 0
                ? '0'
                : value < .001 || value >= 100000
                ? value.toStringAsExponential(1)
                : value.toStringAsFixed(decimals))
            .replaceAll('.', ','),
        Offset(left - 8, y - 7),
        right: true,
      );
    }
    final step = width / buckets.length, bar = math.min(40.0, step * .68);
    for (var i = 0; i < buckets.length; i++) {
      final totals = buckets[i].totals;
      final x = left + step * i + (step - bar) / 2;
      final apiHeight = totals.api / maximum * height,
          subHeight = totals.subscription / maximum * height;
      if (selected == i) {
        canvas.drawRect(
          Rect.fromLTWH(left + step * i, top, step, height),
          Paint()..color = _soft,
        );
      }
      canvas.drawRect(
        Rect.fromLTWH(x, bottom - apiHeight, bar, apiHeight),
        Paint()..color = _api,
      );
      canvas.drawRect(
        Rect.fromLTWH(x, bottom - apiHeight - subHeight, bar, subHeight),
        Paint()..color = _subscription,
      );
    }
    final tickCount = math.min(buckets.length, size.width < 450 ? 3 : 6);
    for (var tick = 0; tick < tickCount; tick++) {
      final index = tickCount == 1
          ? 0
          : (tick * (buckets.length - 1) / (tickCount - 1)).round();
      final x = left + step * index + step / 2;
      label(
        canvas,
        _shortDate(buckets[index].from),
        Offset(
          tick == 0
              ? left
              : tick == tickCount - 1
              ? left + width
              : x - 16,
          bottom + 14,
        ),
        right: tick == tickCount - 1 && tickCount > 1,
      );
    }
  }

  @override
  bool shouldRepaint(covariant _TimelinePainter oldDelegate) => true;
}
