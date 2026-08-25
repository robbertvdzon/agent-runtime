# Agent Runtime — vereenvoudigd `APPLICATION_WORK`-contract

Status: doelontwerp voor contract v2; de huidige v1-code implementeert dit nog niet.

Dit document legt de na de eerste platformrelease gekozen vereenvoudiging vast. Het is leidend voor
de migratie van `APPLICATION_WORK` en voor de aansluiting van Product Factory. Het bestaande
OpenAPI-contract onder `/v1` blijft tijdens de migratie achterwaarts compatibel. De vereenvoudigde
vorm wordt als `/v2` toegevoegd en vervangt v1 pas nadat alle consumenten zijn omgezet.

## Besluiten

- Een applicatie levert één complete `prompt`; Agent Runtime kent geen afzonderlijke
  `instructions` en `input` meer.
- Product- en procescorrelatie, jobkeys en configuratie- of prompttemplateversies blijven bij de
  consument en worden niet naar Agent Runtime gestuurd.
- Rechten volgen uit de geauthenticeerde consument en serverconfiguratie; de consument kiest geen
  `jobProfile` of vrije resources.
- Kleine inputattachments mogen begrensd als Base64 in de aanvraag staan. De server materialiseert
  ze vóór uitvoering als echte bestanden.
- Een agent schrijft outputartifacts als bestanden. De worker verzamelt, valideert en uploadt ze;
  het model maakt geen Base64 van outputbestanden.
- Projectcredentials staan alleen lokaal op workers. Een job bevat uitsluitend de namen van de
  benodigde environmentvariabelen.
- Iedere echte attempt heeft een harde deadline die onafhankelijk door server en worker wordt
  afgedwongen. Leases en hersteltermijnen kunnen die deadline niet verlengen.

## Vereenvoudigde aanvraag

```json
{
  "jobKind": "APPLICATION_WORK",
  "idempotencyKey": "product-session-action-123",
  "provider": "CODEX",
  "model": "gpt-5.6",
  "prompt": "Volledige, zelfstandige opdracht met alle benodigde context.",
  "responseSchema": {
    "type": "object"
  },
  "executionTimeoutSeconds": 3600,
  "environmentKeys": [
    "HKH__ACCEPTANCE_USERNAME",
    "HKH__ACCEPTANCE_PASSWORD"
  ],
  "attachments": [
    {
      "filename": "huidige-pagina.png",
      "mimeType": "image/png",
      "contentBase64": "..."
    }
  ],
  "repositorySnapshot": {
    "url": "https://github.com/example/project.git",
    "commitSha": "0123456789abcdef0123456789abcdef01234567"
  }
}
```

Verplicht zijn `jobKind`, `idempotencyKey`, `provider`, `model`, `prompt` en
`executionTimeoutSeconds`. `responseSchema`, `environmentKeys`, `attachments` en
`repositorySnapshot` zijn optioneel. `REPOSITORY_WORK` behoudt daarnaast zijn afzonderlijke,
gecontroleerde repositoryaanvraag; dit document verandert de Git-publicatiegrens daarvan niet.

`maxAttempts` en `priority` zijn geen vrije consumentvelden meer. Agent Runtime bepaalt retries,
back-off, quota en planning vanuit serverconfiguratie per geauthenticeerde consument en jobsoort.

## Verwijderde velden en eigendom

| V1-veld | V2-besluit |
|---|---|
| `jobProfile` | verdwijnt uit de aanvraag; rechten volgen uit consumentidentiteit en serverpolicy |
| `jobKey` | blijft bij de consument voor modelkeuze, domeincorrelatie en mocks |
| `configurationVersion` | blijft als auditgegeven bij de consument |
| `instructionVersion` | blijft als prompttemplateversie bij de consument |
| `instructions` en `input` | worden één complete `prompt` |
| `resourceRequests` | verdwijnen; tools zijn vast per jobsoort en credentials worden via `environmentKeys` geselecteerd |
| `consumerContext` | blijft samen met het Runtime-job-ID bij de consument |
| `priority` | serverpolicy, niet vrij door de consument te bepalen |
| `maxAttempts` | serverpolicy, niet vrij door de consument te bepalen |

Agent Runtime bewaart de exacte prompt voor technische uitvoering en audit. De consument bewaart
zelf waarom de job bestaat, welke product- of processessie erbij hoort, welke jobkey de prompt
heeft opgeleverd en welke instellingen- en prompttemplateversies zijn gebruikt.

## Runtime- en projectcredentials

De lokale worker gebruikt twee strikt gescheiden bestanden:

```text
secrets.env
project-credentials.env
```

`secrets.env` bevat alleen credentials waarmee de Runtime en worker zelf functioneren, zoals het
workertoken, de server-URL en paden naar Codex- of Claude-credentials. Deze waarden zijn nooit
selecteerbaar door een job en worden nooit in een agentcontainer gemount.

`project-credentials.env` bevat projectgebonden configuratie en credentials die een agentjob mag
ontvangen:

```dotenv
HKH__ACCEPTANCE_BASE_URL=https://acceptance.example.nl
HKH__ACCEPTANCE_USERNAME=example-user
HKH__ACCEPTANCE_PASSWORD=example-password
PF__TEST_USERNAME=example-user
PF__TEST_PASSWORD=example-password
```

De dubbele underscore scheidt een stabiele projectprefix van de naam binnen het project. De worker
accepteert uitsluitend namen volgens `[A-Z][A-Z0-9_]*__[A-Z][A-Z0-9_]*`.

Beide bestanden zijn gitignored en dockerignored, geen symlink, alleen leesbaar door de eigenaar en
nooit onderdeel van een image. De worker weigert startup bij onveilige rechten, dubbele keys of
`AR__`-/providercredentials in `project-credentials.env`.

### Ontdekking en catalogus

De worker leest de waarden lokaal en registreert alleen de beschikbare namen:

```json
{
  "workerId": "local-worker-1",
  "availableEnvironmentKeys": [
    "HKH__ACCEPTANCE_BASE_URL",
    "HKH__ACCEPTANCE_USERNAME",
    "HKH__ACCEPTANCE_PASSWORD"
  ]
}
```

Agent Runtime bewaart per worker welke namen voor het laatst zijn gezien, nooit hun waarden. Een
beveiligde consumenten-API levert de gefilterde catalogus en actuele beschikbaarheid:

```text
GET /v2/environment-keys?project=HKH
```

Een item bevat minimaal naam, projectprefix, actuele beschikbaarheid, aantal passende online
workers en `lastSeenAt`. Een offline worker maakt een eerder ontdekte naam niet onbekend, maar wel
niet beschikbaar. De API toont een consument alleen prefixes die volgens serverconfiguratie voor
die identiteit zichtbaar zijn.

Agent Runtime kent geen Product Factory- of Software Factory-agentrollen. Iedere consument bewaart
zelf welke projectvariabelen aan welke domeinrol zijn toegekend. De vertrouwde backend van de
consument berekent `environmentKeys`; een frontend, prompt of model kan de lijst niet verruimen.

Bij claimen kiest Agent Runtime alleen een worker die alle gevraagde namen heeft geregistreerd. De
worker controleert de identiteit/prefixpolicy en aanwezigheid nogmaals. Een ontbrekende naam houdt
de job zichtbaar wachtend wanneer een andere worker hem kan leveren en eindigt anders met een
veilige configuratiefout `REQUIRED_ENVIRONMENT_KEY_UNAVAILABLE`.

### Tijdelijke selectie per attempt

De worker mount nooit `project-credentials.env` zelf. Hij materialiseert per attempt alleen de
gevraagde subset in een tijdelijk bestand met rechten `0600`:

```text
/job/secrets/secrets.env
```

De gekozen waarden zijn tijdens die attempt bewust leesbaar voor de agent. Dit is een geaccepteerde
risicoafweging voor deze persoonlijke projecten; promptregels zijn geen harde beveiligingsgrens.
Runtime-, worker-, provider- en Git-publicatiecredentials blijven ook onder deze afweging altijd
buiten de agentcontainer. De geselecteerde kopie wordt na terminale afronding verwijderd en nooit
in events, voortgang, monitor, resultaat of artifacts opgenomen.

## Taakdirectory en tools

Iedere echte attempt krijgt deze vaste indeling:

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

`input`, `secrets` en `docs` worden read-only gemount; `output` is schrijfbaar. De eventuele
repositoryworktree blijft op `/work`. `available-tools.md` beschrijft de aanwezige tools, paden,
verwachte output en het gebruik van `secrets.env`. Het document verleent geen rechten: image,
mounts, netwerk- en serverpolicy blijven technisch leidend.

De worker voegt aan iedere prompt alleen een vaste technische slotinstructie toe: lees input uit de
vaste paden, neem geen secretwaarden op in output, schrijf het gestructureerde resultaat naar
`/runtime/output/result.json` en schrijf overige bewijsbestanden naar
`/runtime/output/artifacts`.

## Inputattachments

Kleine inputbestanden mogen Base64 in de aanvraag staan. Eerste limieten:

- maximaal 10 bestanden;
- maximaal 2 MB gedecodeerd per bestand;
- maximaal 10 MB gedecodeerd per job;
- een veilige platte bestandsnaam, zonder padsegmenten;
- een toegelaten MIME-type en controle van magic bytes waar toepasbaar;
- geen symlinks, archiefuitpak of uitvoerbare bestanden.

De server valideert en bewaart de input duurzaam voor queueherstel. De worker verifieert hash en
limieten opnieuw voordat hij de bestanden materialiseert. Inputattachments komen niet automatisch
in het modelprompt; de prompt verwijst zo nodig naar de bestandsnamen.

## Outputartifacts

De agent schrijft screenshots, traces en andere bewijzen als echte bestanden naar
`/runtime/output/artifacts`. Na providerafronding:

1. leest en valideert de worker `result.json`;
2. scant hij alleen de directe artifactdirectory;
3. weigert hij symlinks, onveilige namen, apparaten en overschrijdingen;
4. controleert hij MIME-type, grootte en SHA-256;
5. uploadt hij ieder geaccepteerd bestand via de bestaande gefencete worker-API;
6. koppelt de server artifactmetadata aan het onveranderlijke jobresultaat.

Eerste limieten blijven 5 MB per artifact, 25 MB per job en maximaal 25 bestanden. Het model hoeft
outputbestanden nooit naar Base64 om te zetten.

## Harde uitvoeringstime-out

Bij claimen berekent de server voor iedere echte attempt:

```text
attemptDeadline = claimedAt + executionTimeoutSeconds
```

De deadline staat in het claimantwoord en is server-authoritatief. De worker bewaakt daarnaast zelf
een monotone lokale timer en stopt de container zodra de eerste van beide grenzen wordt bereikt.
De server:

- verlengt de deadline nooit via lease, heartbeat, slaap of recovery;
- fencet de attempt zodra de deadline is verstreken;
- weigert ieder later heartbeat-, progress-, artifact- of resultaatbericht;
- registreert `EXECUTION_TIMEOUT`;
- plant alleen volgens zijn vaste retrypolicy een nieuwe attempt.

Een worker stopt eerst beheerst en verwijdert daarna geforceerd de container. Een restart leest de
oorspronkelijke deadline uit het journal; een al verlopen attempt wordt niet hervat. Queuewachttijd
telt niet mee in de attempt-time-out. Een optionele algemene jobdeadline kan later afzonderlijk
worden toegevoegd.

## Mocks

`MOCKED` blijft server-side en maakt geen workerattempt, tijdelijke directory of credentialselectie.
Een mockjob doorloopt wel hetzelfde job-, responseschema- en artifactresultaatcontract. Productie
weigert `MOCKED`. Mockselectie gebruikt een aparte beveiligde testcorrelatie buiten het minimale
productiecontract; een consument hoeft daarvoor geen `jobKey` aan iedere Runtime-job mee te geven.

## Benodigde implementatiewijzigingen

1. Publiceer een volledig beschreven `/v2`-OpenAPI-contract en houd `/v1` beschikbaar tijdens de
   migratie.
2. Voeg `prompt`, Base64-inputattachments en `environmentKeys` toe; verwijder de genoemde v1-velden
   uit v2.
3. Verplaats retry-, prioriteits-, tool- en prefixpolicies naar serverconfiguratie per consument.
4. Breid workerregistratie en claimselectie uit met beschikbare environmentkeynamen.
5. Voeg de gefilterde environmentcatalogus-API toe.
6. Splits `secrets.env` en `project-credentials.env`, voeg veilige parsing en startupcontroles toe
   en materialiseer alleen de jobsubset. Voeg `project-credentials.env` toe aan `.gitignore` en
   `.dockerignore` en lever alleen een waardevrij `project-credentials.env.example`.
7. Voeg de vaste taakdirectory en `available-tools.md` toe.
8. Materialiseer inputattachments en verzamel outputartifacts automatisch.
9. Maak de attemptdeadline duurzaam en dwing hem onafhankelijk in server en worker af.
10. Voeg contract-, migratie-, timeout-, recovery-, path traversal-, credentialredactie-,
    attachment- en artifacttests toe.
11. Migreer Product Factory naar v2 en verwijder v1 pas nadat geen consument het oude contract meer
    gebruikt.

## Invarianten

- Secretwaarden staan nooit in een jobaanvraag, Runtime-jobtabel of catalogusresponse.
- Alleen environmentkeynamen worden via server en queue getransporteerd.
- De agentcontainer ziet nooit `project-credentials.env`, alleen de expliciet geselecteerde subset.
- Een consument bepaalt rollen en domeincorrelatie; Agent Runtime kent die betekenis niet.
- Provider/model, prompt, schema en time-out zijn per job bevroren.
- Leases en herstel kunnen een harde attemptdeadline niet verlengen.
- Inputattachments en outputartifacts zijn begrensd, geverifieerd en gescheiden.
- Productie weigert `MOCKED`.
