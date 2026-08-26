# Agent Runtime

Agent Runtime is een gedeeld platform voor duurzame AI-agentuitvoering. Geautoriseerde applicaties
dienen jobs in bij de server op OpenShift. Een worker op een MacBook haalt passende jobs via
uitgaande HTTPS-long-polling op en voert Codex of Claude uit in een geïsoleerde Dockercontainer. De
laptop hoeft niet vanaf internet bereikbaar te zijn.

De runtime ondersteunt:

- `APPLICATION_WORK`: een complete prompt uitvoeren en een betrouwbaar JSON-resultaat plus
  artifacts teruggeven;
- `REPOSITORY_WORK`: een geregistreerde Git-repository aanpassen, controleren, committen, pushen
  en een pull request openen;
- `MOCKED`: dezelfde job- en resultaatketen server-side uitvoeren in lokale en
  acceptatieomgevingen.

De server beheert queue, idempotentie, attempts, leases, heartbeats, fencing, retries, harde
deadlines, events, transcripten, JSON-schemavalidatie, resultaten, attachments en artifacts.
Consumenten leveren alleen de technische opdracht en verwerken de terminale uitkomst in hun eigen
domein.

## Onderdelen

- `agent-runtime-contracts`: Kotlin-contracttypen en OpenAPI 3.1;
- `agent-runtime-server`: Spring Boot/Spring Modulith-control plane met Flyway, PostgreSQL/H2,
  management-API en ingebedde Flutter Web-monitor;
- `agent-runtime-worker`: Kotlin-worker voor Codex, Claude, Dockeruitvoering, projectcredentials en
  gecontroleerde Git-publicatie;
- `execution-images`: multi-arch execution-image met agent-CLI's, browser, buildtools,
  OpenShift/Kubernetes-CLI's en databaseclients;
- `monitor-ui`: responsive Flutter Web-beheerinterface;
- `deploy`: Kustomize-overlays, Argo CD Applications, Sealed Secrets, PostgreSQL en backups.

## Lokaal bouwen en starten

Java 21, Maven 3.9 en Flutter zijn vereist voor de volledige build.

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
cd monitor-ui
flutter test
flutter build web --release
cd ..
rsync -a --delete monitor-ui/build/web/ agent-runtime-server/src/main/resources/static/
mvn -B --no-transfer-progress clean verify
java -jar agent-runtime-server/target/agent-runtime-server-0.1.0-SNAPSHOT.jar
```

De lokale server gebruikt H2 en de `local-...`-tokens uit `application.yml`. Open
`http://localhost:8080`, kies de ingeklapte beheertoken-noodroute en gebruik
`local-admin-token`. Productie gebruikt Google-login.

## Worker op een nieuwe MacBook

De macOS-worker draait als LaunchAgent onder de ingelogde gebruiker. Hij start bij het inloggen,
wordt na een fout opnieuw gestart en schrijft naar `work/logs`.

### Benodigdheden

Installeer JDK 21, Maven, Docker Desktop en minimaal één ondersteunde agent-CLI:

```bash
brew install --cask temurin@21 docker-desktop
brew install maven
```

Start Docker Desktop en controleer de installatie:

```bash
/usr/libexec/java_home -v 21
mvn --version
docker info
```

Clone deze repository op een vaste plek. De LaunchAgent bewaart absolute paden naar de checkout.

### Providercredentials

Meld Codex en/of Claude lokaal aan:

```bash
codex login
codex login status

claude auth login
claude auth status
```

Codex gebruikt in de container `~/.codex/auth.json`. Zet daarom
`cli_auth_credentials_store = "file"` in `~/.codex/config.toml` wanneer de CLI anders alleen de
macOS-keychain gebruikt. Claude gebruikt `~/.claude` en het siblingbestand `~/.claude.json`.
Behandel deze bestanden als wachtwoorden en commit ze nooit.

### `properties.env`

Een worker-only laptop gebruikt één lokaal configuratiebestand voor de worker:

```bash
cp properties.worker.env.example properties.env
chmod 600 properties.env
```

```dotenv
AR_SERVER_URL=https://agent-runtime.vdzonsoftware.nl
AR_WORKER_ID=voornaam-macbook
AR_WORK_ROOT=work/worker
AR_WORKER_TOKEN=<productieworkertoken>
AR_CODEX_CREDENTIALS_DIR=/Users/<account>/.codex
AR_CLAUDE_CREDENTIALS_DIR=/Users/<account>/.claude
```

`properties.env` staat in `.gitignore`, is een regulier bestand met mode `0600` en bevat ook alle
eventuele `AR_REPOSITORY_<ALIAS>_URL`-instellingen. Paden zijn absoluut; `~` en `$HOME` worden niet
uitgebreid. Verwijder de providerregel voor een provider die deze worker niet aanbiedt.

`AR_EXECUTION_IMAGE` hoeft niet te worden ingesteld. De standaard is
`ghcr.io/robbertvdzon/agent-runtime-execution:main`; de worker gebruikt bij iedere job
`docker run --pull always`.

Een worker-only laptop gebruikt geen `secrets.env`. Het bestand `secrets.env` in een
deploymentcheckout is uitsluitend de lokale bron voor OpenShift-serversecrets.

### `project-credentials.env`

Maak in de repositoryroot een `project-credentials.env` wanneer jobs projectgebonden environment
variables gebruiken:

```dotenv
HKH__ACCEPTANCE_BASE_URL=https://acceptance.example.nl
HKH__ACCEPTANCE_USERNAME=<gebruikersnaam>
HKH__ACCEPTANCE_PASSWORD=<wachtwoord>
```

```bash
chmod 600 project-credentials.env
```

Namen volgen `PROJECT__NAAM`. De worker publiceert alleen de namen aan de server en injecteert per
attempt uitsluitend de expliciet aangevraagde subset. Waarden blijven lokaal. OpenShift-toegang
wordt als Base64-kubeconfig opgeslagen, zodat de job hem binnen de container kan materialiseren.

### Bouwen en installeren

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn -B --no-transfer-progress clean package
./deploy/macos/install-worker-launch-agent.sh check
./deploy/macos/install-worker-launch-agent.sh install
```

De installer valideert Java, Docker, de worker-JAR, providercredentials en bestandsrechten. Een
bestaande installatie met de oude tweebestandsconfiguratie wordt eenmalig omgezet met:

```bash
./deploy/macos/install-worker-launch-agent.sh migrate
```

### Status, logs en beheer

```bash
launchctl print gui/$(id -u)/nl.vdzon.agent-runtime.worker
tail -F work/logs/worker.log work/logs/worker-error.log
```

De worker staat ook op de pagina **Workers** van de
[productiemonitor](https://agent-runtime.vdzonsoftware.nl). `Capaciteit 0/1` betekent beschikbaar;
`1/1` betekent dat de worker een job uitvoert.

Na een worker-code-update bouw je opnieuw en voer je `install` opnieuw uit. Een nieuwe
execution-image wordt automatisch voor de eerstvolgende job gecontroleerd en opgehaald.

```bash
launchctl kickstart -k gui/$(id -u)/nl.vdzon.agent-runtime.worker
launchctl bootout gui/$(id -u)/nl.vdzon.agent-runtime.worker
./deploy/macos/install-worker-launch-agent.sh uninstall
```

## API

Het externe contract staat in
[`agent-runtime-v1.yaml`](agent-runtime-contracts/src/main/resources/openapi/agent-runtime-v1.yaml)
en gebruikt `/v1`.

Belangrijkste consumentenroutes:

- `POST /v1/jobs`;
- `GET /v1/jobs` en `GET /v1/jobs/{jobId}`;
- `GET /v1/jobs/{jobId}/events`;
- `GET /v1/jobs/{jobId}/result`;
- `GET /v1/jobs/{jobId}/artifacts/{artifactId}`;
- `POST /v1/jobs/{jobId}/cancel`;
- `GET /v1/environment-keys?project=PROJECT`.

Een aanvraag bevat één complete `prompt`. Tenant, toegestane jobsoort, provider-, model- en
projectprefixpolicy volgen uit het authenticatietoken. Inputattachments staan begrensd als Base64
in de aanvraag. Outputartifacts zijn echte bestanden en worden via afzonderlijke beveiligde routes
geladen.

Zie [Jobs en uitvoering](docs/jobs-en-uitvoering.md) voor het volledige gedrag van beide
jobsoorten, credentials, taakdirectory, outputvalidatie en retries.

## Beheerinterface

De Flutter-monitor wordt uit dezelfde server-JAR geleverd. Hij toont actieve jobs, wachtrij,
afgeronde jobs, workers en jobdetails met prompt, outputpogingen, transcript, inputattachments en
artifacts. Afbeeldingen worden inline weergegeven en blijven downloadbaar. De monitor gebruikt in
productie Google-login met een server-side e-mailallowlist en heeft een ingeklapte
beheertoken-noodroute.

## CI en productie

Een push naar `main` start repositoryverificatie voor Flutter, Maven, Docker en beide
Kustomize-overlays. Na een groene verificatie bouwt GitHub Actions de server- en execution-images
onder `main` en een immutable `sha-...`-tag. De workflow commit daarna de serverimage-pin naar
`main`. Argo CD synchroniseert acceptatie en productie automatisch met prune en self-heal.

## Documentatie

- [Jobs en uitvoering](docs/jobs-en-uitvoering.md)
- [Architectuur en beveiliging](docs/architectuur-en-beveiliging.md)
- [Beheerinterface](docs/beheerinterface.md)
- [Deployment en operatie](docs/deployment-en-operatie.md)
- [Runbook](docs/runbook.md)
