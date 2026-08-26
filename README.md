# Agent Runtime

Gedeeld platform voor betrouwbare AI-agentuitvoering. Geautoriseerde applicaties dienen op
OpenShift duurzame jobs in; een lokale worker haalt echte jobs via uitgaande HTTPS-long-polling op.
De laptop hoeft daardoor nooit vanaf internet bereikbaar te zijn.

Agent Runtime kent twee jobsoorten:

- `APPLICATION_WORK` voor complete, opaque applicatietaken, met optionele publieke read-only
  Gitcontext op een vaste commit-SHA. Product Factory is de eerste consument.
- `REPOSITORY_WORK` voor gecontroleerde code- en documentwijzigingen inclusief validatie, commit,
  push en eventueel een pull request. Software Factory is de eerste consument.

De server is de enige eigenaar van jobqueue, attempts, leases, heartbeats, fencing, technische
retries, resultaten en artifacts. Consumenten blijven eigenaar van hun instructies, modelkeuze,
domeinlogica en verwerking van het resultaat.

Integratie- en acceptatieomgevingen gebruiken een centrale server-side `MOCKED`-route met hetzelfde
job- en resultaatcontract. Echte Codex- en Claude-jobs worden door de worker in geïsoleerde
containers uitgevoerd. Er wordt nergens WebSockettransport gebruikt.

## Wat er nu staat

- Spring Boot/Spring Modulith-control plane met Flyway, PostgreSQL en H2;
- versieerbaar OpenAPI-contract en Kotlin-contracttypen;
- minimale idempotente consumenten-API, tenantisolatie en server-side policyvalidatie;
- workerregistratie, capabilityselectie, long-polling, attempts, leases, heartbeat, fencing,
  annuleren en begrensde exponentiële retries;
- harde attemptdeadlines, onveranderlijke events, schema-gevalideerde JSON-resultaten, maximaal drie
  duurzame outputpogingen, transcripten en gehashte artifactopslag;
- centrale `MOCKED`-uitvoering voor integratie en acceptatie;
- lokale Kotlin-worker voor Codex en Claude, plus read-only applicatiecheckout en gecontroleerde
  repositorypublicatie;
- een breed execution-image en een responsive Flutter Web-monitor met actieve jobs, wachtrij,
  afgeronde jobs, workers en jobdetail;
- OpenShift-overlays voor acceptatie en productie, Sealed Secrets, PostgreSQL en backups;
- CI voor tests, Modulithgrenzen, containers en Kustomize.

## Lokaal starten

Java 21 en Maven 3.9 zijn verplicht.

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn -B --no-transfer-progress verify
java -jar agent-runtime-server/target/agent-runtime-server-0.1.0-SNAPSHOT.jar
```

De lokale server gebruikt H2 en de expliciete `local-...`-tokens uit `application.yml`. Open daarna
`http://localhost:8080`; de beheerweergave vraagt om `local-admin-token`.

Voor de worker:

1. kopieer `secrets.env.example` naar het genegeerde `secrets.env` of draai
   `deploy/initialize-secrets.sh` naast de bestaande Factory-repositories;
2. kopieer `project-credentials.env.example` naar het eveneens genegeerde
   `project-credentials.env`, gebruik uitsluitend `PROJECT__NAME`-keys en zet beide bestanden op
   mode `0600`;
3. zet `AR_SERVER_URL`, `AR_CODEX_CREDENTIALS_DIR` en/of `AR_CLAUDE_CREDENTIALS_DIR`;
4. bouw het execution-image en start de worker-JAR.

```bash
docker build -t ghcr.io/robbertvdzon/agent-runtime-execution:main execution-images
java -jar agent-runtime-worker/target/agent-runtime-worker-0.1.0-SNAPSHOT.jar
```

Bij de gebruikelijke `AR_CLAUDE_CREDENTIALS_DIR=~/.claude` mount de worker daarnaast uitsluitend
het reguliere, niet-symlinkende siblingbestand `~/.claude.json` read-only wanneer dat bestaat.
Claude Code heeft beide eigen credentialbronnen nodig. Een verlopen OAuth-sessie moet lokaal met
`claude` opnieuw worden aangemeld voordat de worker weer Claude-jobs kan uitvoeren.

## Contract

De bron staat in
[`agent-runtime-contracts/src/main/resources/openapi/agent-runtime-v1.yaml`](agent-runtime-contracts/src/main/resources/openapi/agent-runtime-v1.yaml).
De belangrijkste consumentenroutes zijn `POST /v1/jobs`, `GET /v1/jobs/{id}`,
`GET /v1/jobs/{id}/result`, `GET /v1/jobs/{id}/events` en `POST /v1/jobs/{id}/cancel`.

De bestaande API wordt rechtstreeks vereenvoudigd; er komt geen parallel v2-contract. Een aanvraag
gebruikt één complete `prompt`. Rechten, retries en prioriteit volgen uit serverpolicy en
applicatiecorrelatie blijft bij de consumer. Kleine Base64-inputattachments, file-based
outputartifacts, lokaal ontdekte projectcredentials en de harde attempt-time-out worden volgens het
[leidende ontwerp](docs/application-work.md) aan dezelfde API toegevoegd.

Een `APPLICATION_WORK`-worker dient ruwe kandidaattekst in bij de server. Alleen de server
normaliseert, parseert en valideert die tekst. Bij een inhoudelijke afwijzing start dezelfde
technische attempt direct een nieuwe, duurzame outputpoging met concrete veilige correctiefouten.
Na acceptatie uploadt de worker artifacts en finaliseert hij het onveranderlijke resultaat.

## Flutter-monitor bouwen

De Flutter-bron staat in `monitor-ui`. De releasebestanden worden uit dezelfde server-JAR
geleverd. Werk na UI-wijzigingen de ingebedde bestanden als volgt bij:

```bash
cd monitor-ui
flutter test
flutter build web --release
cd ..
rsync -a --delete monitor-ui/build/web/ agent-runtime-server/src/main/resources/static/
```

## Documentatie

- [Stappenplan en volledig ontwerp](docs/agent-runtime-stappenplan.md)
- [Vereenvoudigd APPLICATION_WORK-contract](docs/application-work.md)
- [Beheerinterface en Flutter-frontend](docs/beheerinterface.md)
- [Klikbaar UX-concept](ux/README.md)
- [Implementatieplan betrouwbare JSON-resultaten](docs/implementatieplan-betrouwbare-json-resultaten.md)
- [Architectuur en veiligheidsgrenzen](docs/architectuur-en-beveiliging.md)
- [Deployment en operatie](docs/deployment-en-operatie.md)
- [Runbook](docs/runbook.md)
