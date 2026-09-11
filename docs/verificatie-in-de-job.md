# Agent Runtime — verificatie binnen de job

Status: volledig geïmplementeerd en in productie geverifieerd

Peildatum: 2026-09-11

Doelrepository: `agent-runtime`

Aanvulling op: `docs/software-factory-v2-integratieplan.md`

Besluitstatus: voor deze capability staan geen ontwerpkeuzes meer open; de implementerende agent
volgt de vastgelegde keuzes en vult geen alternatieven in.

## Doel van dit document

Dit document beschrijft één aanvullende capability voor Agent Runtime v2: een muterende job voert na
de agent de projectverificatie uit, geeft rode uitkomsten terug aan diezelfde agent, en publiceert
alleen wanneer de verificatie groen is. Het document is zelfstandig leesbaar; kennis van de
voorafgaande gesprekken is niet nodig.

Dit is de laatste openstaande voorwaarde voor de Software Factory-overstap. De volgorde is: eerst
dit, dan pas de Software Factory aanpassen.

## Waarom dit nodig is

Vandaag draait de deterministische verificatie van een project in de `agentworker` van Software
Factory: die leest `.factory/verification.yaml` uit de story-workspace, draait de commando's, en
Software Factory valideert per commando de `id`, `argv`, `exitCode` en outputgrens.

Met de overstap verdwijnt die worker. Software Factory heeft dan geen checkout meer en dus geen
plek om die commando's te draaien. De twee alternatieven vielen af:

- **De verificatie aan de PR-CI overlaten.** Dan ziet een reviewer een gebroken unit test pas nadat
  de pipeline klaar is, en moet de hele keten terug naar de developer. Bovendien verdwijnt het
  bewijs per commando.
- **Een generieke shell-executor in Agent Runtime.** Ongewenst: dat geeft consumers willekeurige
  commando-uitvoering.

Wat hier wordt gespecificeerd is bewust géén generieke executor. De Runtime draait uitsluitend de
commandoset die in de uitgecheckte repository zelf staat, in dezelfde container, zonder dat de
consumer commando's kan meesturen.

## Gewenst jobverloop

Voor een muterende job (`REPOSITORY_WORK`, `publicationMode=COMMIT_AND_PUSH`) met verificatie aan:

1. De worker maakt de verse checkout van de opgegeven remote branch en legt branch en begin-HEAD
   vast. Ongewijzigd ten opzichte van nu.
2. De agent draait in de container. Zijn prompt draagt hem expliciet op de tests zelf te draaien en
   groen achter te laten.
3. De worker controleert dat de agent geen Gitmetadata heeft gemuteerd. Ongewijzigd.
4. Is de worktreediff leeg, dan is de uitkomst `NO_CHANGES`, wordt er niet geverifieerd en bevat
   het jobresultaat geen `verificationResult`.
5. Anders draait de worker de verificatiecommando's uit `.factory/verification.yaml`.
6. **Rood** en er zijn nog herstelrondes over: de worker schrijft de faalde commando's met hun
   uitvoer naar de container-input en start dezelfde agent opnieuw op dezelfde worktree. Daarna
   terug naar stap 3.
7. **Rood** en de rondes zijn op: er wordt niets gecommit en niets gepusht. De job eindigt in een
   toestand die de consumer als mislukt kan herkennen, mét het bewijs.
8. **Groen**: commit en push zoals nu, via de bestaande publicatie-intentie met bevestiging.

Deze capability is uitsluitend bedoeld voor AI-runs die repositorybestanden mogen veranderen:
`REPOSITORY_WORK` met `publicationMode=COMMIT_AND_PUSH`. Read-only jobs (`APPLICATION_WORK`,
`publicationMode=NONE`) verifiëren niet en krijgen geen `verificationResult`. Zij mogen zelf tests
draaien als onderdeel van hun opdracht; dat verandert niet.

## Bron van de commando's

De worker leest `.factory/verification.yaml` uit de checkout die hij toch al heeft. De consumer
stuurt geen commando's mee en kan ze niet overschrijven.

Het bestaande formaat blijft ongewijzigd:

```yaml
version: 1
commands:
  - id: backend-maven-verify
    pathPrefixes: [backend/, pom.xml]
    argv: [mvn, -B, --no-transfer-progress, clean, verify]
    workingDirectory: .
    timeoutSeconds: 1800
```

Regels:

- `argv` wordt als losse argumenten aan `ProcessBuilder` gegeven; er wordt nooit een shellstring
  gebouwd en er is geen shell-expansie.
- `workingDirectory` moet binnen de worktree liggen; `..` en absolute paden worden geweigerd.
- `timeoutSeconds` geldt per commando; daarnaast blijft de bestaande `executionTimeoutSeconds` van
  de job de bovengrens over alle rondes samen.
- `pathPrefixes` bepaalt of een commando relevant is voor de gemaakte wijziging. Raakt de diff geen
  enkel opgegeven pad, dan wordt het commando overgeslagen met status `SKIPPED`. Bij twijfel — geen
  prefixes opgegeven, of de diff kan niet betrouwbaar bepaald worden — draait het commando altijd.
  De diff wordt bepaald ten opzichte van de vastgelegde begin-HEAD.
- Ontbreekt het bestand terwijl verificatie is aangevraagd, dan is dat een expliciete uitkomst
  (`CONFIG_MISSING`), geen stilzwijgend succes.
- Een onbekende `version` wordt geweigerd.

## Contract

### Aanvraag

Voeg aan `CreateJobRequest` toe:

```kotlin
enum class VerificationMode { NONE, REPOSITORY_CONFIG }

data class JobVerification(
    val mode: VerificationMode = VerificationMode.NONE,
    @field:Min(0) @field:Max(5) val maxRepairAttempts: Int = 3,
    @field:Size(max = 4_000) val repairInstruction: String? = null,
)
```

met `verification: JobVerification? = null`.

Validatieregels:

- `verification` is alleen toegestaan samen met `repositoryCheckout`;
- `REPOSITORY_CONFIG` vereist `publicationMode=COMMIT_AND_PUSH`;
- `REPOSITORY_CONFIG` vereist `executionTimeoutSeconds` van minimaal 600 seconden;
- zonder `verification` blijft het gedrag exact zoals nu;
- `maxRepairAttempts=0` betekent: één keer verifiëren, geen herstelronde.

`repairInstruction` is optionele consumer-tekst die bij een herstelronde vóór de faalinformatie
wordt geplaatst. De Runtime vult zelf een neutrale standaardtekst in als het veld leeg is. De
Runtime interpreteert de inhoud niet.

### Resultaat

Voeg een Runtime-beheerd verificatieresultaat toe, naast het bestaande `repositoryResult`:

```kotlin
enum class VerificationStatus { PASSED, FAILED, SKIPPED, CONFIG_MISSING, CONFIG_INVALID, TIMEOUT }
enum class VerificationCommandStatus { PASSED, FAILED, TIMEOUT, SKIPPED }

data class VerificationCommandResult(
    val id: String,
    val argv: List<String>,
    val status: VerificationCommandStatus,
    val exitCode: Int?,
    val durationMillis: Long,
    @field:Size(max = 20_000) val outputTail: String?,
)

data class VerificationResult(
    val status: VerificationStatus,
    val configVersion: Int?,
    val agentRounds: Int,
    val commands: List<VerificationCommandResult>,
)
```

Breid `SubmitResultRequest` en `JobResultView` uit met een optioneel `verificationResult`. Net als
`repositoryResult` is dit Runtime-eigendom: het wordt niet tegen het consumer-schema gevalideerd.
Bewaar het afzonderlijk en duurzaam in een nullable kolom `verification_result_json` op
`runtime_v2_job` via een nieuwe Flyway-migratie. Neem het als getypeerd veld op in `StoredV2Job`.

`verificationResult` is uitsluitend toegestaan wanneer de aanvraag
`REPOSITORY_WORK`/`COMMIT_AND_PUSH` met `REPOSITORY_CONFIG` betreft en de worktreediff niet leeg is.
Bij alle andere jobs en bij `NO_CHANGES` blijft het veld afwezig.

`configVersion` is nullable omdat een ontbrekende of syntactisch onleesbare configuratie geen
betrouwbare versie heeft. `commands` mag leeg zijn bij `CONFIG_MISSING`, `CONFIG_INVALID` of een
timeout voordat het eerste commando kon starten.

`agentRounds` is 1 wanneer de agent in één keer groen was.

`outputTail` is begrensd en gaat door dezelfde redactie als de bestaande logs en artifacts: geen
waarden uit `/job/secrets/secrets.env`, geen repository-URL's met credentials.

### Opslag en weergave

Sla het gevalideerde AI-resultaat en `verificationResult` in één servertransactie op. Het
consumerschema valideert alleen het AI-resultaat; de server valideert zelf de structuur en
consistentie van `verificationResult`. De bestaande result-submit blijft de ene atomische
afrondingscall; er volgt dus geen losse `fail`-call na het indienen van bewijs.

De server leidt de terminale jobuitkomst af uit het verificatieresultaat:

| `verificationResult.status` | Jobstatus | Foutcode |
| --- | --- | --- |
| `PASSED` of `SKIPPED` | `SUCCEEDED` | geen |
| `FAILED` | `FAILED` | `VERIFICATION_FAILED` |
| `CONFIG_MISSING` | `FAILED` | `VERIFICATION_CONFIG_MISSING` |
| `CONFIG_INVALID` | `FAILED` | `VERIFICATION_CONFIG_INVALID` |
| `TIMEOUT` | `FAILED` | `VERIFICATION_TIMEOUT` |

Een job zonder `verificationResult` volgt de bestaande afrondingsregels. Een muterende
verificatiejob met een niet-lege diff mag niet zonder `verificationResult` worden afgerond.

`GET /v2/jobs/{jobId}/result` retourneert het resultaat niet alleen voor `SUCCEEDED`, maar ook voor
een terminale verificatiefout wanneer zowel het gevalideerde AI-resultaat als
`verificationResult` duurzaam aanwezig zijn. Voor andere mislukte jobs zonder gevalideerd
resultaat blijft `RESULT_NOT_READY` gelden. Hierdoor kan Software Factory na rood hetzelfde
resultaatendpoint gebruiken om het agentresultaat en het verificatiebewijs op te halen.

Toon in de managementlijst minimaal verificatiestatus en `agentRounds`. Toon op het jobdetail de
volledige commandolijst met status, exitcode, duur en geredigeerde `outputTail`. Toon geen volledige
ongebegrensde procesuitvoer en geen secrets.

### Eindtoestand bij blijvend rood

Wanneer de verificatie na de laatste ronde rood is, gelden vier eisen:

1. er wordt niets gecommit en niets gepusht;
2. de consumer ontvangt zowel het gevalideerde AI-resultaat als het volledige `verificationResult`;
3. de job eindigt in een toestand die de consumer van succes kan onderscheiden;
4. de Runtime start niet automatisch een nieuwe attempt van de hele AI-job.

De job eindigt met de bestaande terminale status `FAILED` en foutcode `VERIFICATION_FAILED`. Voeg
geen aparte jobstatus toe. Het gevalideerde AI-resultaat en `verificationResult` worden atomair
opgeslagen voordat de attempt niet-retrybaar wordt afgesloten. Agent Runtime start geen nieuwe
technische attempt en er wordt niets gecommit of gepusht.

Gebruik dezelfde terminale status `FAILED` met de specifieke codes
`VERIFICATION_CONFIG_MISSING`, `VERIFICATION_CONFIG_INVALID` of `VERIFICATION_TIMEOUT` voor die
gevallen. Ook deze fouten zijn niet-retrybaar. Een commandotimeout vóór het verstrijken van de
totale jobdeadline is eerst een rode verificatie-uitkomst en mag nog aan de agent worden
teruggegeven zolang herstelrondes en tijd over zijn.

### Foutcodes

- `VERIFICATION_FAILED` — na alle rondes nog rood, niet hervatbaar.
- `VERIFICATION_CONFIG_MISSING` — verificatie gevraagd, geen bruikbare `.factory/verification.yaml`.
- `VERIFICATION_CONFIG_INVALID` — onbekende versie, ongeldige `argv`, of een `workingDirectory`
  buiten de worktree.
- `VERIFICATION_TIMEOUT` — de jobtimeout is bereikt tijdens verificatie of een herstelronde.

## Uitvoering in de container

De verificatie draait in dezelfde execution-image als de agent, op dezelfde worktree, maar in een
aparte containerrun:

- **zonder** providercredentials — de verificatie praat niet met een AI-provider;
- **met** dezelfde projectcredentials uit `/job/secrets/secrets.env` als de agent, omdat tests die
  soms nodig hebben;
- met `.git` read-only gemount, net als bij de agentrun;
- met dezelfde resourcegrenzen en dezelfde outputbegrenzing als nu.

Bij een herstelronde schrijft de worker de faalinformatie naar `/job/input/verification-failure.md`
en start de agentcontainer opnieuw op dezelfde worktree. Het bestand bevat per faalde commando de
`id`, de `argv`, de exitcode en de begrensde uitvoer, plus de `repairInstruction` wanneer die is
meegegeven. De agentprompt verwijst naar dat bestand. De agent behoudt zijn bestaande verbod op
Git-acties.

## Toolchain in het image

De verificatiecommando's van de bestaande projecten vragen minimaal Maven met JDK 17 en 21, Flutter,
Node en Python. Vandaag zit die toolchain in `Dockerfile.agent` van de Software Factory-repository,
die met deze overstap verdwijnt.

Er blijft **één breed, centraal beheerd Agent Runtime execution-image** voor alle projecten. Voeg
geen imagekeuze aan het job- of projectcontract toe en bouw geen images per project. Breid het
bestaande image uit met de toolchain die de huidige `.factory/verification.yaml`-bestanden nodig
hebben, waaronder minimaal Maven, ondersteunde JDK's, Flutter, Node, Python, Bash en de reeds
aanwezige browsertooling.

Bouw en test het image voor de architectuur van de daadwerkelijke MacBook-worker. Ontbrekende
tooling is een expliciete verificatiefout en geen reden om een projectimage dynamisch te kiezen.
Nieuwe algemeen benodigde tooling wordt gecontroleerd aan het centrale image toegevoegd.

## Timeoutmodel

De totale timeout verschilt per logische taak en wordt door Software Factory in iedere
`CreateJobRequest` meegegeven via het bestaande veld `executionTimeoutSeconds`. Software Factory
mag de waarde per project, agentrol of taakconfiguratie bepalen. Zij vertrouwt niet op een vaste
Runtime-default voor muterende repositoryjobs.

De drie begrenzingen hebben elk een eigen betekenis:

- `executionTimeoutSeconds` in het request is de harde totale deadline vanaf het claimen van de
  attempt tot en met clone, alle agentrondes, alle verificatierondes, commit, push, reconciliatie
  en resultaatopslag;
- `maxRepairAttempts` in het request bepaalt hoeveel extra agentrondes na de eerste rode
  verificatie zijn toegestaan; waarde `3` betekent maximaal vier agentruns in totaal;
- `timeoutSeconds` per commando in `.factory/verification.yaml` begrenst alleen dat ene commando.

De harde attemptdeadline wordt nooit per ronde gereset of verlengd. Verdeel de beschikbare tijd
niet vooraf gelijk over de rondes: overgeslagen commando's en snelle reparaties mogen de resterende
tijd gewoon gebruiken.

Reserveer bij verificatiejobs de laatste 300 seconden van `executionTimeoutSeconds` voor commit,
push, publicatiereconciliatie en het duurzaam opslaan van het resultaat. Accepteer daarom voor een
job met `REPOSITORY_CONFIG` minimaal `executionTimeoutSeconds=600`.

Voor iedere agentrun en ieder verificatiecommando geldt:

```text
effectieve deadline = attemptDeadline - 300 seconden
commandotimeout = min(timeoutSeconds uit verification.yaml, tijd tot effectieve deadline)
```

Start geen nieuwe agent- of verificatieronde wanneer de effectieve deadline is bereikt. Eindig dan
niet-retrybaar met `FAILED`/`VERIFICATION_TIMEOUT`, bewaar het reeds opgebouwde geredigeerde bewijs
en publiceer niets. Is de verificatie vóór de effectieve deadline groen, dan gebruikt de worker de
gereserveerde tijd voor de bestaande veilige publicatie- en reconciliatieflow. De absolute
`attemptDeadline` blijft ook tijdens die finalisatie gelden.

## Stappenplan

### Stap 1 — Vaststaande ontwerpbesluiten verwerken

1. Gebruik één breed centraal Agent Runtime execution-image; voeg geen projectimageselectie toe.
2. Gebruik bij blijvend rood `FAILED`/`VERIFICATION_FAILED`, bewaar resultaat plus bewijs en plan
   geen technische retry.
3. Bewaar `verificationResult` in `runtime_v2_job.verification_result_json` en maak het via consumer-
   en managementresultaten zichtbaar zoals hierboven beschreven.
4. Gebruik de per-request `executionTimeoutSeconds` als harde deadline over alle rondes, met de
   vastgelegde finalisatiereserve en per-commandotimeout.
5. Pas verificatie uitsluitend toe op muterende repositoryjobs met een niet-lege diff.

**Klaar wanneer:** contract, implementatie en tests geen alternatieve invulling van deze besluiten
meer toelaten.

### Stap 2 — Contract

1. Voeg `JobVerification`, `VerificationResult` en de bijbehorende enums toe aan de
   Kotlin-contracten en aan de OpenAPI 3.1-beschrijving, met voorbeelden.
2. Voeg de validatieregels toe aan de servervalidatie en de tenantpolicy.
3. Breid `SubmitResultRequest` en `JobResultView` uit met `verificationResult`.
4. Laat de server de terminale status en foutcode volgens de vaste statustabel atomair tijdens
   result-submit bepalen; gebruik geen tweede fail-call.
5. Voeg de databasekolom en de Flyway-migratie toe.
6. Werk de requestserialisatie en de bestaande contracttests bij.

**Klaar wanneer:** een job met `verification` kan worden aangemaakt en opgehaald, een job zonder
`verification` zich exact als voorheen gedraagt, en ongeldige combinaties worden geweigerd.

### Stap 3 — Configuratie lezen en commando's draaien

1. Lees en valideer `.factory/verification.yaml` uit de worktree na de agentrun.
2. Bepaal de diff ten opzichte van de vastgelegde begin-HEAD en pas de `pathPrefixes`-selectie toe,
   met "bij twijfel draaien" als regel.
3. Draai de geselecteerde commando's in de verificatiecontainer, met per-commandotimeout en
   begrensde uitvoer.
4. Bouw het `VerificationResult` op met per commando `id`, `argv`, status, exitcode, duur en
   outputstaart.
5. Pas dezelfde redactie toe als op logs en artifacts.

**Klaar wanneer:** een job met een groene repository een `PASSED`-resultaat oplevert met per
commando bewijs, en een bewust rode repository een `FAILED`-resultaat met de juiste faalde
commando's.

### Stap 4 — Herstelrondes

1. Schrijf bij rood de faalinformatie naar `/job/input/verification-failure.md`.
2. Start dezelfde agent opnieuw op dezelfde worktree, tot maximaal `maxRepairAttempts` keer.
3. Controleer na iedere ronde opnieuw dat de agent geen Gitmetadata heeft gemuteerd.
4. Tel de rondes en rapporteer ze in `agentRounds`.
5. Bewaak de jobtimeout over alle rondes samen.
6. Rapporteer voortgang per ronde via het bestaande `progress`-endpoint, zodat de consumer ziet dat
   er hersteld wordt en niet dat de job hangt.

**Klaar wanneer:** een job waarin de eerste ronde rood is en de tweede groen, netjes één commit
pusht en `agentRounds=2` rapporteert.

### Stap 5 — Publicatie koppelen aan groen

1. Voer `prepareCommit`, de publicatie-intentie, de push en de bevestiging alleen uit bij
   `PASSED` of `SKIPPED`.
2. Laat `NO_CHANGES` ongewijzigd werken: geen diff betekent geen verificatie en geen commit.
3. Publiceer niets bij `FAILED`, `CONFIG_MISSING` of `CONFIG_INVALID`.
4. Zorg dat de bestaande herstelroute na een geslaagde push ongewijzigd blijft werken.

**Klaar wanneer:** een rode job aantoonbaar niets op de remote branch achterlaat en een groene job
zich gedraagt als vandaag.

### Stap 6 — Mocks

1. Laat de mockexecutor een deterministisch `verificationResult` teruggeven.
2. Ondersteun minimaal: direct groen, rood-dan-groen binnen de rondes, blijvend rood,
   `CONFIG_MISSING` en een timeout.
3. Houd mocks verboden in productie.

**Klaar wanneer:** de consumer zijn eigen afhandeling kan testen zonder echte provider en zonder
echte repository.

### Stap 7 — Zichtbaarheid en documentatie

1. Toon in de monitor per job de verificatiestatus, het aantal agentrondes en de faalde commando's.
2. Werk `README.md`, `docs/jobs-en-uitvoering.md` en `docs/architectuur-en-beveiliging.md` bij.
3. Beschrijf expliciet dat dit geen algemene shell-executor is: de commandoset komt uitsluitend uit
   de repository, de consumer kan niets meesturen, en er is geen extra autoriteit voor de agent.
4. Werk `docs/software-factory-v2-integratieplan.md` bij, waar nu nog staat dat Agent Runtime geen
   verificatie uitvoert.

**Klaar wanneer:** de documentatie geen uitspraak meer bevat die door deze wijziging achterhaald is.

## Testscenario's

1. verificatie uit: gedrag exact als vandaag;
2. direct groen, één commit, `agentRounds=1`;
3. eerste ronde rood, tweede groen: één commit, `agentRounds=2`;
4. blijvend rood na alle rondes: geen commit, geen push, bewijs bij de consumer;
5. lege diff: `NO_CHANGES`, geen verificatie, geen commit;
6. alleen documentatie gewijzigd: het trage buildcommando wordt via `pathPrefixes` overgeslagen en
   als `SKIPPED` gerapporteerd;
7. ontbrekende of ongeldige `.factory/verification.yaml`: expliciete fout, geen stilzwijgend succes;
8. commandotimeout en jobtimeout tijdens een herstelronde;
9. de agent muteert Gitmetadata tijdens een herstelronde: dezelfde weigering als in ronde één;
10. de remote branch is tijdens de job gewijzigd: `BRANCH_CHANGED`, geen force-push, geen commit;
11. een testcommando echoot een projectcredential: de waarde staat niet in `outputTail`, logs of
    artifacts;
12. crash tussen groene verificatie en push: de bestaande reconciliatie werkt en er komt geen tweede
    commit;
13. `APPLICATION_WORK` met `publicationMode=NONE`: geen verificatie, ongewijzigd gedrag;
14. `maxRepairAttempts=0`: één verificatie, geen herstelronde.
15. twee muterende jobs met verschillende `executionTimeoutSeconds` krijgen ieder hun eigen harde
    deadline; geen van beide gebruikt een globale verificatietimeout;
16. een commandotimeout wordt begrensd door zowel de YAML-waarde als de resterende jobtijd;
17. bij blijvend rood retourneert `GET /v2/jobs/{jobId}/result` het gevalideerde AI-resultaat plus
    `verificationResult`, terwijl de job `FAILED`/`VERIFICATION_FAILED` is;
18. een andere mislukte job zonder gevalideerd resultaat houdt `RESULT_NOT_READY`;
19. een muterende job met niet-lege diff en aangevraagde verificatie kan niet zonder
    `verificationResult` worden afgerond;
20. een lege diff geeft `NO_CHANGES`, zonder `verificationResult`.

## Beveiliging

- De consumer kan geen commando's, argumenten of paden meesturen; alleen de repository bepaalt wat
  er draait.
- De verificatiecontainer krijgt geen providercredentials en geen Git-publicatiecredentials.
- `.git` blijft read-only voor zowel agent- als verificatiecontainer.
- Uitvoer wordt begrensd en geredigeerd volgens de bestaande regels.
- Het projectlokale, gitignored `secrets.env` van een targetproject wordt niet gelezen, gekopieerd,
  gemount of gewijzigd; alleen de expliciet geselecteerde `environmentKeys` van de worker worden
  aangeboden, zoals nu.

## Buiten scope

- Een algemene shell- of commando-executor voor consumers.
- Verificatie voor read-only jobs.
- Een tweede AI-agent die de verificatie beoordeelt.
- PR-, merge- of deploysemantiek in de Runtime.
- Wijzigingen aan het bestaande `repositorySnapshot`-gedrag voor andere consumers.
