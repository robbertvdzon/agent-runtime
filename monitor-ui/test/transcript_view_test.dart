import 'dart:convert';

import 'package:agent_runtime_monitor/transcript_model.dart';
import 'package:agent_runtime_monitor/transcript_view.dart';
import 'package:flutter/material.dart';
import 'package:flutter_markdown_plus/flutter_markdown_plus.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  testWidgets(
    'lange opdracht klapt expliciet uit en behoudt staat bij updates en tabwissel',
    (tester) async {
      final prompt =
          '${List.generate(14, (index) => 'Instructieregel ${index + 1}.').join('\n')}\nLAATSTE INSTRUCTIE';
      await tester.pumpWidget(
        _page(_transcript(), prompt: prompt, agentLabel: 'Claude'),
      );
      expect(find.text('Claude'), findsOneWidget);
      expect(find.text('Volledige opdracht tonen'), findsOneWidget);
      expect(
        find.textContaining('LAATSTE INSTRUCTIE', findRichText: true),
        findsNothing,
      );
      await tester.ensureVisible(find.text('Volledige opdracht tonen'));
      await tester.tap(find.text('Volledige opdracht tonen'));
      await tester.pumpAndSettle();
      expect(
        find.textContaining('LAATSTE INSTRUCTIE', findRichText: true),
        findsOneWidget,
      );
      expect(find.text('Opdracht inklappen'), findsOneWidget);
      await tester.pumpWidget(
        _page(
          _transcript(toolOutput: 'Nieuwe uitvoer'),
          prompt: prompt,
          agentLabel: 'Claude',
        ),
      );
      await tester.pumpAndSettle();
      expect(
        find.textContaining('LAATSTE INSTRUCTIE', findRichText: true),
        findsOneWidget,
      );
      await tester.ensureVisible(find.text('Technische details'));
      await tester.tap(find.text('Technische details'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Gesprek'));
      await tester.pumpAndSettle();
      expect(
        find.textContaining('LAATSTE INSTRUCTIE', findRichText: true),
        findsOneWidget,
      );
      await tester.ensureVisible(find.text('Opdracht inklappen'));
      await tester.tap(find.text('Opdracht inklappen'));
      await tester.pumpAndSettle();
      expect(
        find.textContaining('LAATSTE INSTRUCTIE', findRichText: true),
        findsNothing,
      );
      await tester.pumpWidget(
        _page(_transcript(), prompt: '${'Context ' * 100}LAATSTE INSTRUCTIE'),
      );
      await tester.pumpAndSettle();
      expect(find.text('Volledige opdracht tonen'), findsOneWidget);
      expect(
        find.textContaining('LAATSTE INSTRUCTIE', findRichText: true),
        findsNothing,
      );
    },
  );

  testWidgets(
    'afgebroken providerbericht toont onvolledig ontvangen in gesloten run',
    (tester) async {
      await tester.pumpWidget(
        _page(
          normalizeTranscript([
            {
              'partId': 'partial',
              'attemptId': 'attempt-1',
              'kind': 'AGENT_TEXT',
              'text': '{"type":"assistant",',
              'streamId': 'provider-event:round-1:1',
              'final': false,
            },
          ]),
        ),
      );
      expect(
        find.text(
          'Het transcript is onvolledig ontvangen. Bekijk de oorspronkelijke uitvoer bij Technische details.',
        ),
        findsOneWidget,
      );
      await tester.tap(find.text('Technische details'));
      await tester.pumpAndSettle();
      expect(find.text('Event 1 · Onvolledig ontvangen'), findsOneWidget);
      expect(find.textContaining('wacht op resterende delen'), findsNothing);
    },
  );

  testWidgets('gesprek toont Markdown en verbergt ruwe protocolgegevens', (
    tester,
  ) async {
    await tester.pumpWidget(_page(_transcript()));

    expect(find.text('Opdracht'), findsOneWidget);
    expect(find.text('Agent'), findsOneWidget);
    expect(find.byType(MarkdownBody), findsWidgets);
    expect(
      find.text('De wijziging is klaar.', findRichText: true),
      findsOneWidget,
    );
    expect(find.text('AGENT_TEXT'), findsNothing);
    expect(find.textContaining('raw-protocol-marker'), findsNothing);
    expect(find.byType(SelectionArea), findsOneWidget);
    expect(find.text('Bash'), findsNothing);
    expect(find.textContaining('flutter test'), findsNothing);

    await tester.tap(find.text('Technische details'));
    await tester.pumpAndSettle();
    expect(find.text('Taakmetadata'), findsOneWidget);
    expect(find.textContaining('Oorspronkelijke events'), findsOneWidget);
    expect(
      find.text('De wijziging is klaar.', findRichText: true),
      findsNothing,
    );
    await tester.tap(find.textContaining('Event 1 ·'));
    await tester.pumpAndSettle();
    expect(find.textContaining('raw-protocol-marker'), findsOneWidget);
  });

  testWidgets(
    'onvolledige ontvangst blijft zichtbaar na eerdere leesbare tekst',
    (tester) async {
      await tester.pumpWidget(
        _page(
          normalizeTranscript([
            {
              'partId': 'text',
              'attemptId': 'attempt-1',
              'kind': 'AGENT_TEXT',
              'text': 'Ik lees de bron.',
              'final': true,
            },
            {
              'partId': 'partial',
              'attemptId': 'attempt-1',
              'kind': 'AGENT_TEXT',
              'text': '{"type":"assistant",',
              'streamId': 'provider-event:round-1:1',
              'final': false,
            },
          ]),
        ),
      );
      expect(find.text('Ik lees de bron.', findRichText: true), findsOneWidget);
      expect(
        find.text(
          'Een deel van het transcript is onvolledig ontvangen. Bekijk de oorspronkelijke uitvoer bij Technische details.',
        ),
        findsOneWidget,
      );
    },
  );

  testWidgets('tool blijft uitgeklapt bij nieuwe uitvoer en bij tabwissel', (
    tester,
  ) async {
    await tester.pumpWidget(
      _page(_transcript(toolOutput: null), isRunning: true),
    );
    await tester.tap(find.text('Opdracht uitvoeren'));
    await tester.pumpAndSettle();
    expect(find.textContaining('flutter test'), findsOneWidget);
    expect(find.text('Wachten op uitvoer…'), findsOneWidget);

    await tester.pumpWidget(_page(_transcript(toolOutput: '12 tests passed.')));
    await tester.pumpAndSettle();
    expect(find.textContaining('flutter test'), findsOneWidget);
    expect(find.text('12 tests passed.'), findsOneWidget);
    expect(find.text('Wachten op uitvoer…'), findsNothing);

    await tester.tap(find.text('Technische details'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Gesprek'));
    await tester.pumpAndSettle();
    expect(find.text('12 tests passed.'), findsOneWidget);
  });

  testWidgets('mislukte tool toont fout zonder openklappen', (tester) async {
    await tester.pumpWidget(
      _page(
        _transcript(
          toolOutput: 'Test failed: knop blijft actief.',
          failed: true,
        ),
      ),
    );
    expect(find.text('Mislukt'), findsOneWidget);
    expect(find.text('Test failed: knop blijft actief.'), findsOneWidget);
    expect(find.textContaining('flutter test'), findsNothing);
    expect(find.text('Uitvoer'), findsNothing);
  });

  testWidgets('afgesloten run toont tool zonder eindstatus als niet afgerond', (
    tester,
  ) async {
    await tester.pumpWidget(_page(_transcript(toolOutput: null)));
    expect(find.text('Niet afgerond'), findsOneWidget);
    expect(find.text('Bezig'), findsNothing);
    await tester.tap(find.text('Opdracht uitvoeren'));
    await tester.pumpAndSettle();
    expect(find.text('Geen eindstatus ontvangen.'), findsOneWidget);
  });

  testWidgets('oude poging blijft niet bezig tijdens een nieuwe poging', (
    tester,
  ) async {
    await tester.pumpWidget(
      _page(
        _transcript(toolOutput: null),
        isRunning: true,
        activeAttemptIds: {'attempt-2'},
      ),
    );
    expect(find.text('Niet afgerond'), findsOneWidget);
    expect(find.text('Bezig'), findsNothing);
    await tester.tap(find.text('Opdracht uitvoeren'));
    await tester.pumpAndSettle();
    expect(find.text('Geen eindstatus ontvangen.'), findsOneWidget);
  });

  testWidgets('resultaatvelden blijven leesbaar met volledige inhoud', (
    tester,
  ) async {
    await tester.pumpWidget(
      _page(
        _transcript(),
        result: jsonEncode({
          'summary': 'De startknop is aangepast.',
          'tests': ['Dubbele klik geblokkeerd', 'Herstelt na fout'],
          'details': {'exitCode': 0, 'published': false},
        }),
      ),
    );
    expect(find.text('Resultaat'), findsOneWidget);
    expect(find.text('summary'), findsOneWidget);
    expect(
      find.text('De startknop is aangepast.', findRichText: true),
      findsOneWidget,
    );
    expect(
      find.text('Dubbele klik geblokkeerd', findRichText: true),
      findsOneWidget,
    );
    expect(find.text('Herstelt na fout', findRichText: true), findsOneWidget);
    expect(find.text('exitCode'), findsOneWidget);
    expect(find.text('0', findRichText: true), findsOneWidget);
    expect(find.text('false', findRichText: true), findsOneWidget);
  });

  testWidgets(
    'gelijk JSON-eindresultaat verschijnt eenmaal ongeacht opmaak en sleutelvolgorde',
    (tester) async {
      final pretty = const JsonEncoder.withIndent('  ').convert({
        'summary': 'De startknop is aangepast.',
        'details': {
          'passed': true,
          'tests': [
            {'name': 'Dubbele klik', 'exitCode': 0},
            'Tweede test',
          ],
        },
      });
      final compact = jsonEncode({
        'details': {
          'tests': [
            {'exitCode': 0, 'name': 'Dubbele klik'},
            'Tweede test',
          ],
          'passed': true,
        },
        'summary': 'De startknop is aangepast.',
      });
      final transcript = normalizeTranscript([
        {
          'partId': 'result',
          'attemptId': 'attempt-1',
          'kind': 'AGENT_TEXT',
          'final': true,
          'text': jsonEncode({
            'type': 'assistant',
            'message': {
              'id': 'message-result',
              'content': [
                {'type': 'text', 'text': pretty},
              ],
            },
          }),
        },
      ]);
      await tester.pumpWidget(_page(transcript, result: compact));
      expect(find.text('Resultaat'), findsNothing);
      expect(
        find.text('De startknop is aangepast.', findRichText: true),
        findsOneWidget,
      );
      expect(find.text('Dubbele klik', findRichText: true), findsOneWidget);

      // Changed nested values remain a separate, visible result.
      await tester.pumpWidget(
        _page(transcript, result: compact.replaceFirst('true', 'false')),
      );
      expect(find.text('Resultaat'), findsOneWidget);
    },
  );

  testWidgets('lege liveweergave toont voortgang en technische tabmelding', (
    tester,
  ) async {
    final views = <bool>[];
    await tester.pumpWidget(
      _page(
        normalizeTranscript([]),
        isRunning: true,
        onTechnicalDetailsChanged: views.add,
      ),
    );
    expect(
      find.text('De agent is bezig. Nieuwe berichten verschijnen hier.'),
      findsOneWidget,
    );
    await tester.tap(find.text('Technische details'));
    await tester.pumpAndSettle();
    expect(
      find.text('Er zijn nog geen transcript-events ontvangen.'),
      findsOneWidget,
    );
    await tester.tap(find.text('Gesprek'));
    await tester.pumpAndSettle();
    expect(views, [true, false]);
  });

  testWidgets('gesprek en open details passen bij 320px en 200 procent tekst', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(320, 900);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(
      _page(
        _transcript(
          toolOutput:
              'Test failed: een fout met een erg lange uitvoer.\n${'lange-uitvoer-' * 25}',
          failed: true,
        ),
        textScale: 2,
        result: jsonEncode({
          'langeNaamVanEenResultaatveld': [
            'Eerste resultaat',
            {'branch': 'feature/${'long-name-' * 15}'},
          ],
        }),
      ),
    );
    expect(tester.takeException(), isNull);
    await tester.ensureVisible(find.text('Opdracht uitvoeren'));
    await tester.tap(find.text('Opdracht uitvoeren'));
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
    await tester.ensureVisible(find.text('Technische details'));
    await tester.tap(find.text('Technische details'));
    await tester.pumpAndSettle();
    await tester.tap(find.textContaining('Event 1 ·'));
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
  });
}

Widget _page(
  TranscriptDocument transcript, {
  String prompt = 'Maak de startknop leesbaar.',
  String agentLabel = 'Agent',
  String? result,
  bool isRunning = false,
  Set<String>? activeAttemptIds,
  double textScale = 1,
  ValueChanged<bool>? onTechnicalDetailsChanged,
}) => MaterialApp(
  home: MediaQuery(
    data: MediaQueryData(textScaler: TextScaler.linear(textScale)),
    child: Scaffold(
      body: SingleChildScrollView(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: TranscriptView(
            key: const ValueKey('transcript'),
            transcript: transcript,
            prompt: prompt,
            agentLabel: agentLabel,
            result: result,
            isRunning: isRunning,
            activeAttemptIds: activeAttemptIds,
            technicalDetails: const [Text('Taakmetadata')],
            onTechnicalDetailsChanged: onTechnicalDetailsChanged,
          ),
        ),
      ),
    ),
  ),
);

TranscriptDocument _transcript({
  String? toolOutput = '12 tests passed.',
  bool failed = false,
}) => normalizeTranscript([
  _part('init', {
    'type': 'system',
    'subtype': 'init',
    'marker': 'raw-protocol-marker',
  }),
  _part('assistant', {
    'type': 'assistant',
    'message': {
      'id': 'message-1',
      'content': [
        {
          'type': 'text',
          'text':
              '**De wijziging is klaar.**\n\n- Leesbaar\n- Getest\n\n```dart\nfinal ready = true;\n```',
        },
        {
          'type': 'tool_use',
          'id': 'tool-1',
          'name': 'Bash',
          'input': {'command': 'flutter test'},
        },
      ],
    },
  }),
  if (toolOutput != null)
    _part('tool-output', {
      'type': 'user',
      'message': {
        'content': [
          {
            'type': 'tool_result',
            'tool_use_id': 'tool-1',
            'content': toolOutput,
            'is_error': failed,
          },
        ],
      },
    }),
]);

Map<String, dynamic> _part(String id, Map<String, dynamic> event) => {
  'partId': id,
  'attemptId': 'attempt-1',
  'kind': 'AGENT_TEXT',
  'text': jsonEncode(event),
  'final': true,
};
