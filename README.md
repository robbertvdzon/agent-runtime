# Agent Runtime

Agent Runtime is een gedeeld platform voor duurzame AI-agentuitvoering. Geautoriseerde applicaties
dienen jobs in bij de server op OpenShift. Een worker op een MacBook haalt passende jobs via
uitgaande HTTPS-long-polling op en voert Codex of Claude uit in een geïsoleerde Dockercontainer. De
laptop hoeft niet vanaf internet bereikbaar te zijn.

De runtime ondersteunt:

- `APPLICATION_WORK`: een complete prompt uitvoeren en een betrouwbaar JSON-resultaat plus
  artifacts teruggeven;
- `REPOSITORY_WORK`: een bestaande, door de consumer aangemaakte branch van een geregistreerde
  Git-repository aanpassen, repositorygestuurd verifiëren, zo nodig door dezelfde agent laten
  herstellen en alleen groen door de worker committen en normaal pushen;
- `MOCKED`: dezelfde job- en resultaatketen server-side uitvoeren in lokale en
  acceptatieomgevingen.

Acceptatie is bewust volledig van laptopworkers geïsoleerd: de server accepteert daar uitsluitend
provider `MOCKED` en stelt de worker-API niet beschikbaar. Codex en Claude zijn alleen lokaal en in
productie inzetbaar.

De server beheert queue, idempotentie, attempts, leases, heartbeats, fencing, retries, harde
deadlines, events, transcripten, JSON-schemavalidatie, resultaten, attachments en artifacts. De
nieuwe `/v2`-API voegt hervatbare streaminguploads en -downloads, filesystemobjectopslag,
leverancier/model/mode/task-dimensies, usage, tarieven, abonnementstoerekening en SSE-events toe.
Consumenten leveren alleen de technische opdracht en verwerken de terminale uitkomst in hun eigen
domein.

Product Factory, HKH Autopilot, HKH, PvdD en Personal News Feed hebben ieder een geïsoleerde tenant.
Software Factory is de enige gewone consument met `REPOSITORY_WORK`. De HKH-consumenten hebben elk
een eigen bearercredential en mogen alleen projectcredentials onder respectievelijk
`HKH_AUTOPILOT__...` en `HKH__...` selecteren.

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
cd ..
bash monitor-ui/tool/build_and_sync.sh
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

Meld Codex lokaal aan wanneer deze worker Codex aanbiedt:

```bash
codex login
codex login status
```

Maak voor Claude een langlevende OAuth-token vanuit het Claude-abonnement:

```bash
claude auth login
claude auth status
claude setup-token
```

Codex gebruikt in de container `~/.codex/auth.json`. Zet daarom
`cli_auth_credentials_store = "file"` in `~/.codex/config.toml` wanneer de CLI anders alleen de
macOS-keychain gebruikt. Zet de uitvoer van `claude setup-token` als `AR_CLAUDE_OAUTH_TOKEN` in
`properties.env`. Dit is een OAuth-token van het Claude-abonnement en geen Anthropic API-key.
Een map via `AR_CLAUDE_CREDENTIALS_DIR` blijft beschikbaar als fallback op systemen waar de
Claude-login daadwerkelijk in bestanden staat. Alleen `~/.claude` mounten werkt op macOS niet
betrouwbaar, omdat de actuele login normaal in de Keychain staat. Behandel tokens en bestanden als
wachtwoorden en commit ze nooit.

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
AR_CODEX_MODELS=gpt-5.6-sol
AR_CLAUDE_OAUTH_TOKEN=<uitvoer-van-claude-setup-token>
AR_CLAUDE_MODELS=<exacte-claude-model-id>
```

`properties.env` staat in `.gitignore`, is een regulier bestand met mode `0600` en bevat ook alle
eventuele `AR_REPOSITORY_<ALIAS>_URL`-instellingen. Paden zijn absoluut; `~` en `$HOME` worden niet
uitgebreid. De Claude OAuth-token heeft voorrang op `AR_CLAUDE_CREDENTIALS_DIR`. Verwijder de
providerregel voor een provider die deze worker niet aanbiedt.
De v2-worker adverteert uitsluitend modellen uit `AR_CODEX_MODELS` en `AR_CLAUDE_MODELS`; zonder
die expliciete lijst claimt hij voor die provider geen v2-job. De Runtime kiest nooit een fallbackmodel.

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
attempt uitsluitend de expliciet aangevraagde subset. Waarden komen niet in de serverqueue of
catalogus. OpenShift-toegang wordt als Base64-kubeconfig opgeslagen, zodat de job hem binnen de
container kan materialiseren. Een service op de laptop gebruikt vanuit Docker
`host.docker.internal` in plaats van `localhost`, bijvoorbeeld in een database-URL.

De worker behandelt keys met onder meer `PASSWORD`, `TOKEN`, `SECRET`, `KEY`, `KUBECONFIG` of
`CREDENTIALS` als gevoelig. Een database-URL met userinfo of een gevoelige queryparameter is ook
gevoelig. Alleen geselecteerde gevoelige waarden worden in resultaten en artifacts geblokkeerd;
gewone configuratie zoals usernames, schema's, URL's zonder credentials en booleans mag terugkomen.

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
en gebruikt `/v1`. Deze API blijft beschikbaar voor bestaande consumers.

Het nieuwe contract staat in
[`agent-runtime-v2.yaml`](agent-runtime-contracts/src/main/resources/openapi/agent-runtime-v2.yaml)
en gebruikt `/v2`. Iedere AI-job kiest expliciet `vendorId`, `model`, `mode` en `taskType`; er is
geen model-fallback. `STRUCTURED_GENERATION` retourneert altijd een klein JSON-object. Grote tekst,
markdown, audio en andere bestanden lopen vooraf via `POST /v2/uploads`, hervatbare `PATCH`-chunks
en `POST /v2/uploads/{uploadId}/complete`. Een succesvolle `result.json` bevat de metadata en
download-URL's van alle artifacts; de bytes blijven op de objectstore.

Live status, zichtbare agenttekst, toolactiviteit en door een provider geleverde
reasoning-samenvattingen zijn beschikbaar via `GET /v2/jobs/{jobId}/event-stream` (SSE). Verborgen
chain-of-thought is geen onderdeel van het contract. Usage en kosten staan per job in het resultaat
en geaggregeerd onder `/v2/usage/summary` en `/v2/management/usage/summary`.
Consumers lezen exacte beschikbare combinaties via `GET /v2/execution-options` en uitsluitend
toegestane v2-workerkeynamen via `GET /v2/environment-keys`. Software Factory leest de
toegestane repositoryaliases en actuele workerbeschikbaarheid via `GET /v2/repository-aliases`;
deze route geeft nooit repository-URL's of credentials terug. Buiten productie kan een afzonderlijk
`AR_TEST_CONTROL_TOKEN`, zonder consumer-, worker- of adminrechten, gerichte fixtures onder
`/v2/test-control/mocks` beheren. Productie bevat dit secret en deze API niet.

Een v2-repositoryjob bevat `repositoryCheckout.alias`, de reeds bestaande remote `branch` en
`publicationMode`. Software Factory maakt de storybranch en de ene pull request; Agent Runtime
maakt voor v2 geen branch of PR. Iedere attempt gebruikt een verse clone. De agent ziet `.git`
read-only en alleen de worker kan na veiligheidscontroles één commit naar exact dezelfde branch
pushen. Het gevalideerde AI-resultaat blijft in `result` staan; alias, begin-SHA, eventuele
commit-SHA en `NONE`, `NO_CHANGES` of `PUSHED` staan afzonderlijk in `repositoryResult`.

Een muterende repositoryjob kan `verification.mode=REPOSITORY_CONFIG` aanvragen. De worker leest
dan uitsluitend `.factory/verification.yaml` uit de checkout; de consumer kan geen commando's
meesturen. Rode commando's worden binnen dezelfde worktree aan dezelfde agent teruggegeven tot de
aangevraagde herstelgrens. Alleen `PASSED` of volledig niet-relevante (`SKIPPED`) verificatie mag
worden gepubliceerd. Bij blijvend rood blijft het gevalideerde AI-resultaat samen met begrensd,
geredigeerd commandobewijs via het resultaatendpoint beschikbaar, terwijl de job terminal `FAILED`
is. Een lege diff blijft `NO_CHANGES` zonder `verificationResult`.

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
artifacts. De pagina **Gebruik & kosten** toont het v2-verbruik, het aandeel per project en
beschikbare directe, berekende of abonnementskosten. Afbeeldingen worden inline weergegeven en blijven downloadbaar. De monitor gebruikt in
productie Google-login met een server-side e-mailallowlist en heeft een ingeklapte
beheertoken-noodroute.

De monitorbuild gebruikt geen service-worker. De appbundle heeft per inhoud een nieuwe
bestandsnaam; uitsluitend dat content-gehashte bestand wordt immutable gecachet. Alle API-GET's
hebben daarnaast een cache-buster en de server antwoordt daarop met `no-store`. Een kill-switch
ruimt oude Flutter-service-workers en hun caches op. Een open monitor controleert iedere dertig
seconden `version.json` en herlaadt zichzelf wanneer een nieuwere frontend live staat.

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
- [Specificatie uitbreidbare AI-runtime](docs/specificatie-uitbreidbare-ai-runtime.md)
