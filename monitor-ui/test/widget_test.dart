import 'dart:convert';
import 'dart:typed_data';

import 'package:agent_runtime_monitor/main.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  testWidgets('status has a textual accessible label at mobile width', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(320, 640);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    await tester.pumpWidget(
      const MaterialApp(home: Scaffold(body: StatusLabel('RUNNING'))),
    );
    expect(find.text('RUNNING'), findsOneWidget);
    expect(find.bySemanticsLabel('Status RUNNING'), findsOneWidget);
  });

  testWidgets(
    'empty job state remains readable at 320 pixels and 200 percent text',
    (tester) async {
      tester.view.physicalSize = const Size(320, 640);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      await tester.pumpWidget(
        MaterialApp(
          home: MediaQuery(
            data: const MediaQueryData(
              size: Size(320, 640),
              textScaler: TextScaler.linear(2),
            ),
            child: Scaffold(
              body: JobList(
                items: const [],
                emptyText: 'De wachtrij is leeg',
                api: ApiClient(),
              ),
            ),
          ),
        ),
      );
      expect(find.text('De wachtrij is leeg'), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('beheer-token blijft een verborgen noodlogin', (tester) async {
    final api = _UnavailableGoogleApiClient()..clearToken();
    await tester.pumpWidget(
      MaterialApp(
        home: Builder(
          builder: (context) => TextButton(
            onPressed: () => showDialog<bool>(
              context: context,
              builder: (_) => LoginDialog(api: api, allowCancel: false),
            ),
            child: const Text('Open login'),
          ),
        ),
      ),
    );

    await tester.tap(find.text('Open login'));
    await tester.pumpAndSettle();
    expect(
      find.text(
        'Gebruik je Google-account om de uitvoeringsmonitor te openen.',
      ),
      findsOneWidget,
    );
    expect(find.byType(TextField), findsNothing);

    await tester.tap(find.text('Inloggen met een beheertoken'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField), 'nood-token');
    await tester.tap(find.text('Inloggen met beheertoken'));
    await tester.pumpAndSettle();

    expect(api.token, 'nood-token');
  });

  testWidgets('joblijst toont prompt output attachments en artifacts', (
    tester,
  ) async {
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: JobList(
            api: ApiClient(),
            emptyText: 'leeg',
            items: const [
              {
                'id': 'job-1',
                'technicalName': 'product-factory-application-work-job-1',
                'application': 'product-factory',
                'jobKind': 'APPLICATION_WORK',
                'provider': 'CODEX',
                'model': 'gpt-test',
                'status': 'SUCCEEDED',
                'promptPreview':
                    'Maak een duidelijke screenshot van de pagina.',
                'outputPreview': '{"screenshot":"pagina.png"}',
                'inputAttachmentCount': 1,
                'artifactCount': 2,
                'createdAt': '2026-09-10T05:30:15Z',
                'completedAt': '2026-09-10T05:32:20Z',
                'durationMillis': 125000,
                'costAvailable': false,
                'repositoryAlias': 'product-factory',
                'repositoryBranch': 'software-factory/SF-123',
                'repositoryPublicationMode': 'COMMIT_AND_PUSH',
                'repositoryPublicationStatus': 'PUSHED',
                'verificationStatus': 'PASSED',
                'verificationAgentRounds': 2,
              },
            ],
          ),
        ),
      ),
    );

    expect(find.text('Prompt · eerste 240 tekens'), findsOneWidget);
    expect(find.text('Output · eerste 240 tekens'), findsOneWidget);
    expect(find.text('1 attachment'), findsOneWidget);
    expect(find.text('2 artifacts'), findsOneWidget);
    expect(find.textContaining('Aangemaakt:'), findsOneWidget);
    expect(find.textContaining('Afgerond:'), findsOneWidget);
    expect(find.text('Looptijd: 2m 5s'), findsOneWidget);
    expect(find.text('Kosten: Niet beschikbaar'), findsOneWidget);
    expect(find.text('Repository: product-factory'), findsOneWidget);
    expect(find.text('Branch: software-factory/SF-123'), findsOneWidget);
    expect(find.text('Publicatie: PUSHED'), findsOneWidget);
    expect(find.text('Verificatie: PASSED'), findsOneWidget);
    expect(find.text('Agentrondes: 2'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('verificatiedetail toont commando bewijs', (tester) async {
    await tester.pumpWidget(
      const MaterialApp(
        home: Scaffold(
          body: SingleChildScrollView(
            child: VerificationPanel(
              result: {
                'status': 'FAILED',
                'configVersion': 1,
                'agentRounds': 3,
                'commands': [
                  {
                    'id': 'backend-verify',
                    'argv': ['mvn', '-B', 'verify'],
                    'status': 'FAILED',
                    'exitCode': 1,
                    'durationMillis': 1250,
                    'outputTail': 'Tests run: 10, Failures: 1',
                  },
                ],
              },
            ),
          ),
        ),
      ),
    );

    expect(find.text('Repositoryverificatie'), findsOneWidget);
    expect(find.text('Agentrondes: 3'), findsOneWidget);
    expect(find.text('backend-verify · FAILED'), findsOneWidget);
    expect(find.text('mvn -B verify'), findsOneWidget);
    expect(find.textContaining('Exitcode: 1'), findsOneWidget);
    expect(find.text('Tests run: 10, Failures: 1'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('jobregel blijft bruikbaar op 320 pixels met grote tekst', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(320, 760);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    await tester.pumpWidget(
      MaterialApp(
        home: MediaQuery(
          data: const MediaQueryData(
            size: Size(320, 760),
            textScaler: TextScaler.linear(2),
          ),
          child: Scaffold(
            body: JobList(
              api: ApiClient(),
              emptyText: 'leeg',
              items: const [
                {
                  'id': 'job-1',
                  'technicalName': 'product-factory-application-work-job-1',
                  'application': 'product-factory',
                  'jobKind': 'APPLICATION_WORK',
                  'provider': 'CODEX',
                  'model': 'gpt-test',
                  'status': 'SUCCEEDED',
                  'promptPreview': 'Maak een screenshot.',
                  'outputPreview': '{"ok":"ja"}',
                  'inputAttachmentCount': 1,
                  'artifactCount': 1,
                  'createdAt': '2026-09-10T05:30:15Z',
                  'completedAt': '2026-09-10T05:30:16Z',
                  'durationMillis': 1000,
                  'costAvailable': false,
                },
              ],
            ),
          ),
        ),
      ),
    );

    expect(find.text('1 attachment'), findsOneWidget);
    expect(find.text('1 artifact'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('gebruiksoverzicht toont statistieken en modellen per consumer', (
    tester,
  ) async {
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: UsageList(
            rows: const [],
            consumers: const [
              {
                'consumer': 'pvdd',
                'totalJobs': 42,
                'jobsLast24Hours': 2,
                'jobsLast7Days': 12,
                'jobsLast30Days': 30,
                'costsInPeriod': [
                  {'currency': 'EUR', 'amount': '4.25'},
                ],
                'legacyJobsInPeriod': 0,
                'models': [
                  {
                    'vendorId': 'openai',
                    'model': 'gpt-5.6-sol',
                    'mode': 'API',
                    'jobCount': 30,
                  },
                ],
              },
            ],
          ),
        ),
      ),
    );

    expect(find.text('pvdd'), findsOneWidget);
    expect(find.text('EUR 4.25'), findsOneWidget);
    expect(find.textContaining('openai / gpt-5.6-sol / API'), findsOneWidget);
    expect(find.textContaining('v1-job'), findsNothing);
    expect(tester.takeException(), isNull);
  });

  testWidgets('afbeeldingsattachment krijgt een inline voorbeeld', (
    tester,
  ) async {
    final api = _ImageApiClient();
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: SingleChildScrollView(
            child: FileCollection(
              title: 'Meegegeven attachments',
              emptyText: 'Geen attachments',
              api: api,
              pathFor: (_) => '/attachment/image',
              items: const [
                {
                  'id': 'image',
                  'filename': 'invoer.png',
                  'mimeType': 'image/png',
                  'sizeBytes': 68,
                  'sha256': 'abc',
                },
              ],
            ),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('Meegegeven attachments'), findsOneWidget);
    expect(find.text('invoer.png'), findsOneWidget);
    expect(find.bySemanticsLabel('Voorbeeld van invoer.png'), findsOneWidget);
    expect(api.requestedPaths, ['/attachment/image']);
  });
}

class _UnavailableGoogleApiClient extends ApiClient {
  @override
  Future<AuthConfig> authConfig() async {
    throw const ApiError('Google-login is niet geconfigureerd');
  }
}

class _ImageApiClient extends ApiClient {
  final requestedPaths = <String>[];

  @override
  Future<Uint8List> getBytes(String path) async {
    requestedPaths.add(path);
    return base64Decode(
      'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=',
    );
  }
}
