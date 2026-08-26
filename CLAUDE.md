# Agent Runtime

Lees [`README.md`](README.md) en [`docs/jobs-en-uitvoering.md`](docs/jobs-en-uitvoering.md) voordat
je de runtime wijzigt. Het OpenAPI-contract, de Kotlin-contracttypen, server, worker, tests en
documentatie vormen één versie en worden samen bijgewerkt.

## Vaste platformgrenzen

- De API heeft één actuele `/v1`-variant. Er is geen parallel compatibiliteitscontract.
- `APPLICATION_WORK` levert een betrouwbaar JSON-resultaat en artifacts op.
- `REPOSITORY_WORK` gebruikt een lokale repositoryalias; alleen de worker commit en publiceert.
- Agent Runtime beheert queue, attempts, leases, heartbeats, fencing, retries, deadlines, events,
  transcripten, resultaten en artifacts.
- Worker-servertransport gebruikt uitgaande HTTPS-long-polling, geen WebSocket en geen directe
  databaseverbinding.
- `MOCKED` draait server-side in `LOCAL` en `ACCEPTANCE`; `ACCEPTANCE` accepteert uitsluitend
  `MOCKED` en heeft geen worker-API, terwijl `PRODUCTION` deze provider weigert.
- Provider en model komen uit de job en worden tegen de serverpolicy van de geauthenticeerde
  consument gevalideerd.
- Projectcredentials staan lokaal in `project-credentials.env`. Alleen namen gaan naar de server;
  per attempt wordt uitsluitend de geselecteerde subset gematerialiseerd.
- Runtime-, worker-, provider- en Git-publicatiecredentials komen niet in de agentcontainer.
- Inputattachments en outputartifacts zijn begrensde echte bestanden. Bestandsbytes worden alleen
  via afzonderlijke beveiligde routes geladen.
- Iedere attempt heeft een harde, niet-verlengbare deadline en een fencing token.
- `APPLICATION_WORK`-uitvoer wordt door de server geparseerd en tegen het volledige JSON-schema
  gevalideerd. De worker kan binnen dezelfde technische attempt maximaal drie gecorrigeerde
  outputpogingen uitvoeren.
- Het gedeelde execution-image bevat alle tools. Serverpolicy, mounts en geselecteerde credentials
  bepalen de feitelijke autoriteit.

## Repository-indeling

- `agent-runtime-contracts`: OpenAPI en gedeelde contracttypen;
- `agent-runtime-server`: control plane, opslag, mocks, management-API en statische monitorassets;
- `agent-runtime-worker`: lokale worker en gecontroleerde publicatie;
- `monitor-ui`: Flutter Web-bron;
- `execution-images`: execution-container;
- `deploy`: OpenShift-, Argo CD- en secretmanifests.

## Verificatie

Gebruik Java 21. Na backend- of contractwijzigingen draait minimaal
`mvn -B --no-transfer-progress verify`. Na monitorwijzigingen draaien `flutter analyze`,
`flutter test` en `flutter build web --release`; synchroniseer daarna `monitor-ui/build/web/` naar
`agent-runtime-server/src/main/resources/static/`.
