# Runbook

## Geen worker online

Controleer de monitor en daarna lokaal Docker, internet en workerlogs. Een offline worker is geen
dataverlies: jobs blijven `WAITING_FOR_WORKER`. Start nooit handmatig een tweede uitvoering buiten
de queue.

## Job blijft `SUSPECTED`

De laptop kan slapen. Dezelfde worker mag binnen het herstelvenster met hetzelfde attempt en token
verdergaan. Na het herstelvenster plant de server automatisch een begrensde retry. Annuleer alleen
wanneer de domeinaanvrager het werk niet meer nodig heeft.

## Veel `FAILED`

Filter op provider, model en foutcode. `ENGINE_FAILED` wijst op de lokale CLI of credentials;
`MODEL_OUTPUT_NOT_JSON` of `MODEL_OUTPUT_SCHEMA_INVALID` op een corrigeerbare modeluitvoer,
`MODEL_OUTPUT_RETRIES_EXHAUSTED` op drie inhoudelijke afwijzingen en `POLICY_VIOLATION` op een aanvraag buiten de
serverpolicy.
De adminactie opnieuw proberen is alleen beschikbaar voor terminal werk en begint bewust een nieuwe
attemptreeks met behoud van historie.

## Database niet ready

```bash
oc get pods -n agent-runtime
oc logs deployment/postgres -n agent-runtime --tail=100
oc describe pvc/postgres-data -n agent-runtime
```

Toon nooit environmentwaarden of de inhoud van het Secret. Controleer alleen of keys bestaan.

## Server niet ready

```bash
oc logs deployment/agent-runtime-server -n agent-runtime --tail=200
oc get events -n agent-runtime --sort-by=.lastTimestamp
oc get route agent-runtime -n agent-runtime
```

Veelvoorkomende oorzaken zijn een ontbrekende secretkey, onbereikbare PostgreSQL of een mislukte
Flywaymigratie. Startup faalt bewust dicht.

## Workercredential roteren

1. Maak lokaal een nieuwe sterke waarde.
2. Werk de OpenShift-secretbron `secrets.env` bij zonder hem te tonen.
3. seal en deploy beide omgevingen.
4. werk `AR_WORKER_TOKEN` in de owner-only `properties.env` van iedere worker bij en herstart de
   worker.
5. controleer registratie en trek daarna de oude waarde definitief in.

## Restore-oefening

Maak een nieuwe lege PostgreSQL-database, controleer eerst het `.sha256`-bestand, herstel met
`pg_restore`, start dezelfde serverversie en voer de idempotentie-, jobquery- en resultaatsmokes uit.
Overschrijf nooit rechtstreeks de actieve productie-PVC tijdens een oefening.

## Credentialbestand afgewezen

De worker faalt dicht wanneer `properties.env` of `project-credentials.env` geen regulier bestand is,
een symlink is, groeps-/wereldrechten heeft, dubbele keys bevat of een verboden key gebruikt.
Controleer alleen bestandsnaam, eigenaar en mode; toon de inhoud nooit:

```bash
ls -l properties.env project-credentials.env
chmod 600 properties.env project-credentials.env
```

Projectkeys volgen `PROJECT__NAME`. Runtime-, provider- en Gitcredentials horen uitsluitend in de
worker-`properties.env` of hun eigen read-only credentialdirectory.

## Claude meldt verlopen OAuth

Voer lokaal `claude` uit en rond de login opnieuw af. Controleer daarna alleen dat `~/.claude` en
het reguliere 0600-bestand `~/.claude.json` bestaan; toon hun inhoud nooit. Herstart de worker en
voer eerst een kleine schema-begrensde smokejob uit. De worker mount voor Claude alleen deze twee
credentialbronnen read-only en geeft ze nooit door aan de Runtime-server.

## Environmentkey niet beschikbaar

Controleer `GET /v1/environment-keys?project=PROJECT` en de workerstatus in de monitor. De catalogus
toont alleen namen. `wacht op geschikte worker` betekent dat geen online worker alle gevraagde
keys én provider-/modelcapabilities heeft. Voeg nooit een waarde aan de jobrequest toe; herstel het
lokale bestand en herstart de worker zodat hij de namen opnieuw registreert.

## JSON-pogingen uitgeput

Open jobdetail en vergelijk de maximaal 25 veilige validatiefouten per outputpoging. De volledige
afgewezen kandidaat wordt niet bewaard. Los een structurele prompt-/schemafout bij de consumer op.
Een handmatige beheerretry start een nieuwe technische uitvoering en behoudt de historie; gebruik
hem niet om een deterministische contractfout eindeloos te herhalen.

## Harde uitvoeringstime-out

`EXECUTION_TIMEOUT` is server-authoritatief. Controleer attemptdeadline, providerduur en lokale
Dockerstatus. Een heartbeat of laptopresume kan de deadline niet verlengen. Een laat resultaat,
transcriptdeel of artifact hoort een fencing-/timeoutconflict te krijgen. Verhoog een timeout alleen
via een nieuwe job wanneer serverpolicy dat toestaat.

## Transcriptopslag vol of ingest mislukt

Stop met publiceren zodra de server een transcriptlimiet of conflict meldt; kap niet stilzwijgend
af. Bewaar het laatste bevestigde sequence-nummer uit de workerjournal en controleer databasevolume,
fencing en dubbele part-ID's. Reconstrueer geen ontbrekende chain-of-thought.

## Artifactupload mislukt

Controleer naam, direct regulier bestand, MIME-type, SHA-256 en limieten (25 bestanden, 5 MB per
bestand, 25 MB totaal). Finaliseer het geaccepteerde resultaat pas nadat alle uploads slagen. Een
oud attempttoken mag niet opnieuw uploaden; laat de technische retryroute de poging afhandelen.
