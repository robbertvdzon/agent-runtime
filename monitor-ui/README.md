# Agent Runtime-monitor

Flutter Web-beheerinterface voor Agent Runtime. De monitor gebruikt de beveiligde
`/v1/management`-API en wordt als statische webbuild in de server-JAR opgenomen.
Lokale releasebuilds en CI gebruiken exact Flutter 3.44.6.

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
cd ..
bash monitor-ui/tool/build_and_sync.sh
```

Commit zowel de Flutter-bron als de gesynchroniseerde serverassets. De repository-CI bouwt en test
de monitor opnieuw en neemt de assets op in de servercontainer.

Het script bouwt zonder Flutter-service-worker, geeft de appbundle een content-gehashte naam,
plaatst een kill-switch voor bezoekers met een oude service-worker en schrijft een deterministische
`version.json`. De monitor vergelijkt die versie iedere dertig seconden met zijn eigen build en
herlaadt na een deploy automatisch. API-GET's krijgen altijd een cache-buster.

De functionele beschrijving staat in
[`../docs/beheerinterface.md`](../docs/beheerinterface.md).
