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
- idempotente consumenten-API, tenantisolatie en profielvalidatie;
- workerregistratie, capabilityselectie, long-polling, attempts, leases, heartbeat, fencing,
  annuleren en begrensde exponentiële retries;
- onveranderlijke events, JSON-resultaten en gehashte artifactopslag;
- centrale `MOCKED`-uitvoering voor integratie en acceptatie;
- lokale Kotlin-worker voor Codex en Claude, plus read-only applicatiecheckout en gecontroleerde
  repositorypublicatie;
- een breed execution-image en een compacte operationele monitor;
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
2. zet `AR_SERVER_URL`, `AR_CODEX_CREDENTIALS_DIR` en/of `AR_CLAUDE_CREDENTIALS_DIR`;
3. bouw het execution-image en start de worker-JAR.

```bash
docker build -t ghcr.io/robbertvdzon/agent-runtime-execution:main execution-images
java -jar agent-runtime-worker/target/agent-runtime-worker-0.1.0-SNAPSHOT.jar
```

## Contract

De bron staat in
[`agent-runtime-contracts/src/main/resources/openapi/agent-runtime-v1.yaml`](agent-runtime-contracts/src/main/resources/openapi/agent-runtime-v1.yaml).
De belangrijkste consumentenroutes zijn `POST /v1/jobs`, `GET /v1/jobs/{id}`,
`GET /v1/jobs/{id}/result`, `GET /v1/jobs/{id}/events` en `POST /v1/jobs/{id}/cancel`.

## Documentatie

- [Stappenplan en volledig ontwerp](docs/agent-runtime-stappenplan.md)
- [Implementatieplan betrouwbare JSON-resultaten](docs/implementatieplan-betrouwbare-json-resultaten.md)
- [Architectuur en veiligheidsgrenzen](docs/architectuur-en-beveiliging.md)
- [Deployment en operatie](docs/deployment-en-operatie.md)
- [Runbook](docs/runbook.md)
