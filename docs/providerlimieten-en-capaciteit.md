# Providerlimieten en capaciteit

De v2-runtime levert de bouwstenen voor langdurige capaciteitstests in HKH-admin.
HKH beheert vragen, volledige zoekflows, campagnes, maximaal één campagne per provider en
wachten/hervatten over meerdere zoekopdrachten. Agent Runtime voert de afzonderlijke jobs uit.
Provider, model en mode blijven expliciet per job gekozen; er is geen automatische fallback.

## Limietwaarnemingen lezen

Lees `GET /v2/jobs/{jobId}/events?afterSequence=...` of
`GET /v2/jobs/{jobId}/event-stream`. Een event `PROVIDER_LIMIT_OBSERVED` bevat
`attemptId` en `providerLimit`, bijvoorbeeld:

```json
{
  "eventId": "provider-observation-1",
  "kind": "SHORT_WINDOW",
  "observedAt": "2026-10-25T09:00:00Z",
  "blocked": true,
  "resetsAt": "2026-10-25T10:00:00Z",
  "providerCode": "five_hour",
  "source": "PROVIDER_STRUCTURED"
}
```

`kind` is `SHORT_WINDOW`, `WEEK`, `MONTH` of `UNKNOWN`. `resetsAt`, `retryAfterSeconds`,
`httpStatus` en `providerCode` zijn optioneel. `createdAt` van het event is de opslagtijd;
`observedAt` is het waarnemingsmoment. IDs blijven bij herlevering gelijk. De server dedupliceert
op attempt en event-ID. Er wordt geen ruwe foutbody, credential of verborgen redenering in
deze metadata opgeslagen. Events blijven tenantgebonden en na een retry beschikbaar.

De worker levert events tijdens uitvoering, onafhankelijk van resultaatvalidatie. Bij een
falen stuurt hij dezelfde waarnemingen opnieuw mee met `FailAttemptRequest.providerLimits`;
de server slaat die binnen dezelfde transactie vóór de statusovergang op. Kortdurend mislukte
eventlevering wordt opnieuw geprobeerd. Een volledige verbindingsuitval kan rapportage nog
steeds verhinderen; ontbrekend bewijs betekent niet dat er geen limiet is bereikt.
Net als usage mag late limietevidentie tot een dag na afronding/deadline append-only worden
aangeleverd met de oorspronkelijke attemptcredential. Dit geeft geen recht op late resultaten.
Late events wijzigen een reeds geplande retry niet; HKH kan wel de campagne erop pauzeren.

## Beschikbare providerinformatie

| Uitvoering | Herkenning | Beperking |
|---|---|---|
| Claude abonnement | `rate_limit_event` met status `rejected`; `five_hour` en `seven_day`-varianten met eventueel `resetsAt` | Waarschuwingen tellen niet als blokkade; onbekende vensters blijven onbekend |
| Codex abonnement | Gestructureerde `error`/`turn.failed`-envelop met limietcode of expliciete limietfout | Vrije tekst bewijst geen week-/maandperiode of exact resetmoment |
| Mistral Vibe abonnement | Vastgepinde Vibe 3.0.2-journalfouten en exportachtervang | 429 bewijst geen maandgrens; alleen expliciete periodecodes krijgen WEEK/MONTH |
| Server API | HTTP 429 of expliciete quotacode, met `Retry-After` indien beschikbaar | Rate limits en abonnementsbudgetten zijn verschillende dingen |

Assistanttekst, tooloutput en een lokale sessietokenlimiet bewijzen geen providerlimiet.
Niet alle CLI-interne retries of providerquota zijn zichtbaar in hun uitvoer. De runtime
rapporteert uitsluitend wat hij kan waarnemen. Vibe-netwerkretries staan uit om de eerste
fout aan de runtime over te laten; de schemaherstelhook blijft ingeschakeld.

## Retrygedrag en gebruik door HKH

Een korte/onbekende blokkade gebruikt de bestaande begrensde technische retrypolicy.
De volgende attempt start niet vóór de gewone back-off én het laatste bekende resetmoment
of retryadvies van die attempt. `JobView.retryNotBefore` maakt dit tijdstip zichtbaar zolang
de job op een retry wacht; bij een nieuwe attempt wordt het gewist. De container wordt beëindigd
en houdt tijdens die wachttijd geen slot bezet.

Een bevestigde blokkerende `WEEK`- of `MONTH`-waarneming voorkomt automatische technische
retries na falen. HKH kan de campagne bewaren en na reset een nieuwe job met een nieuwe
idempotency-key starten. Een bestaande idempotency-key blijft altijd dezelfde job opleveren.
Een limietevent maakt een lopende job niet direct terminal: een CLI kan zelf nog herstellen.
Annulering en fencing blijven de normale jobregels volgen.

HKH moet events al tijdens de job lezen en bij een blokkade de dispatch van andere
campagnevragen pauzeren. De runtime reserveert of blokkeert niet automatisch een provider
voor alle tenants of workers. Nog lopende vragen worden afzonderlijk gevolgd. Tel jobs,
attempts en volledige zoekopdrachten apart; een retry is geen nieuwe geslaagde zoekopdracht.

## Verbruik bij iedere jobstatus

`GET /v2/jobs/{jobId}/usage` geeft `JobUsageSummary` voor actieve, geslaagde, mislukte en
geannuleerde jobs, zonder dat een resultaat beschikbaar hoeft te zijn.
`GET /v2/jobs/{jobId}/attempts` bevat dezelfde informatie per attempt. Late usage kan totalen
nog aanvullen. Cache-read, cache-write en output blijven aparte metrics. `INPUT_TOKENS` is
bij de CLI-adapters exclusief apart gerapporteerde cachetokens.

Gebruik `usageQuality` (`COMPLETE`, `PARTIAL`, `UNAVAILABLE`, `MOCK`) bij interpretatie.
Ontbrekende registratie is geen nulverbruik en API-equivalente abonnementswaarde is geen
factuur of quotumstand. Uit cachekosten valt niet af te leiden hoe een abonnement quota telt.

## Parallelle uitvoering instellen

De standaard blijft 6 application-, 2 repository- en 1 transcriptieslot. Per klasse zijn
0–256 slots toegestaan en in totaal 1–256. Bijvoorbeeld voor een eerste vergelijking:

```dotenv
AR_WORKER_APPLICATION_SLOTS=6
AR_WORKER_REPOSITORY_SLOTS=2
AR_WORKER_TRANSCRIPTION_SLOTS=1
AR_WORKER_PROVIDER_LIMITS=openai=2,anthropic=2,mistral=2
```

De providercaps gelden per worker over alle modellen en jobklassen samen. Vendors zonder
cap kunnen alle vrije slots gebruiken. De som van caps mag groter zijn dan de totale
capaciteit; caps zijn maxima en reserveren geen slots. Voor gegarandeerde ruimte moet je
de totale capaciteit en caps passend kiezen, rekening houdend met andere jobklassen.
Registratie en management-workerdata tonen `providerConcurrencyLimits`.

De server controleert caps en totale capaciteit onder een database-lock op de worker en
claimt alleen nog beschikbare jobs onder een joblock. Ook gelijktijdige claims respecteren
zo de capaciteit. Dit zijn geen globale accountlimieten: twee workers met dezelfde login
delen mogelijk providerquota, maar hebben ieder hun eigen slotconfiguratie.

Vijftig slots worden door het contract ondersteund en gelijktijdige claims zijn getest;
vijftig echte containers zijn daarmee niet op snelheid of resourceverbruik gevalideerd.
Iedere container blijft maximaal 8 GB/4 CPU's begrensd. Meet eerst CPU, geheugen, schijf,
wachttijden en providerdoorvoer op 1/2/4/8/16, daarna eventueel 32/50. Er is geen automatische
verhoging van bestaande workerinstellingen.

## Limieten functioneel testen zonder providergebruik

Buiten productie kan `CreateMockFixtureRequest.providerLimits` synthetische waarnemingen
bevatten naast het bestaande resultaat of de foutcode. De mockexecutor publiceert dezelfde
getypeerde events op iedere mockattempt en vervangt `observedAt` door het uitvoeringsmoment.
Zo kan HKH korte, lange en onbekende blokkades en campagneherstel testen met de bestaande
test-controlcredential en `MOCK`-jobs, zonder providerkeys of productietoegang. De fixture
bepaalt de jobuitkomst; limietmetadata alleen maakt een mock niet automatisch mislukt.
In productie blijven zowel de test-controlroutes als MOCK-uitvoering uitgeschakeld.

## Uitrollen en Mistral gebruiken

Rol eerst de server met migratie V12 uit, daarna het execution-image en de worker. De nieuwe
workerregistratie bevat extra contractvelden die een oude server nog niet accepteert.
Oude workers blijven op de nieuwe server werken, met de bestaande totale slotcapaciteit.

De bestaande Mistral-integratie biedt `mistral/glm-5-3/SUBSCRIPTION` alleen voor HKH
`APPLICATION_WORK/STRUCTURED_GENERATION`. Configureer de abonnementskey en modelallowlist
volgens de README. Een geschikte worker maakt de combinatie zichtbaar in de HKH-catalogus.
Andere Mistral-modellen, repositorywerk en API-overloop worden hiermee niet toegevoegd.
De wijzigingen hier starten geen betaalde proef of productie-login.
