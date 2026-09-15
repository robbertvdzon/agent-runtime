import 'dart:async';

import 'package:agent_runtime_monitor/usage_dashboard.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

Map<String, dynamic> row(
  String project,
  String mode,
  String amount, {
  String date = '2026-09-15',
  String currency = 'USD',
  int runs = 2,
  int unpriced = 0,
  String model = 'test-model',
}) => {
  'project': project,
  'date': date,
  'vendorId': 'openai',
  'model': model,
  'mode': mode,
  'attemptCount': runs,
  'unpricedAttemptCount': unpriced,
  'partialAttemptCount': 0,
  'costs': unpriced == runs
      ? <Map<String, dynamic>>[]
      : [
          {
            'kind': mode == 'API' ? 'CALCULATED' : 'API_EQUIVALENT',
            'status': 'ESTIMATED',
            'amount': amount,
            'currency': currency,
          },
        ],
};
Map<String, dynamic> fixture() => {
  'projects': ['product-factory', 'hkh', 'hkh-autopilot'],
  'rows': [
    row('product-factory', 'API', '10'),
    row('product-factory', 'SUBSCRIPTION', '5'),
    row('hkh', 'SUBSCRIPTION', '20'),
    row('hkh', 'API', '0', unpriced: 2),
  ],
};

Future<void> showDashboard(
  WidgetTester tester, {
  Future<Map<String, dynamic>> Function(String)? load,
  UsageSelection? selection,
  GlobalKey<UsageDashboardState>? key,
  double width = 1100,
  double scale = 1,
}) async {
  tester.view.physicalSize = Size(width, 1800);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.resetPhysicalSize);
  await tester.pumpWidget(
    MaterialApp(
      home: Scaffold(
        body: MediaQuery(
          data: MediaQueryData(
            size: Size(width, 1800),
            textScaler: TextScaler.linear(scale),
          ),
          child: UsageDashboard(
            key: key,
            load: load ?? (_) async => fixture(),
            selection: selection ?? UsageSelection(now: DateTime(2026, 9, 15)),
          ),
        ),
      ),
    ),
  );
  await tester.pump();
}

void main() {
  test('totals never mix currencies and unknown costs are not zero', () {
    final rows = [
      UsageRow(row('a', 'API', '3')),
      UsageRow(row('a', 'SUBSCRIPTION', '7')),
      UsageRow(row('b', 'API', '100', currency: 'EUR')),
    ];
    final totals = UsageTotals(rows, 'USD');
    expect(totals.total, 10);
    expect(totals.runs, 6);
    expect(
      UsageTotals(rows.where((r) => r.matches(UsageSource.api)), 'USD').total,
      3,
    );
    expect(
      UsageTotals([
        UsageRow(row('b', 'API', '0', unpriced: 2)),
      ], 'USD').amount('USD'),
      'Onbekend',
    );
    expect(UsageTotals([], 'USD').amount('USD'), 'US\$ 0,00');
    expect(usageMoney(.000228, 'USD'), '< US\$ 0,01');
    expect(usageMoney(.000228, 'USD', precise: true), 'US\$ 0,000228');
  });

  test('weekly buckets include empty days and clip both partial weeks', () {
    final rows = [
      UsageRow(row('a', 'API', '2', date: '2026-09-09')),
      UsageRow(row('a', 'API', '3', date: '2026-09-15')),
    ];
    final from = DateTime(2026, 9, 9), through = DateTime(2026, 9, 15);
    final daily = usageBuckets(rows, from, through, false, 'USD');
    expect(daily.length, 7);
    expect(daily[1].totals.total, 0);
    final weeks = usageBuckets(rows, from, through, true, 'USD');
    expect(weeks.length, 2);
    expect(weeks.first.from, from);
    expect(weeks.first.through, DateTime(2026, 9, 13));
    expect(weeks.last.from, DateTime(2026, 9, 14));
    expect(weeks.last.through, through);
    expect(weeks.fold(0.0, (v, b) => v + b.totals.total), 5);
  });

  testWidgets(
    'source affects totals ranking and details without extra requests',
    (tester) async {
      final paths = <String>[];
      final selection = UsageSelection(now: DateTime(2026, 9, 15));
      await showDashboard(
        tester,
        selection: selection,
        load: (path) async {
          paths.add(path);
          return fixture();
        },
      );
      expect(Uri.parse(paths.single).queryParameters, {
        'from': '2026-08-17',
        'through': '2026-09-15',
        'timeZone': 'Europe/Amsterdam',
      });
      expect(find.text('US\$ 35,00'), findsOneWidget);
      expect(find.textContaining('hkh gebruikt het meest'), findsOneWidget);
      await tester.tap(find.text('API').first);
      await tester.pump();
      expect(
        find.textContaining('product-factory gebruikt het meest'),
        findsOneWidget,
      );
      expect(find.text('Onbekend'), findsOneWidget);
      await tester.ensureVisible(find.text('product-factory').last);
      await tester.tap(find.text('product-factory').last);
      await tester.pump();
      expect(find.text('Welke modellen worden gebruikt?'), findsOneWidget);
      expect(find.text('test-model'), findsOneWidget);
      expect(selection.source, UsageSource.api);
      await tester.tap(find.text('Week'));
      await tester.pump();
      expect(selection.weekly, isTrue);
      await tester.tap(find.text('Alle projecten'));
      await tester.pump();
      expect(selection.source, UsageSource.api);
      expect(selection.from, DateTime(2026, 8, 17));
      expect(paths, hasLength(1));
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('period preset updates every project and empty detail works', (
    tester,
  ) async {
    final paths = <String>[];
    final selection = UsageSelection(now: DateTime(2026, 9, 15));
    await showDashboard(
      tester,
      selection: selection,
      load: (p) async {
        paths.add(p);
        return fixture();
      },
    );
    await tester.tap(find.byKey(const Key('usage-period')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Laatste 7 dagen'));
    await tester.pumpAndSettle();
    final parameters = Uri.parse(paths.last).queryParameters;
    expect(
      DateTime.parse(
        parameters['through']!,
      ).difference(DateTime.parse(parameters['from']!)).inDays,
      6,
    );
    await tester.ensureVisible(find.text('hkh-autopilot'));
    await tester.tap(find.text('hkh-autopilot'));
    await tester.pump();
    expect(
      find.text(
        'Geen gebruik voor dit project binnen deze periode en dit filter.',
      ),
      findsOneWidget,
    );
    expect(find.text('US\$ 0,00'), findsNWidgets(3));
    expect(tester.takeException(), isNull);
  });

  testWidgets('late request cannot replace a more recent selection', (
    tester,
  ) async {
    final first = Completer<Map<String, dynamic>>(),
        second = Completer<Map<String, dynamic>>();
    var calls = 0;
    await showDashboard(
      tester,
      load: (_) => ++calls == 1 ? first.future : second.future,
    );
    await tester.tap(find.byKey(const Key('usage-period')));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));
    await tester.tap(find.text('Laatste 7 dagen'));
    await tester.pump();
    second.complete({
      'projects': ['latest'],
      'rows': [row('latest', 'API', '9')],
    });
    await tester.pumpAndSettle();
    first.complete(fixture());
    await tester.pumpAndSettle();
    expect(find.text('latest'), findsOneWidget);
    expect(find.text('product-factory'), findsNothing);
    expect(tester.takeException(), isNull);
  });

  testWidgets('custom calendar dates validate and use an inclusive end date', (
    tester,
  ) async {
    final selection = UsageSelection(now: DateTime(2026, 9, 15));
    final paths = <String>[];
    await showDashboard(
      tester,
      selection: selection,
      load: (path) async {
        paths.add(path);
        return fixture();
      },
    );
    await tester.tap(find.byKey(const Key('usage-period')));
    await tester.pumpAndSettle();
    Future<void> enterDate(String key, String date) async {
      await tester.tap(find.byKey(Key(key)));
      await tester.pumpAndSettle();
      await tester.tap(find.byTooltip('Switch to input'));
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextField), date);
      await tester.tap(find.text('Kiezen'));
      await tester.pumpAndSettle();
    }

    await enterDate('usage-from', '09/10/2026');
    await enterDate('usage-through', '09/09/2026');
    await tester.tap(find.text('Periode toepassen'));
    await tester.pumpAndSettle();
    expect(find.textContaining('Kies een einddatum op of na'), findsOneWidget);
    expect(paths, hasLength(1));
    await enterDate('usage-through', '09/12/2026');
    await tester.tap(find.text('Periode toepassen'));
    await tester.pumpAndSettle();
    expect(Uri.parse(paths.last).queryParameters['from'], '2026-09-10');
    expect(Uri.parse(paths.last).queryParameters['through'], '2026-09-12');
    expect(tester.takeException(), isNull);
  });

  testWidgets('failed refresh preserves snapshot and retry recovers', (
    tester,
  ) async {
    var fails = false;
    final key = GlobalKey<UsageDashboardState>();
    await showDashboard(
      tester,
      key: key,
      load: (_) async {
        if (fails) throw StateError('offline');
        return fixture();
      },
    );
    fails = true;
    await key.currentState!.reload();
    await tester.pump();
    expect(find.textContaining('vorige momentopname'), findsOneWidget);
    expect(find.text('US\$ 35,00'), findsOneWidget);
    fails = false;
    await tester.tap(find.text('Opnieuw proberen'));
    await tester.pumpAndSettle();
    expect(find.textContaining('vorige momentopname'), findsNothing);
  });

  testWidgets('overview and project detail fit 320px with 200 percent text', (
    tester,
  ) async {
    final selection = UsageSelection(now: DateTime(2026, 9, 15));
    await showDashboard(tester, width: 320, scale: 2, selection: selection);
    expect(tester.takeException(), isNull);
    await tester.dragUntilVisible(
      find.byKey(const Key('usage-project-product-factory')),
      find.byType(ListView),
      const Offset(0, -500),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.text('product-factory').last);
    await tester.pump();
    expect(tester.takeException(), isNull);
    await tester.dragUntilVisible(
      find.byKey(const Key('usage-model-openai-test-model-API')),
      find.byType(ListView),
      const Offset(0, -500),
    );
    expect(tester.takeException(), isNull);
  });
}
