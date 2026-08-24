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
`RESULT_SCHEMA_INVALID` op een contractverschil; `PROFILE_VIOLATION` op een ongeldige aanvraag.
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
2. Werk `secrets.env` bij zonder hem te tonen.
3. seal en deploy beide omgevingen.
4. update de lokale workerconfig en herstart de worker.
5. controleer registratie en trek daarna de oude waarde definitief in.

## Restore-oefening

Maak een nieuwe lege PostgreSQL-database, controleer eerst het `.sha256`-bestand, herstel met
`pg_restore`, start dezelfde serverversie en voer de idempotentie-, jobquery- en resultaatsmokes uit.
Overschrijf nooit rechtstreeks de actieve productie-PVC tijdens een oefening.
