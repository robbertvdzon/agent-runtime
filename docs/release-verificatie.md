# Releaseverificatie planwijzigingen

Stand: 25 augustus 2026.

## Lokaal geslaagd

- `mvn -B --no-transfer-progress clean verify`: 21 tests groen, inclusief Modulith-/ArchUnitgrenzen.
- Flutter: 2 tests groen en een release-webbuild ingebed in de server-JAR.
- OpenAPI 3.1: Redocly recommended lint zonder fouten of waarschuwingen.
- Server- en multi-arch execution-images gebouwd; Codex, Claude, Node en de arm64 `oc`-client zijn
  in het execution-image gestart.
- Acceptatie- en productie-overlays renderen met `oc kustomize`.
- Servercontainersmoke: health en publieke Flutter-assets geven 200, beheerdata geeft zonder token
  401 en met admintoken 200.
- Echte Codex-smoke geslaagd met strikt schema, geselecteerde environmentkey, Base64-PNG en een
  gecontroleerd PNG-outputartifact.
- Een echte Claude-smoke met strikt schema is geslaagd. De uitgebreidere herhaling met dezelfde
  environmentkey, attachment en artifact stopte daarna op `OAuth session expired and could not be
  refreshed`; dit is een verlopen lokaal provideraccount, geen gefakete test of serveracceptatie.

## Nog vereist voor release

1. Meld de lokale Claude CLI opnieuw aan en herhaal de volledige Claude-smoke.
2. Commit en publiceer de server- en workerwijzigingen en het multi-arch execution-image. Publiceer
   dat execution-image onder zowel de immutable SHA-tag als de bewegende `main`-tag.
3. Controleer dat `agent-runtime-execution:main` naar de zojuist gebouwde SHA-digest wijst. De
   namespaces `agent-runtime-acceptance` en `agent-runtime` bestaan en zijn actief; de huidige
   `oc`-identiteit is `system:admin`.
4. Rol naar acceptatie uit en oefen contractafwijzingen, migratie vanaf Flyway v1, mocks, metrics,
   redactie en herstel vanaf databasebackup.
5. Maak de productiebackup, rol de immutable serverrelease uit en controleer dat laptopworkers bij
   hun eerstvolgende job automatisch de actuele execution-image ophalen.

Een productie-uitrol voordat de nieuwe `main`-tag gepubliceerd is zou niet deze lokale wijziging
bevatten en is daarom bewust niet uitgevoerd.
