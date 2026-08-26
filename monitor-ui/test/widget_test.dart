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
}

class _UnavailableGoogleApiClient extends ApiClient {
  @override
  Future<AuthConfig> authConfig() async {
    throw const ApiError('Google-login is niet geconfigureerd');
  }
}
