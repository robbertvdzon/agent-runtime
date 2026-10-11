import 'dart:async';

import 'package:agent_runtime_monitor/main.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

Map<String, dynamic> _part(int sequence, String text) => {
  'partId': '$sequence',
  'attemptId': 'attempt-1',
  'sequence': sequence,
  'kind': 'AGENT_TEXT',
  'text': text,
};

Map<String, dynamic> _page({
  List<Map<String, dynamic>> items = const [],
  int? cursor,
  bool active = false,
  bool more = false,
}) => {
  'items': items,
  'nextSequence': cursor,
  'active': active,
  'hasMore': more,
};

class _TranscriptApi extends ApiClient {
  final pages = <FutureOr<Map<String, dynamic>> Function()>[];
  final paths = <String>[];
  String status = 'SUCCEEDED';
  String? result;
  int pageCalls = 0;
  int detailCalls = 0;

  @override
  Future<Map<String, dynamic>> get(String path) async {
    paths.add(path);
    if (Uri.parse(path).path.endsWith('/transcript')) {
      final call = pageCalls++;
      return call < pages.length
          ? await pages[call]()
          : _page(active: status == 'RUNNING');
    }
    detailCalls++;
    return {
      'job': {
        'id': 'job-1',
        'technicalName': 'voorbeeldrun',
        'provider': 'CLAUDE',
        'model': 'test-model',
        'status': status,
        'createdAt': '2026-10-11T17:02:00Z',
      },
      'prompt': 'Maak de startknop duidelijker.',
      'attempts': <dynamic>[],
      'inputAttachments': <dynamic>[],
      if (result != null)
        'result': {'result': result, 'artifacts': <dynamic>[]},
    };
  }
}

Future<void> _open(WidgetTester tester, _TranscriptApi api) async {
  await tester.pumpWidget(
    MaterialApp(
      home: JobDetail(api: api, id: 'job-1'),
    ),
  );
  await tester.pumpAndSettle();
}

void main() {
  testWidgets(
    'loads every archived page including pages without log messages',
    (tester) async {
      final api = _TranscriptApi();
      api.pages.addAll([
        () => _page(cursor: 500, more: true),
        () => _page(
          items: [_part(501, 'Eerste bericht')],
          cursor: 1000,
          more: true,
        ),
        () => _page(items: [_part(1001, 'Laatste bericht')], cursor: 1001),
      ]);
      await _open(tester, api);

      expect(api.pageCalls, 3);
      expect(
        api.paths,
        contains(
          '/v2/management/jobs/job-1/transcript?limit=500&afterSequence=500',
        ),
      );
      expect(
        api.paths,
        contains(
          '/v2/management/jobs/job-1/transcript?limit=500&afterSequence=1000',
        ),
      );
      expect(
        find.textContaining('Laatste bericht', findRichText: true),
        findsWidgets,
      );
      expect(find.text('Metadata'), findsNothing);
      expect(find.text('Afgerond'), findsWidgets);
      await tester.pump(const Duration(seconds: 6));
      expect(api.pageCalls, 3);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'empty tail preserves cursor and reconnect deduplicates replayed parts',
    (tester) async {
      final api = _TranscriptApi()..status = 'RUNNING';
      api.pages.addAll([
        () => _page(
          items: [_part(8, 'Eenmalig bericht')],
          cursor: 8,
          active: true,
        ),
        () => _page(active: true),
        () => throw const ApiError('Tijdelijk offline'),
        () => _page(
          items: [_part(8, 'Eenmalig bericht'), _part(9, 'Nieuw bericht')],
          cursor: 9,
          active: true,
        ),
      ]);
      await _open(tester, api);
      await tester.pump(const Duration(seconds: 3));
      await tester.pumpAndSettle();
      await tester.pump(const Duration(seconds: 3));
      await tester.pumpAndSettle();
      expect(find.text('Verbinding onderbroken'), findsOneWidget);
      await tester.pump(const Duration(seconds: 3));
      await tester.pumpAndSettle();

      final paths = api.paths
          .where((path) => path.contains('/transcript'))
          .toList();
      expect(
        paths.skip(1).every((path) => path.endsWith('afterSequence=8')),
        isTrue,
      );
      expect(
        find.textContaining('Nieuw bericht', findRichText: true),
        findsWidgets,
      );
      expect(find.text('Verbinding onderbroken'), findsNothing);
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox.shrink());
    },
  );

  testWidgets(
    'completion refreshes result when job finishes during transcript request',
    (tester) async {
      final api = _TranscriptApi()..status = 'RUNNING';
      api.pages.add(() {
        api.status = 'SUCCEEDED';
        api.result = 'De wijziging is klaar.';
        return _page(items: [_part(1, 'Onderzoek afgerond')], cursor: 1);
      });
      await _open(tester, api);
      expect(api.detailCalls, 2);
      expect(
        find.textContaining('De wijziging is klaar.', findRichText: true),
        findsWidgets,
      );
      expect(find.text('Afgerond'), findsWidgets);
      await tester.pump(const Duration(seconds: 6));
      expect(api.detailCalls, 2);
    },
  );

  testWidgets('slow refresh does not start overlapping requests', (
    tester,
  ) async {
    final pending = Completer<Map<String, dynamic>>();
    final api = _TranscriptApi()..status = 'RUNNING';
    api.pages.add(() => pending.future);
    await tester.pumpWidget(
      MaterialApp(
        home: JobDetail(api: api, id: 'job-1'),
      ),
    );
    await tester.pump();
    await tester.pump(const Duration(seconds: 9));
    expect(api.pageCalls, 1);
    expect(api.detailCalls, 1);
    pending.complete(_page(active: true));
    await tester.pumpAndSettle();
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets(
    'final result without new log parts still follows the live tail',
    (tester) async {
      final api = _TranscriptApi()..status = 'RUNNING';
      final text = List.generate(
        25,
        (i) => 'Voortgang $i. De agent controleert de uitvoering.',
      ).join('\n\n');
      api.pages.addAll([
        () => _page(items: [_part(1, text)], cursor: 1, active: true),
        () {
          api.status = 'SUCCEEDED';
          api.result = 'Het definitieve resultaat.\n\n$text';
          return _page(cursor: 1);
        },
      ]);
      await _open(tester, api);
      final controller = tester
          .widget<ListView>(find.byType(ListView).first)
          .controller!;
      final previousBottom = controller.position.maxScrollExtent;
      await tester.pump(const Duration(seconds: 3));
      await tester.pumpAndSettle();
      expect(controller.position.maxScrollExtent, greaterThan(previousBottom));
      expect(controller.position.extentAfter, lessThan(5));
    },
  );

  testWidgets(
    'reading older text keeps scroll position until new activity is selected',
    (tester) async {
      final api = _TranscriptApi()..status = 'RUNNING';
      final longText = List.generate(
        30,
        (i) =>
            'Paragraaf $i. Ik controleer de startknop en de status van de uitvoering.',
      ).join('\n\n');
      api.pages.addAll([
        () => _page(items: [_part(1, longText)], cursor: 1, active: true),
        () => _page(
          items: [_part(2, 'Er is een nieuw antwoord.')],
          cursor: 2,
          active: true,
        ),
      ]);
      await _open(tester, api);
      final controller = tester
          .widget<ListView>(find.byType(ListView).first)
          .controller!;
      expect(controller.position.extentAfter, lessThan(5));
      await tester.drag(find.byType(ListView).first, const Offset(0, 450));
      await tester.pumpAndSettle();
      final readingOffset = controller.offset;
      expect(controller.position.extentAfter, greaterThan(100));
      await tester.pump(const Duration(seconds: 3));
      await tester.pumpAndSettle();
      expect(controller.offset, closeTo(readingOffset, 1));
      expect(find.text('Nieuwe activiteit'), findsOneWidget);
      await tester.tap(find.text('Nieuwe activiteit'));
      await tester.pumpAndSettle();
      expect(controller.position.extentAfter, lessThan(5));
      expect(find.text('Nieuwe activiteit'), findsNothing);
      await tester.pumpWidget(const SizedBox.shrink());
    },
  );
}
