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
