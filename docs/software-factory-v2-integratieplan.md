# Agent Runtime — prerequisite voor Software Factory v2

Status: uitgevoerd in Agent Runtime op 2026-09-10

Peildatum: 2026-09-10

Doelrepository: `agent-runtime`

## Doel van dit document

Dit document beschrijft alle wijzigingen die in Agent Runtime nodig zijn voordat Software Factory
v2 de Runtime kan gebruiken voor werk aan Git-repositories. Het document is zelfstandig leesbaar.
Een implementerende agent hoeft de voorafgaande ontwerpgesprekken niet te kennen.

De bredere Software Factory-migratie staat in
`../../softwarefactory/docs/software-factory-v2/stappenplan.md`. Dat document is aanvullende context,
maar de eisen aan Agent Runtime staan volledig in dit document.

## Besloten eindtoestand

De topologie blijft als volgt:

```text
Software Factory-backend op OpenShift
                 |
                 | Agent Runtime /v2-jobs
                 v
Agent Runtime-server op OpenShift
                 ^
                 | uitgaande HTTPS-long-polling
                 |
Agent Runtime-worker op een MacBook
                 |
                 | tijdelijke Dockercontainer en tijdelijke Git-clone
                 v
            Codex of Claude
```

De MacBook-worker blijft dus bestaan. De eis dat Software Factory niets meer lokaal hoeft te
draaien, geldt niet voor Agent Runtime. Er komt geen extra Software Factory-worker op de laptop.

Voor iedere Software Factory-story geldt:

1. Software Factory maakt via de Git-provider-API precies één remote storybranch aan vanaf de
   geconfigureerde base branch, normaal `main`.
2. Software Factory geeft alleen een geregistreerde repositoryalias en de bestaande storybranch
   door aan Agent Runtime.
3. Iedere Runtime-job maakt een verse tijdelijke clone en checkt de actuele storybranch uit.
4. De AI-agent wijzigt bestanden en voert eventueel tests uit, maar muteert Gitmetadata niet.
5. Alleen de Runtime-worker commit en pusht geldige wijzigingen naar dezelfde storybranch.
6. Software Factory maakt na de eerste succesvolle push precies één pull request voor de story.
7. Alle volgende agents halen opnieuw dezelfde remote branch op. Er wordt geen filesystem tussen
   jobs gedeeld.
8. Software Factory gebruikt de ene PR voor CI-verificatie, previewomgevingen en de uiteindelijke
   merge naar de base branch.

De remote storybranch is het enige overdrachtspunt voor repositorycode tussen agentjobs. Er is geen
netwerkschijf, gedeelde checkout, permanente worktree of checkout in Software Factory.

## Eigenaarschap

| Onderdeel | Eigenaar |
| --- | --- |
| Story, rol, workflow en volgorde van agents | Software Factory |
| Base branch en naam van de storybranch | Software Factory |
| Remote storybranch aanmaken | Software Factory |
| Eén PR per story aanmaken, volgen en mergen | Software Factory |
| CI-status en preview aan de story koppelen | Software Factory |
| Runtime-job, attempts, leases, retries en provideruitvoering | Agent Runtime-server |
| Tijdelijke clone, checkout en Git-publicatie | Agent Runtime-worker |
| Bronbestanden wijzigen en tests uitvoeren | AI-agent in de execution-container |
| Commit en normale push naar de storybranch | Agent Runtime-worker |

Agent Runtime kent geen story-, approval-, PR-, merge-, preview- of deploysemantiek. Software
Factory kent geen providercredentials, Dockeruitvoering of lokale repositoryworkspace.

## Voor implementatie geconstateerde afwijkingen

De oorspronkelijke v2-implementatie voldeed nog niet aan dit protocol:

- `RepositoryRequest` bevat `baseBranch`, `branchHint` en `publish`;
- `V2Worker` checkt de base branch uit en maakt altijd `agent-runtime/<job-id>`;
- `V2Worker` probeert met `gh pr create` zelf een PR te maken;
- iedere repositoryjob krijgt daardoor een nieuwe branch en mogelijk een nieuwe PR;
- een `APPLICATION_WORK`-job kan alleen een publieke detached snapshot op exacte commit-SHA
  krijgen en geen geregistreerde actuele branch;
- het gestructureerde JSON-resultaat van de AI wordt bij `REPOSITORY_WORK` vervangen door alleen
  Gitmetadata;
- geen wijzigingen geldt nu als `NO_REPOSITORY_CHANGES`, terwijl dit voor bijvoorbeeld een
  documentatie-agent een geldige uitkomst kan zijn;
- de agent krijgt geen Gitcredentials, maar de volledige worktree inclusief `.git` is technisch
  schrijfbaar gemount;
- de server routeert v2-jobs niet op de repositoryaliases die een worker daadwerkelijk kent;
- een crash nadat een push is gelukt maar voordat het resultaat is bevestigd, kan tot een
  onduidelijke retry leiden.

De relevante code staat hoofdzakelijk in:

- `agent-runtime-contracts/src/main/kotlin/nl/vdzon/agentruntime/contracts/v2/V2Contracts.kt`;
- `agent-runtime-contracts/src/main/resources/openapi/agent-runtime-v2.yaml`;
- `agent-runtime-server/src/main/kotlin/nl/vdzon/agentruntime/server/v2/V2Jobs.kt`;
- `agent-runtime-server/src/main/kotlin/nl/vdzon/agentruntime/server/v2/V2Workers.kt`;
- `agent-runtime-server/src/main/kotlin/nl/vdzon/agentruntime/server/v2/V2Controllers.kt`;
- `agent-runtime-server/src/main/resources/db/migration/`;
- `agent-runtime-worker/src/main/kotlin/nl/vdzon/agentruntime/worker/V2Worker.kt`;
- `agent-runtime-server/src/test/kotlin/nl/vdzon/agentruntime/server/V2IntegrationTest.kt`;
- `agent-runtime-worker/src/test/kotlin/nl/vdzon/agentruntime/worker/WorkerSupportTest.kt`.

## Scope en compatibiliteit

Deze wijziging richt zich op `/v2`. Het bestaande v1-contract hoeft voor deze prerequisite niet te
worden uitgebreid.

De huidige `REPOSITORY_WORK`-semantiek wordt nog niet door een productieconsumer gebruikt. Zij mag
in v2 incompatibel worden vervangen; er hoeft geen overgangsmodus te blijven die per job een
branch en PR maakt.

`repositorySnapshot(url, commitSha)` wordt wel door Product Factory gebruikt en moet blijven
werken. Het nieuwe branchmechanisme komt ernaast en mag bestaand snapshotgedrag niet veranderen.

## Stap 1 — Maak het v2-repositorycontract branchgericht

Vervang `RepositoryRequest` voor v2 door een expliciete checkoutbeschrijving. Aanbevolen contract:

```kotlin
enum class RepositoryPublicationMode {
    NONE,
    COMMIT_AND_PUSH,
}

data class RepositoryCheckout(
    val alias: String,
    val branch: String,
    val publicationMode: RepositoryPublicationMode,
)
```

Voeg `repositoryCheckout: RepositoryCheckout?` toe aan `CreateJobRequest` en verwijder daar de
oude v2-`repositoryRequest`. Gebruik geen `baseBranch`, `branchHint`, PR-vlag of door Software
Factory aangeleverde commit-SHA voor branchcoördinatie.

Pas de validatieregels als volgt aan:

- `repositorySnapshot` en `repositoryCheckout` zijn onderling exclusief;
- `REPOSITORY_WORK` vereist `taskType=REPOSITORY_AGENT`, een `repositoryCheckout` en
  `publicationMode=COMMIT_AND_PUSH`;
- `APPLICATION_WORK` mag een `repositoryCheckout` hebben wanneer
  `taskType=REPOSITORY_AGENT` en `publicationMode=NONE`;
- `APPLICATION_WORK` zonder branchcontext en bestaand `repositorySnapshot` blijven werken zoals
  nu;
- alleen de tenant `software-factory` mag in eerste instantie `repositoryCheckout` gebruiken;
- de alias moet in de server-side allowlist van die tenant staan;
- valideer alias en branch strikt en weiger lege waarden, control characters, `..`, reflogsyntax,
  een voorloopstreep en andere namen die `git check-ref-format --branch` niet accepteert;
- geef branchnamen als afzonderlijk argument aan `ProcessBuilder`; bouw nooit een shellstring.

Voorbeeld van een muterende developer- of documenterjob:

```json
{
  "idempotencyKey": "story-SF-123-development-1",
  "jobKind": "REPOSITORY_WORK",
  "taskType": "REPOSITORY_AGENT",
  "execution": {
    "vendorId": "openai",
    "model": "gpt-5.6-sol",
    "mode": "SUBSCRIPTION"
  },
  "input": {
    "instruction": "Volledige zelfstandige opdracht voor deze agentrol.",
    "objects": []
  },
  "output": {
    "resultSchema": {
      "type": "object",
      "required": ["status", "summary"],
      "properties": {
        "status": {"type": "string"},
        "summary": {"type": "string"}
      }
    },
    "artifacts": []
  },
  "repositoryCheckout": {
    "alias": "pvdd",
    "branch": "software-factory/SF-123",
    "publicationMode": "COMMIT_AND_PUSH"
  },
  "environmentKeys": [],
  "executionTimeoutSeconds": 3600
}
```

Voor reviewer, tester en auditor gebruikt Software Factory `APPLICATION_WORK`,
`taskType=REPOSITORY_AGENT` en dezelfde structuur met `publicationMode=NONE`. `NONE` betekent geen
duurzame repositorypublicatie; de tijdelijke worktree mag wel schrijfbaar zijn voor build- en
testoutput.

Werk tegelijk bij:

- Kotlin-contracttypen;
- OpenAPI 3.1;
- servervalidatie en tenantpolicy;
- requestserialisatie in de database;
- mocks en contracttests;
- voorbeelden en documentatie.

## Stap 2 — Maak repositoryaliasbeschikbaarheid onderdeel van workerselectie

De worker leest aliases nu al uit `AR_REPOSITORY_<ALIAS>_URL`, maar de v2-server weet niet welke
worker welke alias kent. Voeg aan `WorkerRegistrationRequest` een begrensde set
`availableRepositoryAliases` toe en bewaar deze in `runtime_v2_worker` via een nieuwe Flyway-
migratie.

De worker registreert exact de keys uit `WorkerConfig.repositoryAliases`. De server mag een job
met `repositoryCheckout` alleen laten claimen door een online worker die:

- het gevraagde vendor/model/mode/tasktype aanbiedt;
- alle gevraagde environmentkeys aanbiedt;
- de gevraagde repositoryalias aanbiedt.

Neem de aliases ook op in de workerbeheerweergave, zodat ontbrekende configuratie zichtbaar is.
Een consumerendpoint voor aliasbeschikbaarheid is wenselijk voor de Software Factory-preflight,
analoog aan `/v2/execution-options` en `/v2/environment-keys`. Het endpoint geeft alleen namen en
beschikbaarheid terug, nooit repository-URL's of credentials.

De server-side tenantallowlist en de workeradvertentie hebben verschillende doelen:

- de allowlist bepaalt wat Software Factory mag aanvragen;
- de workeradvertentie bepaalt waar de job uitvoerbaar is.

Configureer uiteindelijk op de MacBook-worker een alias voor iedere door Software Factory beheerde
repository. Repository-URL's blijven uitsluitend in het gitignored `properties.env` van de worker.

## Stap 3 — Checkout exact de bestaande remote storybranch

Wijzig `V2Worker.prepare` voor `repositoryCheckout`:

1. Vertaal de alias via `WorkerConfig.repositoryAliases` naar de lokale URL.
2. Maak onder de bestaande per-attempt workroot een verse clone.
3. Haal exact de opgegeven remote branch op.
4. Check die branch lokaal uit zonder een nieuwe branchnaam te verzinnen.
5. Controleer dat upstream exact `origin/<branch>` is.
6. Leg alias, branch en de opgehaalde HEAD vast vóór de agent start.
7. Fail niet-retryable met `REMOTE_BRANCH_NOT_FOUND` wanneer de branch niet bestaat.
8. Verwijder de volledige tijdelijke attemptdirectory altijd na afronding.

Een verse `git clone --branch <branch> --single-branch` bevat al de actuele remote stand. Een extra
`pull --ff-only` mag als expliciete controle worden uitgevoerd. Gebruik nooit een lokale checkout
uit `/Users/.../git` en verwacht geen directory die door Software Factory is gemount.

De Runtime maakt in deze flow nooit:

- `agent-runtime/<job-id>`;
- een andere jobbranch;
- de storybranch zelf;
- een PR.

Wanneer de storybranch nog niet bestaat, is dat een orchestratiefout van Software Factory en geen
reden voor Runtime om stilzwijgend vanaf `main` verder te gaan.

## Stap 4 — Dwing af dat de AI-agent geen Gitmetadata muteert

Gitpublicatiecredentials blijven buiten de execution-container. Voeg daarnaast een technische
grens toe rond `.git`:

- mount de worktree op `/work` zoals nu;
- mount de bijbehorende `.git`-directory genest en read-only op `/work/.git`;
- zet voor read-only Gitcommando's zo nodig `GIT_OPTIONAL_LOCKS=0`;
- mount nooit repository-URL's met credentials, SSH-private keys, `properties.env`, GitHub CLI-
  credentials of workercredentials in de container;
- houd `status`, `diff`, `log`, `show` en vergelijkbare read-only Gitinspectie bruikbaar.

Leg vóór de containerstart minimaal vast:

- huidige branch;
- `HEAD`;
- refs en relevante Gitconfiguratie;
- remote-URL in een intern geredigeerde vorm.

Controleer na de agentuitvoering dat branch, HEAD, refs en Gitconfig niet zijn gewijzigd. Weiger de
publicatie met `GIT_METADATA_MUTATED` wanneer deze postconditie faalt. Vertrouw hiervoor niet alleen
op de prompt. De prompt moet nog steeds expliciet zeggen dat de agent geen checkout, branch,
commit, push, PR, merge of credentialinspectie uitvoert.

De agent mag gewone bestanden in `/work` wijzigen. Bij `publicationMode=NONE` worden die
wijzigingen na het uitlezen van resultaat en artifacts weggegooid. Dit maakt builds en tests
mogelijk zonder een duurzame repositorymutatie.

## Stap 5 — Laat alleen de worker naar dezelfde branch publiceren

Behoud de bestaande controles op:

- path traversal;
- symlinks;
- bekende geheime bestandsnamen;
- maximale bestandsgrootte;
- geselecteerde gevoelige credentialwaarden in output en artifacts.

Voer bij `COMMIT_AND_PUSH` daarna uit:

1. Bepaal de worktreediff ten opzichte van de vastgelegde begin-HEAD.
2. Accepteer een lege diff als succesvolle uitkomst met publicatiestatus `NO_CHANGES`.
3. Stage uitsluitend na alle veiligheidscontroles.
4. Maak bij een niet-lege diff één workercommit op de reeds uitgecheckte storybranch.
5. Voeg een traceerbare trailer toe, bijvoorbeeld `Agent-Runtime-Job: <job-id>`.
6. Push met een normale, niet-geforceerde push naar exact `refs/heads/<storybranch>`.
7. Open geen PR en installeer geen PR-logica in de Runtime.

Een lege diff kan correct zijn, bijvoorbeeld wanneer een documentatie-agent concludeert dat geen
documentatie geraakt is. Agent Runtime rapporteert de technische uitkomst. Software Factory
beslist op basis van rol en gestructureerd resultaat of `NO_CHANGES` domeinmatig is toegestaan.

Als de remote branch tijdens de agentuitvoering is gewijzigd, moet de normale push worden
geweigerd. Classificeer dit herkenbaar als `BRANCH_CHANGED` en force-push nooit. Software Factory
kan vervolgens een nieuwe logische agentjob starten die de nieuwste branch opnieuw ophaalt. Een
Gitconflict wordt niet automatisch opgelost buiten een expliciete AI-opdracht.

Software Factory serialiseert muterende jobs per story. De Runtimebescherming tegen een
non-fast-forward push blijft desondanks verplicht voor menselijke wijzigingen, configuratiefouten
en toekomstige meerdere workers.

## Stap 6 — Bewaar AI-output en repositorybewijs afzonderlijk

Het JSON-resultaat van de agent mag niet door Gitmetadata worden vervangen. Lees en valideer
`/job/output/result.json` voor `REPOSITORY_WORK` op dezelfde manier als voor gestructureerde jobs.

Voeg een Runtime-beheerd `RepositoryResult` toe, bijvoorbeeld:

```kotlin
enum class RepositoryPublicationStatus {
    NONE,
    NO_CHANGES,
    PUSHED,
}

data class RepositoryResult(
    val alias: String,
    val branch: String,
    val checkoutCommitSha: String,
    val publicationStatus: RepositoryPublicationStatus,
    val commitSha: String? = null,
    val diffStat: String? = null,
)
```

Breid `SubmitResultRequest` en `JobResultView` uit met een optioneel Runtime-beheerd
`repositoryResult`. De publieke respons heeft dan deze vorm:

```json
{
  "jobId": "...",
  "result": {
    "status": "completed",
    "summary": "De wijziging is uitgevoerd.",
    "questions": []
  },
  "repositoryResult": {
    "alias": "pvdd",
    "branch": "software-factory/SF-123",
    "checkoutCommitSha": "1111111111111111111111111111111111111111",
    "publicationStatus": "PUSHED",
    "commitSha": "2222222222222222222222222222222222222222",
    "diffStat": "2 files changed, 18 insertions(+), 3 deletions(-)"
  },
  "artifacts": [],
  "usageSummary": {},
  "completedAt": "2026-09-10T12:00:00Z"
}
```

De server valideert alleen `result` tegen het door de consumer aangeleverde JSON-schema.
`repositoryResult` is Runtime-eigendom en wordt afzonderlijk gevalideerd en opgeslagen, bij
voorkeur in een nieuwe kolom `repository_result_json`. Controleer onder andere:

- alias en branch komen overeen met de aanvraag;
- `NONE` hoort bij read-only publicatie;
- `NO_CHANGES` heeft geen nieuwe commit;
- `PUSHED` heeft een geldige volledige commit-SHA;
- repositorymetadata is verboden voor een job zonder repositorycheckout.

De SHA's zijn bewijs- en herstelmetadata. Software Factory geeft ze niet door als coördinatie voor
de volgende agent; de volgende agent krijgt opnieuw alleen alias en branch. Software Factory mag
`checkoutCommitSha` wel opslaan om review- en testbewijs ongeldig te maken na een latere push.

Pas ook managementresponses en de monitor aan zodat alias, branch, opgehaalde commit en eventuele
publicatie zichtbaar zijn zonder geheime remote-URL.

## Stap 7 — Maak push en resultaatbevestiging herstelbaar

Een Gitpush is een extern effect en kan al gelukt zijn wanneer de worker crasht of de HTTP-respons
naar de Runtime-server verloren gaat. Een technische retry mag dan niet blind een tweede commit
produceren.

Implementeer een duurzame publicatie-intentie en reconciliatie. De precieze interne API mag tijdens
implementatie worden gekozen, maar de volgende eigenschappen zijn verplicht:

1. De logische Runtime-job-ID staat in de committrailer.
2. De server kan vóór de push duurzaam vastleggen welk gevalideerd agentresultaat en welke lokale
   commit bij de publicatie-intentie horen.
3. Na de push bevestigt de worker de publicatie en completeert de server de job atomair met het
   resultaat en `repositoryResult`.
4. Bij herstel controleert de worker de remote storybranch op de bedoelde commit of jobtrailer.
5. Staat de commit al remote, dan wordt de bestaande intentie afgerond zonder nieuwe AI-run of
   tweede commit.
6. Staat de commit niet remote, dan mag een nieuwe attempt veilig opnieuw beginnen vanaf de
   actuele branch.
7. Een andere remote head of ambigu resultaat blokkeert met een herkenbare fout; er wordt niet
   gegokt en nooit geforceerd.

Een mogelijke uitwerking is een nieuwe `runtime_v2_repository_publication`-tabel met job-ID,
attempt-ID, alias, branch, begin-HEAD, bedoelde commit, gevalideerd result JSON, status
`PREPARED|PUSHED|FINALIZED` en timestamps. Koppel iedere mutatie aan het bestaande fencing token.
Zorg dat attemptrecovery een `PREPARED` of `PUSHED` record eerst reconcileert voordat een nieuwe
agentuitvoering wordt gestart.

Deze stap is pas klaar wanneer een test de worker precies na een succesvolle remote push kan
onderbreken en hervatten zonder tweede commit.

## Stap 8 — Ondersteun Runtime-mocks

Software Factory-acceptatie gebruikt `MOCK` en heeft bewust geen verbinding met een laptopworker.
Breid de v2-mockfixture daarom uit met een optioneel `repositoryResult` of laat de mockexecutor een
deterministisch repositoryresultaat maken dat overeenkomt met de aanvraag.

Een gemockte `REPOSITORY_WORK`-job:

- voert geen echte Gitactie uit;
- retourneert wel dezelfde publieke resultaatvorm;
- kan `NO_CHANGES`, `PUSHED` en relevante foutscenario's simuleren;
- gebruikt syntactisch geldige vaste test-SHA's;
- blijft in productie verboden.

Dit is nodig om in Software Factory herstel na push, stale reviewbewijs, no-change en foutafhandeling
zonder echte provider of repository te testen.

## Stap 9 — Werk configuratie en documentatie bij

Werk minimaal bij:

- `README.md`;
- `docs/jobs-en-uitvoering.md`;
- `docs/architectuur-en-beveiliging.md`;
- `properties.worker.env.example`;
- deployment-/serverconfiguratie voor de Software Factory-repositoryallowlist;
- de OpenAPI-beschrijvingen en voorbeelden;
- eventuele monitorlabels die nu nog een jobbranch of Runtime-PR suggereren.

Verwijder uit de v2-documentatie de bewering dat Agent Runtime per repositoryjob een branch en PR
opent. Leg expliciet vast dat de consumer een bestaande branch aanlevert en eigenaar blijft van de
PR.

Documenteer voor productie hoe iedere repositoryalias op de worker wordt ingesteld, zonder echte
URL's met credentials in Git te zetten. Het bestaande `properties.env` blijft gitignored en mode
`0600`.

## Secrets en credentials

Het projectlokale secretsmechanisme verandert niet.

In de lokale ontwikkelcheckout van targetrepositories kan een gitignored `secrets.env` staan. Dat
bestand wordt door de gebruiker beheerd en is niet aanwezig in een verse remote clone. Agent
Runtime en Software Factory mogen dit lokale bestand niet:

- lezen;
- kopiëren;
- synchroniseren;
- mounten;
- aanpassen;
- alsnog committen.

Een reeds bestaand versleuteld bestand dat wél is gecommit, blijft een normaal repositorybestand.
Deze prerequisite voegt geen decryptie of nieuw secretbeheer toe.

Gitcredentials zijn workerinterne configuratie. Providercredentials worden alleen volgens de
bestaande geïsoleerde providerflow aangeboden. Projectcredentials uit `project-credentials.env`
blijven per job expliciet geselecteerd en veranderen door dit plan niet.

## Verificatiestrategie

Voeg unit-, integratie- en end-to-endtests toe. Gebruik een tijdelijke echte bare Gitrepository
voor worker-Gittests; raak geen productierepository aan.

Minimale contract- en servertests:

1. `REPOSITORY_WORK` zonder checkout wordt geweigerd.
2. `REPOSITORY_WORK` met `NONE` wordt geweigerd.
3. `APPLICATION_WORK` met checkout en `COMMIT_AND_PUSH` wordt geweigerd.
4. `APPLICATION_WORK` met checkout en `NONE` wordt geaccepteerd.
5. Snapshot en checkout tegelijk worden geweigerd.
6. Ongeldige of niet-toegestane branch en alias worden geweigerd.
7. Een worker zonder de alias kan de job niet claimen.
8. Een worker met alias, executor en environmentkeys kan de job wel claimen.
9. Agentresultaat wordt tegen het consumerschema gevalideerd en blijft ongewijzigd beschikbaar.
10. Repositorymetadata wordt afzonderlijk opgeslagen en geretourneerd.
11. Product Factory-achtig `APPLICATION_WORK` met `repositorySnapshot` blijft werken.
12. Mockfixtures leveren dezelfde repositoryresultaatvorm.

Minimale worker-Gittests:

1. Een bestaande remote storybranch wordt exact uitgecheckt.
2. Een ontbrekende branch geeft `REMOTE_BRANCH_NOT_FOUND` en maakt geen branch aan.
3. Twee opeenvolgende jobs zien elkaars wijzigingen uitsluitend via de remote branch.
4. De worker commit en pusht naar dezelfde branch en maakt geen andere branch of PR.
5. Read-only werk pusht niets, ook niet wanneer buildtools bestanden hebben gemaakt.
6. Een geldige lege diff resulteert in `NO_CHANGES`.
7. Een non-fast-forward push geeft `BRANCH_CHANGED` en force-pusht niet.
8. De agentcontainer kan `.git`, branch, HEAD, refs en config niet muteren.
9. Een gesimuleerde Gitmetadatamutatie wordt door de postconditie geweigerd.
10. Symlinks, onveilige paden, secretbestandsnamen en te grote bestanden blijven geblokkeerd.
11. Repository- en workercredentials verschijnen niet in containerconfiguratie, logs of output.
12. Een crash direct na een geslaagde push wordt zonder tweede commit gereconcileerd.
13. De tijdelijke clone wordt na succes, fout, annulering en timeout verwijderd.

Minimale ketentest voor Software Factory-gebruik:

1. Maak in een tijdelijke remote repository vanaf `main` één storybranch aan.
2. Dien een muterende developerjob in en controleer de workercommit op die branch.
3. Dien een read-only reviewerjob in en controleer dat deze exact de developercommit beoordeelt.
4. Dien een tweede muterende hersteljob in op dezelfde branch.
5. Dien een read-only testerjob in en controleer de nieuwe branchstand.
6. Dien een documenterjob in met een wijziging of `NO_CHANGES`.
7. Controleer dat er exact één storybranch bestaat en dat Runtime geen PR heeft gemaakt.
8. Laat de testconsumer één PR maken en controleer dat alle workercommits daarin zichtbaar zijn.

GitHub PR-checks blijven het onafhankelijke mergebewijs en de PR levert de preview-identiteit.
Daarnaast ondersteunt Agent Runtime nu de afzonderlijk gespecificeerde, repositorygestuurde
verificatie uit `docs/verificatie-in-de-job.md`: de worker voert uitsluitend de commandoset uit
`.factory/verification.yaml` uit, geeft rood bewijs terug aan dezelfde agent en publiceert alleen
bij groen. Dit is nadrukkelijk geen generieke shell-executor; de consumer kan geen commando's
meesturen.

## Aanbevolen implementatievolgorde

Voer de wijzigingen in deze volgorde uit:

1. Leg contractnamen, validatiematrix en foutcodes vast.
2. Wijzig Kotlin-contracten en OpenAPI samen.
3. Voeg Flyway-kolommen/tabellen voor workeraliases, repositoryresultaat en publicatieherstel toe.
4. Pas tenantpolicy, jobvalidatie, workerregistratie en claimselectie aan.
5. Implementeer checkout van een bestaande branch en verwijder jobbranch-/PR-aanmaak uit v2.
6. Implementeer de read-only `.git`-grens en postcondities.
7. Scheid agentresultaat van repositoryresultaat.
8. Implementeer normale push, `NO_CHANGES`, `BRANCH_CHANGED` en publicatiereconciliatie.
9. Breid mocks uit.
10. Voeg de volledige testmatrix toe.
11. Werk monitor en documentatie bij.
12. Configureer alleen een tijdelijke testalias en voer de echte Gitketentest uit.
13. Voeg pas na geslaagde tests productiealiases toe aan de MacBook-worker.

## Verplichte foutcodes

Gebruik stabiele, machineleesbare codes. Minimaal nodig:

| Code | Retry door Runtime | Betekenis |
| --- | --- | --- |
| `INVALID_REPOSITORY_CHECKOUT` | nee | Contractcombinatie of branchnaam is ongeldig. |
| `REPOSITORY_ALIAS_NOT_ALLOWED` | nee | Tenant mag deze alias niet gebruiken. |
| `UNKNOWN_REPOSITORY_ALIAS` | nee voor deze worker/configuratie | Worker kent de alias niet; normale routing hoort dit te voorkomen. |
| `REMOTE_BRANCH_NOT_FOUND` | nee | Software Factory heeft de branch niet correct aangemaakt. |
| `GIT_METADATA_MUTATED` | nee | Agent of tool heeft verboden Gitmetadata gewijzigd. |
| `UNSAFE_REPOSITORY_OUTPUT` | nee | Diff bevat een onveilig pad, symlink of verboden bestand. |
| `BRANCH_CHANGED` | nee binnen dezelfde technische attempt | Remote branch wijzigde; Software Factory start zo nodig een nieuwe logische job. |
| `GIT_CLONE_FAILED` | ja bij tijdelijke netwerkfout | Clone/fetch kon technisch niet worden uitgevoerd. |
| `GIT_PUSH_FAILED` | alleen bij aantoonbaar tijdelijke fout | Pushstatus is niet `BRANCH_CHANGED` maar mogelijk onbekend; eerst reconciliëren. |
| `REPOSITORY_PUBLICATION_AMBIGUOUS` | nee | Remote en duurzame intentie kunnen niet veilig worden verenigd. |

Verberg credentials en volledige geauthenticeerde remote-URL's in alle foutmeldingen.

## Acceptatiecriteria

Deze prerequisite is pas gereed wanneer aantoonbaar geldt:

- Software Factory kan uitsluitend via `/v2` een bestaande storybranch laten ophalen;
- developer en documenter kunnen via de worker naar dezelfde branch committen en pushen;
- reviewer, tester en auditor kunnen dezelfde actuele branch gebruiken zonder publicatie;
- iedere job gebruikt een verse tijdelijke clone en geen gedeeld filesystem;
- Runtime maakt geen branch of PR voor een agentrun;
- de AI-agent kan geen branch, HEAD, refs, config, commit, push of PR muteren;
- alleen de worker bezit Gitpublicatiecredentials en voert muterende Gitacties uit;
- een lege diff kan succesvol als `NO_CHANGES` worden gerapporteerd;
- het gestructureerde AI-resultaat blijft behouden naast Runtime-beheerde repositorymetadata;
- een non-fast-forward situatie resulteert nooit in een force-push;
- een crash na push veroorzaakt geen tweede commit;
- workerselection houdt rekening met repositoryaliasbeschikbaarheid;
- bestaande `repositorySnapshot`-consumers blijven werken;
- mocks ondersteunen de nieuwe resultaatvorm zonder laptopworker;
- lokale targetprojectbestanden zoals gitignored `secrets.env` worden nergens gelezen of gemount;
- `mvn -B --no-transfer-progress verify` is groen;
- documentatie en OpenAPI beschrijven exact het geïmplementeerde gedrag.

## Buiten scope

Niet in deze Agent Runtime-prerequisite opnemen:

- Software Factory-domeinmodellen of workflowbeslissingen;
- remote storybranch aanmaken;
- PR aanmaken, bijwerken of mergen;
- GitHub-previewomgevingen beheren;
- deploymentbeslissingen;
- een netwerkshare of permanente repositorycache;
- een tweede Software Factory-worker op de laptop;
- wijzigingen aan het bestaande lokale `secrets.env`-mechanisme;
- branch- of PR-compatibiliteit met het nog ongebruikte oude v2-`REPOSITORY_WORK`;
- migratie van Product Factory weg van `repositorySnapshot`;
- een algemene deterministische shell-executor, tenzij daar later apart voor wordt gekozen.

## Verificatiecommando

Gebruik Java 21 en voer na contract-, server- en workerwijzigingen minimaal uit:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn -B --no-transfer-progress verify
```

Wanneer de monitor wordt aangepast, volg ook de bestaande Flutter-build- en synchronisatiestappen
uit `CLAUDE.md` en `README.md`.
