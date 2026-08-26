# Architectuur en beveiliging

## Platformgrens

Agent Runtime verwerkt technische AI-uitvoering. Applicatiespecifieke rollen, sessies, stories en
andere domeinentiteiten staan niet in het Runtime-contract. Een `APPLICATION_WORK`-job bevat één
complete prompt; een `REPOSITORY_WORK`-job voegt een geregistreerde repositoryalias en
publicatie-instructie toe.

```mermaid
flowchart LR
  PF[Product Factory] -->|APPLICATION_WORK| API[Agent Runtime server]
  SF[Software Factory] -->|REPOSITORY_WORK| API
  API --> DB[(PostgreSQL)]
  API --> MOCK[Centrale mockexecutor]
  WORKER[MacBook-worker] -->|uitgaande HTTPS long-poll| API
  WORKER --> CONTAINER[Execution-container]
  CONTAINER --> CODEX[Codex of Claude]
```

De server is de enige eigenaar van queue, status, attempts, leases, retries, events, transcripten,
resultaten, attachments en artifacts. De worker heeft geen toegang tot consumentendatabases. De
server bewaart geen lokale providercredentials of projectcredentialwaarden.

## Modules

- `agent-runtime-contracts` bevat de externe gegevensvormen en OpenAPI-specificatie.
- `agent-runtime-server` is de composition root. De packages `jobs`, `workers`, `mock` en `monitor`
  zijn acyclische Spring Modulithmodules; ArchUnit verifieert deze grenzen.
- `agent-runtime-worker` is een zelfstandig proces dat alleen via HTTPS met de server praat.
- `monitor-ui` wordt als statische Flutter Web-build in de server-JAR geleverd.

## Identiteiten en autorisatie

Productie gebruikt vier onafhankelijke bearercredentials:

| Credential | Autoriteit |
| --- | --- |
| `AR_PRODUCT_FACTORY_TOKEN` | Eigen `APPLICATION_WORK` maken, lezen, annuleren en artifacts downloaden |
| `AR_SOFTWARE_FACTORY_TOKEN` | Eigen `REPOSITORY_WORK` maken, lezen, annuleren en artifacts downloaden |
| `AR_WORKER_TOKEN` | Worker registreren, jobs claimen en actuele gefencete attempts bedienen |
| `AR_ADMIN_TOKEN` | Managementmetadata lezen en terminale jobs opnieuw aanbieden |

Tenant en rechten volgen uit het token. De server valideert jobsoort, provider, model,
repositoryalias en environmentkeyprefix tegen de vaste tenantpolicy voordat een job uitvoerbaar
wordt. Een consument ziet jobs van een andere tenant niet; de API retourneert daarvoor dezelfde
not-found-respons als voor een onbekende job.

De webmonitor gebruikt in productie Google Identity Services. De server controleert issuer,
audience, e-mailverificatie en de `AR_ADMIN_EMAILS`-allowlist en geeft daarna een tijdelijk, met
`AR_SESSION_SIGNING_SECRET` ondertekend beheersessietoken uit. Het Google ID-token wordt niet
opgeslagen. Het statische beheertoken is alleen de noodroute.

## Serversecrets

`deploy/initialize-secrets.sh` maakt de genegeerde lokale bron `secrets.env` met mode `0600`.
`deploy/seal-secrets.sh` zet deze waarden om naar namespacegebonden Sealed Secrets voor acceptatie
en productie. Alleen de versleutelde manifests staan in Git.

Een productieproces start niet met lege, korte of `local-...`-tokens. Secretwaarden worden niet in
prompts, payloads, foutmeldingen, Dockerlabels of logs geplaatst. Logs en voortgang worden op
bearer- en herkenbare key/valuepatronen geredigeerd.

## Worker- en projectcredentials

De worker leest interne configuratie uit het gitignored `properties.env` met mode `0600`.
Workertoken, Claude OAuth-token, providercredentialpaden en repository-URL's uit dit bestand zijn
niet selecteerbaar door een job. De Claude-token is een abonnementgebonden OAuth-token en geen
Anthropic API-key.

Projectgebonden waarden staan apart in `project-credentials.env`. De worker accepteert alleen
reguliere bestanden zonder symlink, veilige rechten, unieke namen en namen volgens
`PROJECT__NAAM`. Alleen de namen worden bij de server geregistreerd. De servercatalogus bevat geen
waarden.

Per attempt maakt de worker een tijdelijk bestand met uitsluitend de door de job aangevraagde en
door de tenantpolicy toegestane subset. Dit bestand wordt read-only onder
`/job/secrets/secrets.env` gemount en na de attempt verwijderd. De agent kan deze geselecteerde
waarden tijdens de uitvoering lezen. Keys met `PASSWORD`, `TOKEN`, `SECRET`, `KEY`, `KUBECONFIG`
of `CREDENTIALS` en database-URL's met ingebedde authenticatie gelden als gevoelig. De worker
blokkeert alleen de voor deze job geselecteerde gevoelige waarden in provideruitvoer en artifacts;
gewone configuratie zoals usernames, schema's en booleans veroorzaakt geen blokkade.

Runtime-, worker- en Git-publicatiecredentials blijven buiten de agentcontainer.
Providerauthenticatie wordt uitsluitend aan de gekozen providercontainer gegeven: Claude bij
voorkeur als `CLAUDE_CODE_OAUTH_TOKEN`, Codex via zijn geïsoleerde credentialmount en
Claude-filecredentials alleen als fallback. De outputcontrole vindt plaats nadat de provider heeft
gewerkt. Omdat de agent het geselecteerde `secrets.env` kan lezen, kan deze opzet niet technisch
garanderen dat een agent nooit een waarde in provider-tooloutput laat verschijnen. De jobinstructie
verbiedt daarom het tonen, dumpen en tracen van het bestand; een harde preventie vereist een aparte
credentialbroker in plaats van een leesbare secretmount.

## Execution-container

Het gedeelde multi-arch image bevat Codex, Claude, Git, Java/Maven, Node, Playwright/Chromium,
`oc`/`kubectl` en PostgreSQL-tools. De worker gebruikt `--pull always` voor de bewegende `main`-tag.

De container krijgt vaste read-only input-, documentatie- en secretmounts, een schrijfbare
outputdirectory en een aparte `/work`-worktree. Alleen technische job- en attemptidentifiers staan
in containerlabels. Fencing tokens en andere credentials staan niet in labels.

Bij `APPLICATION_WORK` verwijdert de worker de Gitremote na een detached read-only checkout. Bij
`REPOSITORY_WORK` krijgt de agent geen Git-publicatiecredential. De worker controleert de worktree,
maakt de commit, pusht de vaste jobbranch en opent het pull request.

## Betrouwbaarheid

- Idempotentie is uniek per tenant en requestinhoud.
- Iedere echte attempt heeft een willekeurig fencing token; alleen de SHA-256-hash staat in de
  database.
- Heartbeats verlengen de lease tot maximaal de harde attemptdeadline.
- Leaseverlies zet de attempt in `SUSPECTED`; binnen het herstelvenster kan dezelfde worker met
  hetzelfde token hervatten.
- Na een verlopen herstelvenster gebruikt de server begrensde exponentiële retryback-off.
- Resultaten, artifacts, transcripten en voortgang worden alleen voor de actuele gefencete attempt
  geaccepteerd.
- De attemptdeadline wordt door server en worker onafhankelijk afgedwongen en nooit verlengd.
- Events en transcriptdelen zijn append-only. Een geslaagd resultaat en zijn artifacts zijn
  onveranderlijk.
- `APPLICATION_WORK`-uitvoer wordt door de server geparseerd en volledig tegen het aangevraagde
  JSON-schema gevalideerd.
- Productie weigert `MOCKED`; acceptatie gebruikt dezelfde opslag- en validatieketen zonder worker.

## Bestandsgrenzen

Inputattachments hebben veilige platte namen, toegestane MIME-types, hash- en magic-bytecontroles
en standaardlimieten van 2 MB per bestand, 10 MB per job en tien bestanden. Outputartifacts zijn
directe reguliere bestanden met maximaal 5 MB per bestand, 25 MB per job en 25 bestanden. Symlinks,
apparaten, padtraversal en bekende projectcredentialwaarden worden geweigerd.

De managementlijsten bevatten alleen prompt-/outputpreviews, aantallen en bestandsmetadata.
Attachment- en artifactbytes worden pas via een afzonderlijke geauthenticeerde route geladen.

## Database en herstel

Productie gebruikt PostgreSQL op een persistente volumeclaim. Flyway beheert het schema.
De nachtelijke backupjob maakt een custom-format dump, valideert hem met `pg_restore --list`,
schrijft een SHA-256-bestand en bewaart veertien dagen. Restore-oefeningen gebruiken een lege,
afzonderlijke database.
