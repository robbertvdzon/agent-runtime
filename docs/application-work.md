# Agent Runtime — vereenvoudigd `APPLICATION_WORK`-contract

Status: leidend doelontwerp voor de huidige API. Er komt geen afzonderlijk v2-contract en geen
compatibiliteitslaag voor het oude aanvraagmodel. De API heeft nog geen consumers; daarom wordt de
bestaande `/v1/jobs`-route rechtstreeks aangepast wanneer een onderdeel wordt geïmplementeerd.

## Besluiten

- Een applicatie levert één complete `prompt`; Agent Runtime kent geen afzonderlijke vaste
  instructies en input meer.
- Product- en procescorrelatie, applicatiesleutels en configuratie- of prompttemplateversies blijven
  bij de consumer en worden niet naar Agent Runtime gestuurd.
- Rechten volgen uit de geauthenticeerde consumer en serverconfiguratie. De consumer kiest geen
  profiel, prioriteit, retrylimiet of vrije resources.
- Kleine inputattachments mogen begrensd als Base64 in de aanvraag staan. De Runtime bewaart ze
  duurzaam en de worker materialiseert ze vóór uitvoering als echte bestanden.
- Een agent schrijft outputartifacts als bestanden. De worker verzamelt, valideert en uploadt ze;
  het model maakt geen Base64 van outputbestanden.
- Projectcredentials staan alleen lokaal op workers. Een job bevat uitsluitend de namen van de
  benodigde environmentvariabelen.
- Iedere echte attempt heeft een harde deadline die onafhankelijk door server en worker wordt
  afgedwongen. Leases en hersteltermijnen kunnen die deadline niet verlengen.

## Aanvraagcontract

De huidige API gebruikt direct `POST /v1/jobs`. De reeds geïmplementeerde minimale aanvraag is:

```json
{
  "jobKind": "APPLICATION_WORK",
  "idempotencyKey": "product-session-action-123",
  "provider": "CODEX",
  "model": "gpt-5.6-sol",
  "prompt": "Volledige, zelfstandige opdracht met alle benodigde context.",
  "responseSchema": {
    "type": "object"
  },
  "executionTimeoutSeconds": 3600,
  "repositorySnapshot": {
    "url": "https://github.com/example/project.git",
    "commitSha": "0123456789abcdef0123456789abcdef01234567"
  }
}
```

Verplicht zijn `jobKind`, `idempotencyKey`, `provider`, `model` en `prompt`.
`responseSchema`, `executionTimeoutSeconds` en `repositorySnapshot` zijn optioneel.

Tijdens uitvoering van het stappenplan worden rechtstreeks aan dezelfde request de volgende
optionele velden toegevoegd:

```json
{
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
  ]
}
```

Onbekende requestvelden worden geweigerd. Hierdoor kan een verwijderde of nog niet geïmplementeerde
mogelijkheid niet stilzwijgend worden genegeerd.

`REPOSITORY_WORK` gebruikt dezelfde gemeenschappelijke velden en behoudt zijn afzonderlijke,
gecontroleerde `repositoryRequest`. Dit document verandert de Git-publicatiegrens daarvan niet.

Retries, back-off, quota, planning en prioriteit zijn geen consumentvelden. Agent Runtime bepaalt
ze vanuit serverconfiguratie per geauthenticeerde consumer en jobsoort.

## Verwijderde gegevens en eigendom

De bestaande API en code bevatten niet langer profiel, applicatiejobkey, configuratieversie,
instructieversie, losse instructies/input, vrije resourceaanvragen of consumercontext. Technische
retrylimiet en prioriteit blijven uitsluitend interne serverwaarden.

Agent Runtime bewaart de exacte prompt voor technische uitvoering en audit. De consumer bewaart
zelf waarom de job bestaat, welke domeinsessie erbij hoort, welke applicatiesleutel de prompt heeft
opgeleverd en welke instellingen- en prompttemplateversies zijn gebruikt.

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
Runtime-/providercredentials in `project-credentials.env`.

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
GET /v1/environment-keys?project=HKH
```

Een item bevat minimaal naam, projectprefix, actuele beschikbaarheid, aantal passende online
workers en `lastSeenAt`. Een offline worker maakt een eerder ontdekte naam niet onbekend, maar wel
niet beschikbaar. De API toont een consumer alleen prefixes die volgens serverconfiguratie voor die
identiteit zichtbaar zijn.

Agent Runtime kent geen applicatiespecifieke agentrollen. Iedere consumer bewaart zelf welke
projectvariabelen aan welke domeinrol zijn toegekend. De vertrouwde backend van de consumer
berekent `environmentKeys`; een frontend, prompt of model kan de lijst niet verruimen.

Bij claimen kiest Agent Runtime alleen een worker die alle gevraagde namen heeft geregistreerd. De
worker controleert identiteit, prefixpolicy en aanwezigheid nogmaals. Een ontbrekende naam houdt de
job zichtbaar wachtend wanneer een andere worker hem kan leveren en eindigt anders met
`REQUIRED_ENVIRONMENT_KEY_UNAVAILABLE`.

### Tijdelijke selectie per attempt

De worker mount nooit `project-credentials.env` zelf. Hij materialiseert per attempt alleen de
gevraagde subset in een tijdelijk bestand met rechten `0600`:

```text
/job/secrets/secrets.env
```

De gekozen waarden zijn tijdens die attempt bewust leesbaar voor de agent. Dit is een geaccepteerde
risicoafweging voor deze persoonlijke projecten; promptregels zijn geen harde beveiligingsgrens.
Runtime-, worker-, provider- en Git-publicatiecredentials blijven altijd buiten de agentcontainer.
De geselecteerde kopie wordt na terminale afronding verwijderd en nooit in events, voortgang,
monitor, resultaat of artifacts opgenomen.

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
repositoryworktree blijft op `/work`. `available-tools.md` beschrijft aanwezige tools, paden,
verwachte output en het gebruik van `secrets.env`. Het document verleent geen rechten: image,
mounts, netwerk- en serverpolicy blijven technisch leidend.

De worker voegt aan iedere prompt alleen een vaste technische slotinstructie toe: lees input uit de
vaste paden, neem secretwaarden nooit op in een providerrequest of output, schrijf het
gestructureerde resultaat naar `/job/output/result.json` en overige bewijsbestanden naar
`/job/output/artifacts`.

## Inputattachments

Kleine inputbestanden mogen Base64 in de aanvraag staan. Eerste limieten:

- maximaal 10 bestanden;
- maximaal 2 MB gedecodeerd per bestand;
- maximaal 10 MB gedecodeerd per job;
- een veilige platte bestandsnaam zonder padsegmenten;
- een toegelaten MIME-type en controle van magic bytes waar toepasbaar;
- geen symlinks, archiefuitpak of uitvoerbare bestanden.

De server valideert en bewaart input duurzaam voor queueherstel. De worker verifieert hash en
limieten opnieuw voordat hij de bestanden materialiseert. Attachments komen niet automatisch in de
modelprompt; de prompt verwijst zo nodig naar de bestandsnamen.

## Outputartifacts

De agent schrijft screenshots, traces en andere bewijzen als echte bestanden naar
`/job/output/artifacts`. Na providerafronding:

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
een monotone lokale timer en stopt de container zodra de eerste grens wordt bereikt. De server:

- verlengt de deadline nooit via lease, heartbeat, slaap of recovery;
- fencet de attempt zodra de deadline is verstreken;
- weigert ieder later heartbeat-, progress-, transcript-, artifact- of resultaatbericht;
- registreert `EXECUTION_TIMEOUT`;
- plant alleen volgens zijn vaste retrypolicy een nieuwe attempt.

Een worker stopt eerst beheerst en verwijdert daarna geforceerd de container. Een restart leest de
oorspronkelijke deadline uit het journal; een al verlopen attempt wordt niet hervat. Queuewachttijd
telt niet mee in de attempt-time-out.

## Mocks

`MOCKED` blijft server-side en maakt geen workerattempt, tijdelijke directory of credentialselectie.
Een mockjob doorloopt wel hetzelfde job-, responseschema- en artifactresultaatcontract. Productie
weigert `MOCKED`. Mockselectie gebruikt een aparte beveiligde testcorrelatie buiten het minimale
productiecontract.

## Benodigde implementatiewijzigingen

1. Houd de huidige `/v1`-OpenAPI actueel; voeg geen parallel contract toe.
2. Voeg Base64-inputattachments en `environmentKeys` rechtstreeks aan de huidige request toe zodra
   de bijbehorende uitvoering gereed is.
3. Houd retry-, prioriteits-, tool- en prefixpolicies server-side per consumer.
4. Breid workerregistratie en claimselectie uit met beschikbare environmentkeynamen.
5. Voeg de gefilterde environmentcatalogus-API toe.
6. Splits Runtime- en projectcredentials, voeg veilige parsing en startupcontroles toe en
   materialiseer alleen de jobsubset.
7. Voeg de vaste taakdirectory en `available-tools.md` toe.
8. Materialiseer inputattachments en verzamel outputartifacts automatisch.
9. Maak de attemptdeadline duurzaam en dwing hem onafhankelijk in server en worker af.
10. Voeg contract-, timeout-, recovery-, path traversal-, credentialredactie-, attachment- en
    artifacttests toe.

## Invarianten

- Secretwaarden staan nooit in een jobaanvraag, Runtime-jobtabel of catalogusresponse.
- Alleen environmentkeynamen worden via server en queue getransporteerd.
- De agentcontainer ziet nooit `project-credentials.env`, alleen de expliciet geselecteerde subset.
- Een consumer bepaalt rollen en domeincorrelatie; Agent Runtime kent die betekenis niet.
- Provider, model, prompt, schema en time-out zijn per job bevroren.
- Leases en herstel kunnen een harde attemptdeadline niet verlengen.
- Inputattachments en outputartifacts zijn begrensd, geverifieerd en gescheiden.
- Productie weigert `MOCKED`.
