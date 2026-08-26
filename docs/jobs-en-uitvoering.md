# Jobs en uitvoering

Agent Runtime voert twee soorten asynchrone jobs uit. `APPLICATION_WORK` levert een zelfstandig
AI-resultaat op. `REPOSITORY_WORK` laat een agent een vooraf geregistreerde Git-repository
wijzigen en laat de worker de wijziging committen en publiceren.

## Aanvraag

Een consument maakt een job met `POST /v1/jobs`. Het actuele contract staat in
[`agent-runtime-v1.yaml`](../agent-runtime-contracts/src/main/resources/openapi/agent-runtime-v1.yaml).
De server weigert onbekende velden.

```json
{
  "jobKind": "APPLICATION_WORK",
  "idempotencyKey": "product-session-action-123",
  "provider": "CODEX",
  "model": "gpt-5.6-sol",
  "prompt": "Volledige, zelfstandige opdracht met alle benodigde context.",
  "responseSchema": {
    "type": "object",
    "required": ["answer"],
    "properties": {
      "answer": {"type": "string"}
    }
  },
  "executionTimeoutSeconds": 3600,
  "environmentKeys": [
    "PF__TEST_USERNAME",
    "PF__TEST_PASSWORD"
  ],
  "attachments": [
    {
      "filename": "huidige-pagina.png",
      "mimeType": "image/png",
      "contentBase64": "..."
    }
  ]
}
```

`jobKind`, `idempotencyKey`, `provider`, `model` en `prompt` zijn verplicht. De request kan verder
een JSON-schema, een harde uitvoeringstime-out, environmentkeynamen, maximaal tien attachments en
jobsoortspecifieke repositorygegevens bevatten. Dezelfde idempotency key binnen dezelfde tenant
levert dezelfde job terug. Hergebruik met een andere request geeft een conflict.

De server leidt tenant, toegestane jobsoort, provider-, model- en projectprefixpolicy af uit het
bearertoken. Attempts, retrylimiet en prioriteit zijn serverwaarden en staan niet in de request.

## `APPLICATION_WORK`

`APPLICATION_WORK` gebruikt één complete prompt. Een optionele `repositorySnapshot` geeft de agent
read-only context uit een publieke GitHub-repository op een exacte commit-SHA:

```json
{
  "repositorySnapshot": {
    "url": "https://github.com/example/project.git",
    "commitSha": "0123456789abcdef0123456789abcdef01234567"
  }
}
```

De worker doet een detached checkout en verwijdert daarna de remote. De agent kan deze checkout
niet publiceren.

De provider schrijft kandidaatuitvoer naar de taakdirectory. De worker stuurt de begrensde ruwe
tekst naar de server. De server normaliseert uitsluitend een kaal JSON-document, een JSON-codeblok
of `Here is the JSON:` gevolgd door zo'n codeblok, parseert het resultaat en valideert het volledige
JSON-schema. Bij een afwijzing krijgt dezelfde technische attempt concrete, veilige
validatiefouten. Er zijn maximaal drie duurzame outputpogingen. Een geaccepteerd resultaat wordt
pas definitief nadat alle artifacts zijn geüpload.

## `REPOSITORY_WORK`

`REPOSITORY_WORK` bevat een `repositoryRequest`:

```json
{
  "repositoryRequest": {
    "alias": "agent-runtime",
    "baseBranch": "main",
    "branchHint": "monitor-verbetering",
    "publish": true
  }
}
```

De Software Factory-policy accepteert de geregistreerde aliases `software-factory`,
`agent-runtime` en `test-repository`. De worker vertaalt een alias via een lokale
`AR_REPOSITORY_<ALIAS>_URL`-instelling naar een repository-URL. De agent wijzigt alleen de worktree
onder `/work`; hij krijgt geen Git-publicatiecredential en commit of pusht niet zelf.

Na de agentuitvoering controleert de worker paden, symlinks, geheime bestandsnamen en bestanden
groter dan 20 MB. Vervolgens maakt de worker de commit. Bij `publish: true` pusht hij een branch met
naam `agent-runtime/<job-id>` en probeert hij een pull request naar `baseBranch` te openen. Het
resultaat bevat branch, commit-SHA, diffstatistiek, publicatiestatus en, wanneer aangemaakt, de
pull-request-URL.

## Projectcredentials

Een laptopworker gebruikt twee lokale bestanden:

```text
properties.env
project-credentials.env
```

`properties.env` bevat de workerconfiguratie en interne credentials, waaronder server-URL,
workertoken, worker-ID, de Claude OAuth-token, providercredentialpaden en repositoryaliases. Een
worker-only laptop heeft geen `secrets.env` nodig. `AR_CLAUDE_OAUTH_TOKEN` bevat de uitvoer van
`claude setup-token`, gebruikt het Claude-abonnement en heeft voorrang op filecredentials uit
`AR_CLAUDE_CREDENTIALS_DIR`.

`project-credentials.env` bevat uitsluitend projectgebonden waarden die een job expliciet kan
selecteren:

```dotenv
HKH__ACCEPTANCE_BASE_URL=https://acceptance.example.nl
HKH__ACCEPTANCE_USERNAME=example-user
HKH__ACCEPTANCE_PASSWORD=example-password
```

Namen volgen `PROJECT__NAAM`. Beide bestanden zijn gitignored, reguliere bestanden zonder symlink
en alleen leesbaar door de eigenaar (`0600`). De worker registreert uitsluitend namen bij de
server. Waarden komen niet in de request, queue, catalogus, events of monitor.

`GET /v1/environment-keys?project=HKH` geeft de voor de consument zichtbare namen, actuele
beschikbaarheid, het aantal passende online workers en `lastSeenAt`. Een job wordt alleen geclaimd
door een worker die alle gevraagde namen heeft geregistreerd.

Per attempt maakt de worker een tijdelijke `/job/secrets/secrets.env` met alleen de geselecteerde
waarden. Het volledige `project-credentials.env` wordt niet gemount. Runtime-, worker- en
Git-publicatiecredentials blijven buiten de agentcontainer. Alleen de authenticatie van de gekozen
AI-provider wordt aan die providercontainer gegeven. De worker blokkeert resultaten en artifacts
waarin een voor deze job geselecteerde gevoelige waarde voorkomt. Gevoeligheid volgt de keynaam
(`PASSWORD`, `TOKEN`, `SECRET`, `KEY`, `KUBECONFIG`, `CREDENTIALS`) en ingebedde authenticatie in
database-URL's. Usernames, schema's, gewone URL's en booleans blijven normale configuratie.

De agent leest de geselecteerde waarden zelf. Tooloutput is onderdeel van de providerconversatie;
daarom verbiedt de vaste instructie `cat`, `od`, `printenv`, shelltracing en vergelijkbare manieren
om `secrets.env` te tonen. Deze instructie en de uitvoercontrole beperken het risico, maar vormen
geen harde isolatie tegen een agent die de leesbare waarden bewust naar de provider stuurt.

Gebruik in projectwaarden `host.docker.internal` in plaats van `localhost` wanneer een service op
de workerlaptop vanuit de execution-container bereikbaar moet zijn.

## Taakdirectory

Iedere echte attempt krijgt deze indeling:

```text
/job/
├── input/
│   ├── prompt.md
│   ├── response-schema.json
│   └── attachments/
├── secrets/
│   └── secrets.env
├── docs/
│   └── available-tools.md
└── output/
    ├── result.json
    └── artifacts/
```

`input`, `secrets` en `docs` zijn read-only; `output` is schrijfbaar. Het execution-image bevat
Codex, Claude, Git, Java/Maven, Node, Playwright/Chromium, `oc`/`kubectl` en PostgreSQL-tools.
`available-tools.md` beschrijft de aanwezige commando's en vaste paden.

De worker voegt aan de prompt een technische instructie toe voor de taakdirectory. Deze instructie
verbiedt het kopiëren van waarden uit `/job/secrets/secrets.env` naar providerrequests,
transcripten, resultaten of artifacts. Autorisatie blijft technisch begrensd door serverpolicy,
geselecteerde credentials, mounts en de publicatiegrens van de worker.

## Attachments en artifacts

Inputattachments worden als Base64 in de jobaanvraag aangeleverd en als bytes in de Runtime
bewaard. Toegestaan zijn PNG, JPEG, WebP, PDF, tekst en JSON. Bestandsnamen zijn plat en veilig.
De standaardlimieten zijn 2 MB per bestand, 10 MB per job en maximaal tien bestanden. Voor formats
met herkenbare magic bytes controleert de server dat inhoud en MIME-type overeenkomen.

De worker materialiseert inputattachments onder `/job/input/attachments` en controleert opnieuw
naam, hash, type en grootte. De prompt verwijst naar een bestand wanneer de agent het moet lezen.

De agent schrijft outputartifacts rechtstreeks onder `/job/output/artifacts`. Alleen directe,
reguliere bestanden worden verzameld. Er gelden maximaal 25 bestanden, 5 MB per bestand en 25 MB
per job. De worker bepaalt MIME-type en SHA-256 en uploadt de bytes via de gefencete worker-API. De
server koppelt de onveranderlijke metadata aan het jobresultaat. Consumenten en beheerders halen een
artifact via een afzonderlijke beveiligde downloadroute op.

## Attempts, leases en time-outs

Een geschikte worker claimt een job via HTTPS-long-polling. De server maakt een attempt-ID en een
willekeurig fencing token; alleen de hash van het token staat in de database. Iedere mutatie van
een draaiende job vereist het actuele attempt-ID en token.

De standaardlease duurt 120 seconden en wordt met heartbeats vernieuwd. Bij leaseverlies wordt de
attempt `SUSPECTED`; dezelfde worker kan hem binnen het standaardherstelvenster van 30 minuten
terugnemen. Daarna plant de server een retry met begrensde exponentiële back-off. De standaardlimiet
is drie technische attempts.

Bij claimen berekent de server een harde `attemptDeadline` uit `executionTimeoutSeconds`. De worker
en server dwingen deze grens onafhankelijk af. Heartbeats, slaapstand en herstel verlengen hem
niet. Na het verstrijken worden verdere voortgang, transcriptdelen, artifacts en resultaten
gefencet. De server registreert `EXECUTION_TIMEOUT` en gebruikt dezelfde technische retrypolicy.

## Status, resultaat en historie

Een job doorloopt `QUEUED`, `WAITING_FOR_WORKER`, `RUNNING` en een terminale status `SUCCEEDED`,
`FAILED` of `CANCELLED`. De consumenten-API levert actuele status, append-only events en na succes
een onveranderlijk JSON-resultaat met artifactmetadata.

De worker publiceert zichtbare prompt-, correctie- en provideruitvoer als append-only
transcriptdelen. De worker en server redigeren bekende bearer- en key/valuepatronen. Verborgen
modelredenering wordt niet opgevraagd of gereconstrueerd.

## Centrale mocks

In `LOCAL` en `ACCEPTANCE` kan provider `MOCKED` een vooraf geregistreerde server-side respons
gebruiken. De mock doorloopt dezelfde jobopslag, JSON-normalisatie, schemavalidatie,
outputpogingen en resultaatroutes zonder worker, lease of container. Acceptatie accepteert geen
`CODEX`- of `CLAUDE`-jobs en de worker-API retourneert daar not-found. `PRODUCTION` weigert
`MOCKED`.
