# Agent Runtime-monitor

Flutter Web-beheerinterface voor Agent Runtime. De monitor gebruikt de beveiligde
`/v1/management`-API en wordt als statische webbuild in de server-JAR opgenomen.

## Ontwikkelen

```bash
flutter pub get
flutter analyze
flutter test
flutter run -d chrome
```

Een lokaal gestarte Runtime-server is bereikbaar op `http://localhost:8080`. De noodlogin gebruikt
het lokale `local-admin-token`.

## Releasebuild

```bash
flutter build web --release
cd ..
rsync -a --delete monitor-ui/build/web/ agent-runtime-server/src/main/resources/static/
```

Commit zowel de Flutter-bron als de gesynchroniseerde serverassets. De repository-CI bouwt en test
de monitor opnieuw en neemt de assets op in de servercontainer.

De functionele beschrijving staat in
[`../docs/beheerinterface.md`](../docs/beheerinterface.md).
