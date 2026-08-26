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
`http://localhost:8080`; kies in de beheerweergave de ingeklapte beheertoken-noodroute en gebruik
`local-admin-token`. In productie is Google-login de standaardroute.

## Worker op een nieuwe MacBook installeren

De macOS-worker draait als LaunchAgent onder de ingelogde gebruiker. Hij start bij het inloggen,
wordt na een fout opnieuw gestart en schrijft zijn logs naar `work/logs`. De laptop maakt alleen
uitgaande verbindingen met de Agent Runtime-server en hoeft niet vanaf internet bereikbaar te zijn.

### 1. Benodigdheden installeren

Installeer JDK 21, Maven, Docker Desktop en minimaal één ondersteunde agent-CLI. Volg voor de CLI's
de actuele installatie-instructies van [Codex](https://developers.openai.com/codex/cli) en/of
[Claude Code](https://docs.anthropic.com/en/docs/claude-code/getting-started). De overige onderdelen
kunnen met Homebrew worden geïnstalleerd:

```bash
brew install --cask temurin@21 docker-desktop
brew install maven
```

Start Docker Desktop eenmalig en controleer daarna de installatie:

```bash
/usr/libexec/java_home -v 21
mvn --version
docker info
```

Clone vervolgens deze repository op een vaste plek. De LaunchAgent bewaart absolute paden; verplaats
de checkout daarom niet na installatie.

### 2. Agentproviders aanmelden

Meld Codex en/of Claude lokaal aan voordat de worker wordt geïnstalleerd. Voor Codex opent
`codex login` de browserlogin; de workercontainer heeft een lokaal `~/.codex/auth.json` nodig.
Controleer beide providers als volgt:

```bash
codex login
codex login status

claude auth login
claude auth status
```

Gebruik voor Codex file-based credentialopslag wanneer de CLI anders alleen de macOS-keychain
gebruikt: zet `cli_auth_credentials_store = "file"` in `~/.codex/config.toml` en meld opnieuw aan.
Behandel `~/.codex/auth.json`, `~/.claude/.credentials.json` en `~/.claude.json` als wachtwoorden:
nooit committen, mailen of in een ticket plakken.

### 3. Worker configureren

Een worker-only laptop gebruikt één lokaal configuratiebestand: `properties.env`. Kopieer het
voorbeeld, vul het bestaande productieworkertoken en minimaal één providerpad in en kies een unieke
worker-ID:

```bash
cp properties.worker.env.example properties.env
chmod 600 properties.env
```

Het ingevulde bestand ziet er bijvoorbeeld zo uit:

```dotenv
AR_SERVER_URL=https://agent-runtime.vdzonsoftware.nl
AR_WORKER_ID=voornaam-macbook
AR_WORK_ROOT=work/worker
AR_WORKER_TOKEN=<bestaand-worker-token>
AR_CODEX_CREDENTIALS_DIR=/Users/<account>/.codex
AR_CLAUDE_CREDENTIALS_DIR=/Users/<account>/.claude
```

Neem `AR_WORKER_TOKEN` veilig over van de bestaande Agent Runtime-productieconfiguratie. Draai
hiervoor **niet** `deploy/initialize-secrets.sh`: dat script beheert de OpenShift/serversecrets en
kan een nieuw token genereren dat de bestaande server nog niet kent. Een worker-only laptop heeft
geen `secrets.env` nodig. `properties.env` staat in `.gitignore` en moet vanwege het workertoken mode
`0600` houden. Credentialmappen moeten absolute paden zijn; `~` en `$HOME` worden niet uitgebreid.
Eén provider is voldoende, dus verwijder de regel voor een niet-gebruikte provider.

Een bestaande installatie met de oude tweebestandsconfiguratie migreert eenmalig met:

```bash
./deploy/macos/install-worker-launch-agent.sh migrate
```

Projectcredentials zijn optioneel. Maak pas wanneer een project ze nodig heeft een
`project-credentials.env`, met uitsluitend namen in de vorm `PROJECT__NAAM`, en beveilig ook dit
bestand. De worker publiceert alleen de namen aan de server; waarden blijven lokaal en worden alleen
in de geselecteerde jobcontainer geïnjecteerd.

```dotenv
HKH__ACCEPTANCE_BASE_URL=https://acceptance.example.nl
HKH__ACCEPTANCE_USERNAME=<gebruikersnaam>
HKH__ACCEPTANCE_PASSWORD=<wachtwoord>
```

```bash
chmod 600 project-credentials.env
```

Sla OpenShift-toegang per project als Base64-kubeconfig op. Daardoor kan een geselecteerde job de
kubeconfig binnen zijn container materialiseren; een absoluut hostpad naar `~/.kube` zou daar niet
bruikbaar zijn.

### 4. Worker bouwen en installeren

Bouw alleen de worker-JAR; het execution-image wordt door GitHub gebouwd en hoeft lokaal niet te
worden gebouwd of vooraf gedownload:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn -B --no-transfer-progress clean package
```

Na iedere succesvolle merge naar `main` publiceert GitHub Actions
`ghcr.io/robbertvdzon/agent-runtime-execution:main` voor `linux/amd64` en `linux/arm64`. De worker
gebruikt bij iedere nieuwe job `docker run --pull always`: Docker controleert de tag en downloadt
alleen nieuwere lagen. `AR_EXECUTION_IMAGE` hoeft daarom niet in `properties.env` te staan.

Controleer eerst alles zonder de bestaande macOS-service te wijzigen en installeer hem daarna:

```bash
./deploy/macos/install-worker-launch-agent.sh check
./deploy/macos/install-worker-launch-agent.sh install
```

De installer valideert Java, Docker, de worker-JAR, bestandsrechten en providercredentialbestanden.
Daarna rendert hij een plist met de absolute paden van deze checkout.

### 5. Status en logging bekijken

```bash
launchctl print gui/$(id -u)/nl.vdzon.agent-runtime.worker
tail -F work/logs/worker.log work/logs/worker-error.log
```

De worker hoort daarnaast als `ONLINE` te verschijnen in de pagina **Workers** van de
[productiemonitor](https://agent-runtime.vdzonsoftware.nl). `Capaciteit 0/1` betekent dat hij online
en beschikbaar is; `1/1` betekent dat hij een job uitvoert.

### Beheer, updates en problemen

Na een worker-code-update: voer opnieuw de schone Maven-build uit en draai daarna `install` opnieuw.
Een nieuwe execution-image vereist op de laptop geen handeling; de eerstvolgende job haalt hem op.
De installer vervangt de plist en herstart de service. Handmatig herstarten, stoppen of volledig
verwijderen kan met:

```bash
launchctl kickstart -k gui/$(id -u)/nl.vdzon.agent-runtime.worker
launchctl bootout gui/$(id -u)/nl.vdzon.agent-runtime.worker
./deploy/macos/install-worker-launch-agent.sh uninstall
```

Bij problemen zijn `work/logs/worker-error.log` en `launchctl print` de eerste controles. Controleer
vervolgens of Docker Desktop draait, de Agent Runtime-URL bereikbaar is, de lokale agentlogin nog
geldig is en `properties.env` en `project-credentials.env` (indien aanwezig) mode `0600` hebben.

Bij `AR_CLAUDE_CREDENTIALS_DIR=/Users/<account>/.claude` mount de worker daarnaast uitsluitend het
reguliere, niet-symlinkende siblingbestand `/Users/<account>/.claude.json` read-only. Claude Code
heeft beide eigen credentialbronnen nodig. Meld een verlopen sessie lokaal opnieuw aan voordat de
worker weer Claude-jobs uitvoert.

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
