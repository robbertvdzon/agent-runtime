# Stappenplan Agent Runtime

## Status

Dit is het uitgevoerde vervolgplan voor Agent Runtime na de eerste platformrelease. De fasen 0 tot
en met 8 en de bouw-, test-, documentatie- en observabilityonderdelen van fase 9 zijn op 25 augustus
2026 geïmplementeerd en lokaal geverifieerd. De resterende releasehandelingen zijn extern: Claude
opnieuw authenticeren, immutable images publiceren, een OpenShift-project selecteren, acceptatie
uitrollen en controleren, een databasebackup maken en daarna productie uitrollen. De actuele
verificatiestatus staat in [release-verificatie.md](release-verificatie.md).

De eerste platformrelease
bevat al het duurzame control plane, de lokale worker, Codex- en Claude-uitvoering,
`REPOSITORY_WORK`, centrale mocks, OpenShift-deployment en een compacte technische monitor.

Dit plan beschrijft alleen wijzigingen in deze repository. Werk in Product Factory, Software
Factory, Newsfeed, HKH of een andere consument hoort in het stappenplan van die applicatie en staat
hier niet meer tussen. Agent Runtime levert wel de generieke contracten en API's die zulke
consumenten nodig hebben.

De uitgevoerde wijzigingen komen uit drie leidende specificaties:

1. [Vereenvoudigd `APPLICATION_WORK`-contract](application-work.md);
2. [Betrouwbare JSON-resultaten](implementatieplan-betrouwbare-json-resultaten.md);
3. [Eenvoudige beheerinterface](beheerinterface.md).

De huidige API heeft nog geen consumers. Het bestaande contract wordt daarom rechtstreeks breaking
vereenvoudigd. Er komt geen parallel v2-contract, compatibiliteitsroute of overgangsmodel.

## Doel

Na uitvoering van dit plan kan Agent Runtime:

- een minimale `APPLICATION_WORK`-aanvraag met één complete prompt uitvoeren;
- lokaal beschikbare projectcredentials uitsluitend bij naam ontdekken en per attempt begrensd
  beschikbaar stellen;
- kleine inputattachments veilig materialiseren en outputartifacts als bestanden verzamelen;
- iedere echte attempt onafhankelijk op een harde deadline stoppen;
- uitsluitend syntactisch en schema-geldige JSON als succesvol resultaat publiceren;
- een model binnen dezelfde technische attempt maximaal drie gerichte outputpogingen geven;
- hetzelfde gedrag deterministisch via de centrale mockexecutor testen;
- actieve, wachtende en afgeronde jobs, workers, resultaten en zichtbare geredigeerde
  AI-transcripten via een beveiligde Flutter Web-monitor tonen.

## Buiten scope

- Clients, outboxes, rolconfiguratie of domeinverwerking in consumerende applicaties bouwen.
- Applicatiespecifieke agentrollen, prompttemplates, jobkeys of domeincorrelatie in Agent Runtime
  opnemen.
- Spring AI introduceren of Codex CLI en Claude Code vervangen.
- Provider- of modelkeuze stilzwijgend wijzigen na een fout.
- `REPOSITORY_WORK` opnieuw ontwerpen; alleen gedeelde infrastructuur mag daarvoor worden
  uitgebreid.
- Een agent Git-publicatiecredentials, Runtime-secrets of providercredentials geven.
- Verborgen chain-of-thought verzamelen of reconstrueren.
- Een configuratie- of bedieningsconsole bouwen. De nieuwe frontend is alleen een monitor.
- Grote attachments, archiefuitpak of een objectstoremigratie toevoegen.

## Vaste architectuur- en veiligheidsgrenzen

Deze regels gelden in iedere fase en worden niet per story opnieuw onderhandelbaar:

- De server is de enige eigenaar van jobs, planning, attempts, leases, fencing, retries, events,
  transcriptdelen, resultaatacceptatie en artifactmetadata.
- Alleen de server beslist of een AI-resultaat geldige JSON is en aan het bevroren schema voldoet.
- De worker maakt uitsluitend uitgaande HTTPS-verbindingen en heeft geen directe
  databaseverbinding.
- Tenant, toegestane jobsoort, provider, model, tools, zichtbare projectprefixes, prioriteit,
  retrybeleid en quota volgen uit het bearer-token en serverconfiguratie. Een payload verleent geen
  rechten.
- Runtime-, worker-, provider- en Git-publicatiecredentials blijven buiten de agentcontainer.
- Alleen expliciet gevraagde waarden uit lokaal `project-credentials.env` mogen tijdelijk in een
  agentcontainer worden gemount.
- Prompt, transcript, diagnose, fout, resultaat en artifactmetadata zijn begrensd en worden vóór
  duurzame opslag geredigeerd waar dat contractueel is toegestaan. Een JSON-resultaat wordt nooit
  ongemerkt aangepast om redactie mogelijk te maken.
- Iedere workeroperatie op een echte attempt vereist het actuele attempt-ID en fencing token.
- Leases en herstelvensters kunnen een harde attemptdeadline nooit verlengen.
- `MOCKED` is server-side, maakt geen workerattempt en is in productie niet beschikbaar.
- Events, transcriptdelen en een succesvol eindresultaat zijn append-only respectievelijk
  onveranderlijk.
- `agent-runtime-contracts` bevat alleen versieerbare contractvormen. De servermodules blijven
  acyclisch en de worker praat uitsluitend via het HTTPS-contract.

## Uitvoeringsvolgorde

De fasen worden in onderstaande volgorde uitgevoerd. Een fase mag technisch in meerdere pull
requests worden verdeeld, maar haar Definition of Done is de poort naar de volgende fase.

## Fase 0 — Huidige contract direct vereenvoudigen

### Resultaat

Eén klein huidig contract zonder legacyvelden of parallelle API-versie.

### Werk

- Pas de bestaande `/v1/jobs`-OpenAPI, Kotlin-contracttypen en implementatie rechtstreeks aan; voeg
  geen `/v2` toe.
- Gebruik als verplichte gemeenschappelijke requestvelden alleen `jobKind`, `idempotencyKey`,
  `provider`, `model` en `prompt`.
- Houd `responseSchema`, `executionTimeoutSeconds`, `repositorySnapshot` en de gecontroleerde
  `repositoryRequest` optioneel of jobsoortafhankelijk.
- Verwijder profiel, applicatiejobkey, configuratieversie, instructieversie, losse
  instructies/input, vrije resourceaanvragen en consumercontext uit contract, database, mocks,
  worker, monitor en tests.
- Verwijder `priority` en `maxAttempts` uit de aanvraag. Bewaar technische retrylimiet en prioriteit
  uitsluitend als interne, server-side configuratie en status.
- Houd zowel `APPLICATION_WORK` als de bestaande gecontroleerde `REPOSITORY_WORK`-aanvraag op
  dezelfde huidige API.
- Bevries provider, model, prompt, responseschema, time-out, environmentkeynamen, attachments en
  repositorysnapshot bij het aanmaken van de job.
- Laat prioriteit, technische retries, maximale outputpogingen, quota, tools, netwerkbeleid,
  attachmentlimieten, artifactlimieten en zichtbare projectprefixes uit serverpolicy per
  geauthenticeerde consument volgen.
- Maak foutresponses voor contract- en policyafwijzingen stabiel en versieerbaar.
- Voeg een Flywaymigratie toe die de verwijderde job- en mockkolommen uit een bestaande database
  verwijdert. Er hoeft geen oude request-JSON te worden gemigreerd.
- Weiger onbekende JSON-requestvelden zodat legacyvelden niet stilzwijgend worden genegeerd.
- Gebruik in de monitor een servergegenereerde technische naam op basis van applicatie,
  jobsoort en verkort job-ID. Voeg geen vrij taaknaam- of correlatieveld toe om de verwijderde
  domeincontext alsnog terug te brengen.

### Definition of Done

- De gepubliceerde huidige OpenAPI beschrijft ieder veld, limiet, foutgeval en voorbeeld volledig.
- Een aanvraag met een verwijderd of onbekend veld wordt als clientfout afgewezen.
- Een verse database en een bestaande database op de vorige Flywayversie komen op hetzelfde nieuwe
  schema uit.
- Een payload kan geen provider, model, prefix, tool, retry, prioriteit of quotum buiten de
  serverpolicy activeren.

## Fase 1 — Projectcredentials, catalogus en workerrouting

### Resultaat

Projectcredentialwaarden blijven volledig lokaal, terwijl server en consumenten uitsluitend
kunnen zien welke namen beschikbaar zijn en de scheduler een geschikte worker kan kiezen.

### Werk

- Splits de lokale configuratie in:
  - één owner-only `properties.env` voor alle interne instellingen van de laptopworker;
  - `project-credentials.env` voor projectgebonden waarden die een agentjob mag ontvangen.
- Voeg beide echte workerbestanden toe aan `.gitignore` en `.dockerignore`; lever alleen waardevrije
  `.example`-bestanden.
- Vereis voor beide bestanden mode `0600`, een regulier bestand en geen symlink.
- Bouw een strikte dotenv-parser die dubbele keys, ongeldige regels en onveilige namen weigert.
- Accepteer projectkeys uitsluitend volgens
  `[A-Z][A-Z0-9_]*__[A-Z][A-Z0-9_]*`.
- Weiger `AR__`-namen en bekende Runtime-, provider- en Git-publicatiecredentials in
  `project-credentials.env`.
- Laat de worker bij registratie en iedere relevante wijziging alleen de beschikbare keynamen
  melden, nooit waarden of hashes van waarden.
- Bewaar per worker naam, laatst gezien tijdstip en actuele beschikbaarheid. Een offline worker
  maakt een naam historisch bekend maar niet beschikbaar.
- Voeg een beveiligde, op tokenpolicy gefilterde catalogusquery toe, conceptueel
  `GET /v1/environment-keys?project=<PREFIX>`.
- Retourneer per catalogusitem minimaal naam, projectprefix, beschikbaarheid, aantal passende
  online workers en `lastSeenAt`.
- Laat de scheduler alleen een worker selecteren die provider, model, jobsoort en alle gevraagde
  environmentkeynamen ondersteunt.
- Controleer in de worker na claim opnieuw of iedere naam aanwezig en voor de consument/prefix
  toegestaan is.
- Houd een job zichtbaar wachtend als een passende worker later beschikbaar kan komen. Eindig met
  `REQUIRED_ENVIRONMENT_KEY_UNAVAILABLE` als de aanvraag volgens de actuele configuratie niet
  uitvoerbaar kan worden.
- Materialiseer per attempt uitsluitend de geselecteerde subset als tijdelijk bestand
  `/job/secrets/secrets.env` met mode `0600`; mount het bronbestand nooit.
- Verwijder de tijdelijke subset bij succes, fout, annulering, time-out, recovery en workerstartup.
- Voeg centrale redactie toe op alle lokaal bekende credentialwaarden voordat workerlogs,
  voortgang, transcriptdelen of diagnoses worden verstuurd.

### Definition of Done

- Geen projectcredentialwaarde bereikt aanvraag, queue, database, catalogus, event, transcript,
  resultaat, artifactmetadata of Dockerlabel.
- De agentcontainer ziet precies de gevraagde subset en nooit `project-credentials.env` zelf.
- Onveilige bestandsrechten, symlinks, dubbele keys en verboden namen laten de worker veilig dicht
  falen.
- Online-, stale- en offline-overgangen werken door in catalogus en claimselectie.
- Fencing en tokenpolicy voorkomen dat een worker namen voor een andere identiteit misbruikt.

## Fase 2 — Vaste taakdirectory, inputattachments en outputartifacts

### Resultaat

Iedere echte attempt krijgt dezelfde begrensde bestandsinterface, ongeacht provider.

### Werk

- Maak per echte attempt deze structuur:

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

- Mount `input`, `secrets` en `docs` read-only en alleen `output` schrijfbaar. Houd een eventuele
  repositoryworktree afzonderlijk op `/work`.
- Genereer `available-tools.md` vanuit de vaste execution-image- en serverpolicy. Beschrijf tools,
  paden, outputafspraken en het gebruik van geselecteerde environmentvariabelen zonder waarden te
  tonen. Leg expliciet vast dat secretwaarden nooit in een providerprompt, nieuwe AI-request,
  transcript of output mogen worden opgenomen. Het document verleent zelf geen rechten en deze
  instructie is geen vervanging voor mounts, netwerkbeleid en redactie.
- Voeg aan de prompt één vaste technische slotinstructie toe over inputpaden, geheimhouding,
  het verbod op doorsturen van secretwaarden, `/job/output/result.json` en
  `/job/output/artifacts`.
- Accepteer maximaal tien Base64-inputattachments, maximaal 2 MB gedecodeerd per bestand en 10 MB
  gedecodeerd per job.
- Valideer vóór opslag platte veilige bestandsnamen, unieke namen, Base64, gedeclareerd MIME-type,
  magic bytes waar mogelijk, individuele en totale omvang. Sta geen paden, symlinks, apparaten,
  executables of archiefuitpak toe.
- Bewaar geaccepteerde input duurzaam met grootte en SHA-256 zodat queueherstel dezelfde bytes
  oplevert.
- Laat de worker hash, naam, type en limieten opnieuw controleren vóór materialisatie.
- Voeg attachments niet automatisch aan modeltekst toe; de prompt verwijst expliciet naar de
  bestandsnaam wanneer de agent hem moet gebruiken.
- Scan na providerafronding alleen directe reguliere bestanden in `/job/output/artifacts`.
- Weiger symlinks, subdirectories, apparaten, onveilige of dubbele namen, typeconflicten en
  overschrijdingen.
- Handhaaf maximaal 25 outputartifacts, 5 MB per bestand en 25 MB per job.
- Bereken MIME-type, grootte en SHA-256 en upload elk bestand via de bestaande gefencete worker-API
  voordat het eindresultaat wordt vastgelegd.
- Koppel artifactmetadata onveranderlijk aan het geaccepteerde jobresultaat en bied gecontroleerde
  downloads aan.
- Ruim alle tijdelijke invoer, secrets en uitvoer op bij iedere terminale of herstelroute.

### Definition of Done

- Dezelfde screenshot kan als Base64-input worden ingestuurd, als regulier bestand door beide
  providers worden gelezen en als gecontroleerd outputartifact worden gedownload.
- Path traversal, MIME-spoofing, corrupte Base64, symlinks en alle grensoverschrijdingen zijn
  geautomatiseerd getest.
- Het model hoeft nooit een outputbestand naar Base64 om te zetten.
- Een retry kan geen artifact dubbel koppelen en een oud fencing token kan niets uploaden.

## Fase 3 — Harde attemptdeadline

### Resultaat

Iedere echte execution-attempt stopt uiterlijk op de bij claimen bevroren deadline, ook bij slaap,
leaseherstel of een defecte worker.

### Werk

- Bereken bij claimen server-side
  `attemptDeadline = claimedAt + executionTimeoutSeconds`.
- Bewaar `claimedAt`, de bevroren timeout en `attemptDeadline` duurzaam bij de attempt.
- Neem de server-authoritatieve deadline op in het claimantwoord en workerjournal.
- Gebruik in de worker daarnaast een monotone lokale timer; de vroegste van serverdeadline en
  lokale grens wint.
- Stop eerst beheerst en daarna geforceerd de providercontainer en onderliggende processen.
- Laat leases, heartbeats, `SUSPECTED`, slaap en recovery de deadline nooit aanpassen.
- Fence de attempt server-side zodra de deadline verstrijkt en weiger daarna heartbeat, progress,
  transcript, artifact en resultaat.
- Registreer een onveranderlijk event en foutcode `EXECUTION_TIMEOUT`.
- Laat uitsluitend de vaste server-side technische retrypolicy bepalen of een nieuwe attempt wordt
  gepland.
- Lees bij workerrestart de oorspronkelijke deadline uit het versleutelde journal; hervat nooit een
  al verlopen attempt.
- Tel queuewachttijd niet mee in deze attemptdeadline. Voeg in deze release geen tweede algemene
  jobdeadline toe.

### Definition of Done

- Server en worker kunnen elk afzonderlijk een te lange attempt begrenzen.
- Een laptop die voorbij de deadline slaapt kan na ontwaken geen laat resultaat of artifact meer
  publiceren.
- Een technische retry krijgt een nieuwe attempt en deadline zonder de historie van de oude poging
  te wijzigen.
- Deadline-, lease-, recovery-, annulering- en racecondities zijn met een bestuurbare klok getest.

## Fase 4 — Gezaghebbende JSON-schema-validatie

### Resultaat

Geen AI-job wordt `SUCCEEDED` tenzij de server het resultaat als geldige JSON en, indien aanwezig,
tegen het bevroren responseschema heeft geaccepteerd.

### Werk

- Vervang `SimpleJsonSchemaValidator` door een eigen `JsonResultValidator`-adapter rond een
  vastgepinde, onderhouden Draft 2020-12-library, bij voorkeur
  `com.networknt:json-schema-validator`.
- Voeg geen Spring AI-afhankelijkheid toe en laat librarytypen niet uit de servermodule lekken.
- Valideer een responseschema al bij het aanmaken van de job:
  - geldige JSON en een rootobject;
  - compileerbaar als Draft 2020-12;
  - begrensde omvang;
  - geen externe of netwerk-`$ref`;
  - alleen het gedocumenteerde portable profiel dat door Codex en Claude wordt ondersteund.
- Ondersteun minimaal rootobjecten, `properties`, `required`, `additionalProperties`, geneste
  objecten, arrays met `items`/`minItems`/`maxItems`, ondersteunde primitieve types, `enum` en
  gangbare string- en getalgrenzen.
- Geef bij aanvragen stabiele fouten `RESPONSE_SCHEMA_INVALID`,
  `RESPONSE_SCHEMA_UNSUPPORTED` en `RESPONSE_SCHEMA_TOO_LARGE`.
- Laat de validator eigen veilige foutobjecten teruggeven met `path`, `keyword` en `message`.
- Sorteer fouten deterministisch, begrens ze tot maximaal 25 en neem geen volledige invoerwaarden
  in meldingen op.
- Dien een AI-kandidaat als begrensde tekst in, niet als vooraf geparseerde `JsonNode`.
- Handhaaf maximaal 5 MB vóór normalisatie en parsing.
- Normaliseer server-side voor echte workers en mocks identiek:
  1. probeer de volledige getrimde tekst;
  2. accepteer één volledig `json`-codeblok met alleen witruimte of een korte bekende introductie
     eromheen;
  3. zoek niet willekeurig naar accolades in vrije tekst.
- Laat `REPOSITORY_WORK` zijn deterministische resultaatroute behouden; een schema- of
  programmeerfout mag nooit opnieuw Git-publicatie starten.
- Houd de oude `/complete`-route compatibel en laat een afwijzing daar een echte stabiele foutcode
  geven in plaats van `WORKER_ERROR`. Verander de actieve attempt daarbij pas wanneer vaststaat of
  de oude caller nog kan corrigeren of terminal moet falen.

### Definition of Done

- Syntactisch ongeldige JSON, proza, verkeerd getypeerde velden en schema-afwijkingen kunnen nooit
  als succesvol resultaat worden opgeslagen.
- Codex, Claude en `MOCKED` komen uiteindelijk bij dezelfde servervalidator uit.
- Zonder responseschema blijft syntactisch geldige JSON verplicht.
- Validator-unittests dekken het volledige portable profiel, foutsortering, foutlimieten,
  schemafouten en verboden refs.

## Fase 5 — Duurzame outputpogingen en self-correction

### Resultaat

Een model krijgt maximaal drie directe, gecontroleerde kansen op een volledig geldig JSON-resultaat
zonder technische retries en outputpogingen door elkaar te halen.

### Werk

- Voeg met Flyway een duurzame `runtime_output_attempt`-tabel toe met minimaal:
  - ID, job-ID en execution-attempt-ID;
  - oplopend outputpogingnummer;
  - status `RESERVED`, `REJECTED`, `ACCEPTED` of `ABANDONED`;
  - kandidaat-SHA-256, begrensde geredigeerde diagnose en validatiefouten;
  - provider/modelmomentopname en start-/eindtijden.
- Maak `(job_id, output_attempt_number)` uniek en reserveer een poging vóór iedere modelaanroep.
- Gebruik standaard maximaal drie outputpogingen uit serverpolicy. Een crash na reservering
  verbruikt de poging; een netwerkretry niet.
- Voeg gefencete, idempotente interne workeroperaties toe om een outputpoging te reserveren en een
  kandidaattekst in te dienen.
- Retourneer `ACCEPTED`, `CORRECTION_REQUIRED` of `EXHAUSTED`, gestructureerde validatiefouten en
  het resterende budget.
- Neem begrensde providermetadata over gebruik en kosten optioneel bij de kandidaatinzending op,
  zonder providercredentials of ruwe geheime waarden vast te leggen.
- Sla van een afgewezen kandidaat alleen SHA-256, maximaal 2.000 geredigeerde diagnosetekens,
  begrensde validatiefouten, provider/model en tijden op.
- Voeg minimaal de events `OUTPUT_ATTEMPT_STARTED`, `OUTPUT_REJECTED_NOT_JSON`,
  `OUTPUT_REJECTED_SCHEMA`, `OUTPUT_CORRECTION_REQUESTED`, `OUTPUT_ACCEPTED` en
  `OUTPUT_ATTEMPTS_EXHAUSTED` toe.
- Splits Codex- en Claude-aansturing in testbare provideradapters die één kandidaattekst leveren.
- Houd voor Codex de native outputschema-optie en het laatste-antwoordbestand, maar behandel dat
  bestand nog niet als geaccepteerd resultaat.
- Houd voor Claude de native JSON-schema-optie; gebruik bij een provider-envelope eerst het echte
  `structured_output`- of gedocumenteerde resultaatveld en anders de resultaattekst.
- Laat adapters alleen ondubbelzinnige providertransporten uitpakken; algemene normalisatie blijft
  op de server.
- Pas de workerloop voor `APPLICATION_WORK` aan:
  1. materialiseer input en schema eenmaal;
  2. reserveer een outputpoging;
  3. start een unieke providercontainer;
  4. blijf heartbeat en deadline bewaken;
  5. dien kandidaattekst in;
  6. rond af bij `ACCEPTED`;
  7. start direct een nieuwe providercontainer met correctieprompt bij
     `CORRECTION_REQUIRED`;
  8. ruim op wanneer de server `EXHAUSTED` heeft vastgelegd.
- Neem outputpoging-ID en -nummer op in het versleutelde workerjournal en gebruik unieke
  containernamen en resultaatpaden.
- Bouw de correctieprompt uit de volledige oorspronkelijke prompt en het schema plus alleen de
  gestructureerde foutfeedback. Vraag het volledige antwoord opnieuw, niet een patch. Neem de oude
  kandidaattekst niet opnieuw op.
- Gebruik geen back-off tussen outputpogingen. Providerstoringen, time-outs, leaseverlies en
  workercrashes blijven via de bestaande technische retryroute lopen.
- Eindig met `OUTPUT_ATTEMPTS_INTERRUPTED` wanneer het totale outputbudget uitsluitend door
  afgebroken technische uitvoeringen is verbruikt; rapporteer dat niet als inhoudelijke
  JSON-afwijzing.
- Gebruik minimaal deze stabiele foutcodes:
  - `MODEL_OUTPUT_NOT_JSON`;
  - `MODEL_OUTPUT_SCHEMA_INVALID`;
  - `MODEL_OUTPUT_RETRIES_EXHAUSTED`;
  - `OUTPUT_ATTEMPTS_INTERRUPTED`;
  - `RESULT_TOO_LARGE`;
  - `ENGINE_FAILED`.
- Laat de derde inhoudelijke afwijzing job en attempt terminal `FAILED` maken. Laat een handmatige
  beheerretry, buiten de nieuwe monitor, een expliciete nieuwe uitvoering met gereset outputbudget
  en behouden historie starten.

### Definition of Done

- Eén technische attempt kan drie outputpogingen bevatten zonder lease- of retry-back-off tussen de
  modelaanroepen.
- Een crash, netwerkretry, oud fencing token of herhaald request kan geen gratis of dubbele poging
  veroorzaken.
- Correctiefeedback bevat concrete veilige fouten en nooit de volledige afgewezen uitvoer.
- Een geldig resultaat is na acceptatie onveranderlijk.
- `REPOSITORY_WORK` wordt niet opnieuw door een agent uitgevoerd vanwege resultaatvalidatie.

## Fase 6 — Centrale mocks en JSON-correctie

### Resultaat

Alle nieuwe contract-, bestands-, timeout- en JSON-scenario's zijn zonder laptop of AI-kosten
deterministisch te testen.

### Werk

- Houd de eenvoudige voorbereide `result: JsonNode` naast de nieuwe kandidaatreeks beschikbaar.
- Voeg een begrensde `outputSequence: List<String>` toe voor ruwe kandidaatreeksen.
- Gebruik voor mockselectie een afzonderlijke beveiligde testcorrelatie buiten het minimale
  productiecontract; voeg daarvoor geen applicatiejobkey aan iedere productiejob toe.
- Laat de mockexecutor dezelfde servernormalisatie, schemavalidatie, outputpogingadministratie en
  foutcodes gebruiken als echte `APPLICATION_WORK`-uitvoering.
- Maak voor een mock geen workerattempt, taakdirectory, credentialselectie, deadlinelease of
  container.
- Ondersteun fixtures voor:
  - direct geldige JSON;
  - totaal ongeldige JSON en proza;
  - een toegestaan JSON-codeblok;
  - ontbrekende verplichte velden en verkeerde types;
  - succes op poging twee of drie;
  - drie afwijzingen en uitgeput budget;
  - te grote kandidaat;
  - schemafout vóór jobaanmaak;
  - bestaande voorbereide mockresultaten.
- Consumeer nooit kandidaten boven het maximumbudget. Laat een te korte reeks expliciet falen in
  plaats van wachten.
- Laat mockartifacts dezelfde naam-, type-, hash- en groottelimieten doorlopen als echte artifacts.
- Houd Test Control API uitsluitend in acceptatie beschikbaar en laat productie bij startup en
  aanvraagvalidatie dicht falen voor `MOCKED`.

### Definition of Done

- Iedere foutcode en outputpogingsovergang kan met één deterministische fixture worden bewezen.
- Mock- en echte kandidaatroutes delen validator- en resultaatcode; er bestaat geen tweede
  mockvalidator.
- Productie bevat geen bruikbare Test Control-route en accepteert geen mockprovider.

## Fase 7 — Duurzaam zichtbaar transcript en managementquery's

### Resultaat

De server kan alle voor een beheerder beschikbare zichtbare providerinteractie veilig en
incrementeel tonen, zonder verborgen redeneerstappen te verzinnen.

### Werk

- Voeg een append-only transcriptopslag toe met minimaal job-ID, attempt-ID, deel-ID, sequence,
  tijdstip, kind, tekst en redactievlag.
- Ondersteun kinds `PROMPT`, `AGENT_TEXT`, `TOOL_CALL`, `TOOL_OUTPUT`, `CORRECTION` en
  `PROVIDER_RESULT`.
- Laat alleen de actuele gefencete attempt transcriptdelen schrijven.
- Maak ingest idempotent op deel-ID en sequence en behoud transcript van oudere attempts na een
  technische retry.
- Laat provideradapters uitsluitend werkelijk beschikbare zichtbare tekst en toolgebeurtenissen
  vertalen. Vraag geen chain-of-thought op en reconstrueer niets dat de provider niet levert.
- Redigeer in de worker en opnieuw op de server vóór opslag. Markeer redactie expliciet zodat de
  frontend `Waarde door Agent Runtime afgeschermd` kan tonen.
- Kap een transcript nooit stilzwijgend af. Maak het bereiken van een opslaglimiet een expliciete
  technische fout en bewaar tot welk sequence-nummer opslag gelukt is.
- Voeg beveiligde beheerquery's toe voor:
  - actieve jobs;
  - wachtrij in verwachte claimvolgorde met serverberekende wachtreden;
  - afgeronde jobs met server-side zoekactie en cursorpaginering van maximaal 30;
  - jobdetail en resultaat;
  - transcript na sequence/cursor;
  - workers.
- Neem in iedere lijstresponse de servertijd op. Laat de transcriptresponse een volgende cursor en
  actieve/terminale indicatie bevatten.
- Maak lange transcripten in beide richtingen pagineerbaar zodat een beheerder altijd het eerste en
  laatste bewaarde deel kan bereiken.
- Zoek op job-ID, servergegenereerde technische naam en applicatie. Introduceer geen nieuwe
  domeincorrelatie.
- Geef workers als `ONLINE`, `STALE` of `OFFLINE` terug met laatste heartbeat, capaciteit,
  providers, modellen, jobsoorten en actuele technische jobnaam.
- Toon bij outputpogingen pogingnummer, maximum, provider/model, veilige foutcode en begrensde
  validatiefouten; expose nooit volledige afgewezen uitvoer in lijsten. Een beheerderdetail mag
  uitsluitend de geredigeerde diagnose-uitsnede tonen.
- Beveilig alle query's met de bestaande adminidentiteit. Retourneer nooit credentials, lokale
  paden of fencing tokens.

### Definition of Done

- Een live transcript kan vanaf het laatst ontvangen sequence-nummer zonder duplicaten worden
  bijgewerkt.
- Meerdere technische attempts en outputpogingen blijven in de juiste onveranderlijke volgorde
  zichtbaar.
- Zoekactie, cursorpaginering, claimvolgorde en wachtreden worden volledig server-side bepaald.
- Een provider zonder zichtbaar transcriptveld veroorzaakt geen verzonnen content.
- Geheime testwaarden komen in geen enkele managementresponse voor.

## Fase 8 — Flutter Web-monitor

### Resultaat

Een beheerder kan actuele en afgeronde uitvoering volgen via één beveiligde, responsive monitor.

### Werk

- Voeg een Flutter Web-app toe die vanuit dezelfde release als Agent Runtime wordt geleverd.
- Maak vijf views:
  - **Actieve jobs**;
  - **Wachtrij**;
  - **Afgeronde jobs**;
  - **Workers**;
  - **Jobdetail**.
- Toon bij actieve jobs technische naam/verkort ID, applicatie, jobsoort, provider/model, worker,
  fase, voortgang, starttijd en verstreken tijd.
- Toon wachtrijitems in de servervolgorde met wachttijd en serverberekende reden.
- Gebruik voor wachtredenen minimaal **klaar om te claimen**, **wacht op geschikte worker** en
  **uitgesteld tot retrymoment**.
- Toon afgeronde jobs nieuwste eerst, maximaal 30 per pagina, met zoekterm en cursor in de URL en
  duidelijke vorige/volgende-acties. Neem alleen `SUCCEEDED`, `FAILED` en `CANCELLED` op.
- Toon workerstatus, heartbeat, capaciteit, capabilities en actuele job zonder lokale secrets of
  paden.
- Toon in jobdetail metadata, schema-gevalideerd JSON-resultaat als tekst, gecontroleerde
  artifactdownloads, veilige fout of annuleringsinformatie inclusief actor en tijdstip, en het
  beschikbare transcript.
- Render prompt, resultaat, fout en transcript nooit als HTML.
- Poll actieve jobs en transcript incrementeel. Volg nieuwe tekst alleen automatisch als de
  beheerder al onderaan staat en behoud de leespositie anders.
- Toon transcriptstatus `Live`, `Verbinding onderbroken` of `Afgerond`.
- Behoud de laatste succesvolle data bij een refreshfout en toon het tijdstip van die
  momentopname.
- Bied alleen **Verversen** als algemene actie. Voeg geen annuleren, retry, mockbeheer,
  workerbediening, prioriteitswijziging of configuratie toe.
- Toon duidelijke lege toestanden voor iedere lijst en onderscheid geen historie van geen
- Gebruik daarbij de vastgelegde teksten **Er worden nu geen jobs uitgevoerd**, **De wachtrij is
  leeg**, **Er zijn nog geen afgeronde jobs**, **Geen jobs gevonden voor deze zoekterm** en
  **Er zijn geen workers geregistreerd**.
- Gebruik standaard Google-login tegen de server-side beheerdersallowlist, wissel het Google
  ID-token in voor een eigen Runtime-sessie en bewaar het ID-token niet. Houd het handmatige
  beheertoken alleen als ingeklapte noodoptie. Toon bovenin de servergeleverde omgeving `Acceptatie`
  of `Productie`; dit label is geen schakelaar.
- Geef `index.html` en versie-informatie `no-store`; geef inhoudsgehashte assets een lange cache.
- Volg het UX-concept in `ux/index.html`: rustige donkergroene navigatie, lichte achtergrond,
  witte kaarten, veel witruimte en tekstlabels naast statuskleur.
- Maak alle functies toetsenbordbedienbaar, status niet alleen kleurafhankelijk, transcript
  selecteerbaar, mobiel bruikbaar vanaf 320 CSS-pixels en bruikbaar bij 200% tekstvergroting.
- Respecteer `prefers-reduced-motion`.

### Definition of Done

- De vijf views werken tegen de echte managementquery's en bevatten geen synthetische data.
- Een actieve job vult zijn transcript aan zonder duplicaten of ongewenste scrollsprongen.
- Resultaat, fout, artifacts, meerdere attempts en outputcorrecties zijn veilig leesbaar.
- Lege toestand, zoekactie, paginering, refreshfout, verlopen sessie en onbereikbare backend zijn
  getest.
- Widget-, contract- en toegankelijkheidstests slagen op 320 pixels en 200% tekstvergroting.

## Fase 9 — Observability, systeemtests en release

### Resultaat

De drie wijzigingen zijn gezamenlijk herstelbaar, meetbaar en achterwaarts compatibel inzetbaar.

### Werk

- Voeg metrics toe voor:
  - acceptatie op outputpoging 1, 2 en 3;
  - `MODEL_OUTPUT_NOT_JSON` en `MODEL_OUTPUT_SCHEMA_INVALID`;
  - uitgeputte outputpogingen per provider/model;
  - gemiddeld aantal outputpogingen per succesvolle job;
  - wachtende jobs zonder passende environmentkeys;
  - harde time-outs;
  - transcriptingest- en redactiefouten;
  - attachment- en artifactafwijzingen.
- Toon technische attempts en outputpogingen als verschillende begrippen in metrics, events en
  monitor.
- Voeg unit-, serverintegratie-, worker-, mock- en end-to-endtests toe voor alle Definitions of
  Done uit fase 0 tot en met 8.
- Test expliciet idempotentie, fencing, oude attempts, server-/workerrestart, slaap voorbij de
  deadline, leaseverlies, annulering en gelijktijdige timeout/resultaat-races.
- Bouw het execution-image en voer, indien lokale accounts aanwezig zijn, één handmatige smokejob
  per Codex en Claude uit met strikt schema, attachment, environmentkey en outputartifact.
- Laat ontbrekende lokale provideraccounts de geautomatiseerde build niet laten falen.
- Voer minimaal `mvn -B --no-transfer-progress verify`, containerbuilds, OpenAPI-validatie,
  Modulith-/ArchUnitcontroles, Flutter-tests en beide Kustomize-validaties uit.
- Werk README, architectuur, deploymentdocumentatie en runbook bij met het vereenvoudigde contract, credentialbestanden,
  timeout, outputpogingen, transcript, monitor, foutcodes en herstelprocedures.
- Rol eerst uit naar acceptatie en controleer contractafwijzingen, migratie, mocks, echte smokejobs,
  metrics en redactie.
- Maak vóór productie een databasebackup en rol daarna de immutable serverrelease uit. Publiceer
  het multi-arch execution-image tegelijk onder een SHA-tag en de bewegende `main`-tag. Laptopworkers
  gebruiken standaard `main` met `docker run --pull always`; de SHA-tag blijft beschikbaar voor een
  expliciete rollback. Deze release is bewust contract- en databaseschemabreking; een oude
  serverversie is na de kolomverwijdering geen geldige applicatierollback.

### Definition of Done

- Alle geautomatiseerde verificaties en beide provider-smokes slagen, of het ontbreken van een
  lokaal provideraccount is expliciet vastgelegd zonder een test te faken.
- De breaking database-upgrade en de herstelroute via backup zijn in acceptatie geoefend.
- Runbookscenario's bestaan voor credentialbestand afgewezen, environmentkey niet beschikbaar,
  JSON-pogingen uitgeput, harde time-out, transcriptopslag vol en mislukte artifactupload.
- Productie weigert mocks, bewaart geen projectcredentialwaarden en accepteert geen laat of
  schema-ongeldig resultaat.

## Eerste uitvoerbare stories

Deze stories vormen de aanbevolen pull-requestvolgorde:

1. Vereenvoudig de huidige OpenAPI, Kotlin-contracten, opslag, worker, mocks en monitor breaking en
   voeg strictness- en migratietests toe.
2. Voeg serverpolicy voor provider, model, retry, prioriteit, tools en prefixes
   toe.
3. Splits lokale secretbestanden en bouw veilige parsing plus workerregistratie van alleen namen.
4. Bouw environmentcatalogus en capability-/keygebaseerde schedulerselectie.
5. Bouw de vaste taakdirectory en `available-tools.md`.
6. Voeg duurzame Base64-inputattachments en veilige materialisatie toe.
7. Verzamel, valideer en upload outputartifacts uit de taakdirectory.
8. Voeg duurzame attemptdeadline en onafhankelijke server-/workerhandhaving toe.
9. Vervang de eenvoudige JSON-schemavalidator en valideer schema's vóór jobaanmaak.
10. Voeg duurzame outputpogingen en de gefencete worker-API toe.
11. Splits provideradapters en bouw de directe self-correction-loop.
12. Breid de centrale mock uit met ruwe kandidaatreeksen en contractfixtures.
13. Voeg duurzame geredigeerde transcriptingest en managementquery's toe.
14. Bouw de vijf Flutter-monitorviews tegen de echte API.
15. Voeg volledige systeemtests, metrics, runbooks en acceptatie-/productierelease toe.

Iedere story bevat eigen migratie- en contracttests. Een story wordt niet als afgerond gemarkeerd
wanneer alleen het happy path werkt.

## Specificatiedekking

| Specificatieonderdeel | Fase |
|---|---|
| Minimale huidige aanvraag, verwijderde legacyvelden en serverpolicy | 0 |
| `secrets.env`, `project-credentials.env`, catalogus en claimselectie | 1 |
| Taakdirectory, `available-tools.md`, Base64-input en file-based output | 2 |
| Harde uitvoeringstime-out en recovery | 3 |
| Volledige JSON Schema-validatie en centrale normalisatie | 4 |
| Duurzame outputpogingen, provideradapters en self-correction | 5 |
| Centrale mocks en Test Control | 6 |
| Zichtbaar geredigeerd transcript en management-API | 7 |
| Actief, wachtrij, afgerond, workers en jobdetail in Flutter | 8 |
| Metrics, tests, documentatie, rollout en rollback | 9 |

## Eindbeeld

Agent Runtime blijft een generiek technisch uitvoeringsplatform. Een consumerende applicatie stuurt
een complete taak en bewaart zelf alle domeincontext. De Runtime plant een passende worker, stelt
alleen de toegestane lokale credentialsubset en bestanden beschikbaar, begrenst de attempt hard,
accepteert alleen betrouwbare JSON en bewaart een veilig zichtbaar transcript en gecontroleerde
artifacts. Beheerders kunnen dit volgen in één kleine monitor; geen enkele consumerimplementatie is
onderdeel van dit repositoryplan.
