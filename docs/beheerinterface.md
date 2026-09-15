# Beheerinterface

De Agent Runtime-server levert een responsive Flutter Web-monitor op `/`. De interface gebruikt
uitsluitend de beveiligde management-API en heeft geen rechtstreekse databasetoegang.

## Inloggen

Productie gebruikt Google Identity Services. De browser haalt de publieke OAuth-configuratie op,
verkrijgt een Google ID-token en wisselt dat via `POST /v1/auth/google` in voor een tijdelijk
Runtime-sessietoken. De server laat alleen geverifieerde Google-accounts uit `AR_ADMIN_EMAILS` toe.

De dialoog bevat daarnaast een ingeklapte **Inloggen met een beheertoken**-route. Deze noodroute
gebruikt `AR_ADMIN_TOKEN`. Een verlopen of ongeldig sessietoken opent de login opnieuw.

## Navigatie

De monitor heeft vier operationele lijsten, een gebruiksoverzicht en een jobdetail:

- **Actieve jobs**;
- **Wachtrij**;
- **Afgeronde jobs**;
- **Workers**;
- **Gebruik & kosten**;
- **Jobdetail**, bereikbaar door een jobkaart te openen.

Op desktop staat de navigatie links. Onder 760 pixels gebruikt de interface een onderste
navigatiebalk. De header toont de serveromgeving, een verversactie, de loginactie en bij een fout
de laatste succesvolle momentopname.

## Joblijsten

Iedere jobkaart toont:

- technische naam en status;
- applicatie, jobsoort, provider, model en de expliciete uitvoeringswijze **API (werkelijk)**,
  **SUBSCRIPTION (abonnement)** of **MOCK**;
- fase, wachtreden of voortgang wanneer aanwezig;
- de eerste 240 tekens van de prompt;
- de eerste 240 tekens van het resultaat wanneer aanwezig;
- het aantal meegegeven inputattachments;
- het aantal teruggekomen outputartifacts;
- de lokale aanmaak- en afrondtijd, de looptijd vanaf de eerste workerattempt en de kostenstatus.

Historische v1-jobs hebben doorgaans geen betrouwbare kostenregistratie. De monitor toont daarvoor
expliciet **Niet beschikbaar (v1)** en behandelt dit niet als een bedrag van nul.

De actieve lijst bevat uitsluitend `RUNNING`. De wachtrij bevat `QUEUED` en
`WAITING_FOR_WORKER`, gesorteerd op serverprioriteit en aanmaaktijd. De server levert als reden
`klaar om te claimen`, `wacht op geschikte worker` of `uitgesteld tot retrymoment`.

Actieve jobs en de wachtrij verversen iedere vijf seconden. De verversknop haalt iedere lijst
direct opnieuw op. Een mislukte refresh laat de vorige momentopname staan en toont een
verbindingsindicator.

## Afgeronde jobs

De afgeronde lijst bevat `SUCCEEDED`, `FAILED` en `CANCELLED`, nieuwste eerst. De server levert
maximaal dertig regels per pagina. **Vorige** en **Volgende** gebruiken een opaak cursorveld.

Filteren gebeurt server-side op consumer, aanmaaktijd en titel (technische naam of job-ID). De
bestaande parameter `search` blijft compatibel en zoekt ook op applicatie. Filters en cursor staan
in de browser-URL, zodat een refresh dezelfde selectie opent. `from` is inclusief en `until`
exclusief; beide gebruiken een ISO-8601-tijdstip.

## Gebruik & kosten

Het kostenoverzicht toont alle geconfigureerde en historisch aangetroffen v2-projecten, ook
zonder gebruik. Eén periodekeuze geldt voor totalen, projectranglijst, tijdlijn en modeldetails.
Snelle keuzes zijn de laatste 7, 30 of 90 dagen, deze maand en vorige maand. Een eigen periode
kan 1 tot en met 366 kalenderdagen omvatten; de einddatum is inclusief. De daggrenzen volgen
**Europe/Amsterdam**, inclusief zomer- en wintertijd.

De bronkeuze **Alles / API / Abonnement** en de projectselectie werken op dezelfde momentopname.
Projecten staan standaard op hoogste bekende verbruikswaarde; sorteren op runs of naam is ook
mogelijk. Klik op een project voor zijn tijdlijn, model-/provider-/bronuitsplitsing en runs.
Teruggaan behoudt periode en bronfilter. De tijdlijn toont dagen of maandag-gebaseerde weken;
een gedeeltelijke week bevat alleen de geselecteerde dagen. Aanwijzen, aantikken of de knoppen
Vorige/Volgende periode tonen exacte bedragen. Kleine bedragen worden als bijvoorbeeld
**< US$ 0,01** weergegeven; details bewaren zes decimalen.

Een run is een technische **uitvoering (attempt)**, inclusief retries en mislukte uitvoeringen.
Bedragen en metingen worden toegeschreven aan de startdatum van die uitvoering. Verversen kan
het bedrag van een nog draaiende uitvoering aanvullen. Lokale uitvoeringen, mocks, v1-jobs en
jobs die nog niet gestart zijn vallen buiten dit kostenoverzicht.

De drie totalen onderscheiden:

- **Totale verbruikswaarde**: bekende API-kosten plus geschatte abonnementswaarde;
- **API-kosten**: providergerapporteerde (`DIRECT`) of berekende (`CALCULATED`) kosten;
- **Abonnementswaarde**: het hypothetische API-equivalent (`API_EQUIVALENT`).

Het API-equivalent is geen werkelijk betaald bedrag, geen extra factuur en geen indicator van
resterende abonnementsruimte. Vaste abonnementen en toegerekende abonnementskosten (`ALLOCATED`)
worden niet bij deze verbruikswaarde opgeteld. Een directe providerprijs vervangt de berekende
prijs voor dezelfde meting en valuta; een directe prijs zonder meting vervangt de berekeningen
voor die uitvoering en valuta. Bedragen in verschillende valuta worden nooit opgeteld; wanneer
meerdere valuta voorkomen verschijnt een valutakeuze voor bedragen, ranglijst en grafiek.
Runaantallen blijven aantallen voor de gekozen bron en periode, onafhankelijk van de valuta.

Uitvoeringen zonder kostenregistratie krijgen **Onbekend**, niet nul. Bij deels ontbrekende
metingen of tarieven vermeldt de pagina aantallen ongeprijsde en gedeeltelijk gemeten runs.
De ranglijst en gemiddelden gebruiken de bekende bedragen. Een lege periode toont nul gebruik.
Bij een mislukte refresh blijft de vorige momentopname zichtbaar met een melding; na een nieuwe
periodekeuze worden oude bedragen verborgen totdat de passende gegevens beschikbaar zijn.

De dashboard-API leest één consistente, alleen-lezen momentopname. Kosten en metrics worden
apart opgehaald om vermenigvuldiging door joins te voorkomen. Bestaande consumers- en
usage-summaryroutes blijven beschikbaar voor compatibiliteit.

## Workers

De workerlijst toont per geregistreerde worker:

- worker-ID;
- `ONLINE`, `STALE` of `OFFLINE`;
- actieve en maximale capaciteit;
- providers en capabilities;
- de technische naam van de actuele job of **Beschikbaar**.

De managementrespons bevat geen credentialwaarden, lokale paden, fencing tokens of
providercredentials.

## Jobdetail

Jobdetail laadt `GET /v1/management/jobs/{jobId}` en toont:

- volledige jobmetadata;
- de volledige prompt;
- foutcode en veilige foutmelding wanneer de job faalde;
- het gevalideerde JSON-resultaat wanneer beschikbaar;
- technische attempts;
- duurzame outputpogingen en hun validatiefouten;
- het zichtbare transcript;
- inputattachments en outputartifacts.

De detailpagina ververst het transcript iedere drie seconden met alleen delen na het laatste
sequence-nummer. Herhaalde delen worden op `partId` ontdubbeld. De status boven het transcript is
**Live**, **Afgerond** of **Verbinding onderbroken**. Tekst is selecteerbaar en wordt nooit als HTML
gerenderd.

Transcriptdelen bevatten zichtbare prompt-, correctie- en provideruitvoer. Een geredigeerd deel
krijgt het label **Waarde door Agent Runtime afgeschermd**. Niet door de provider geleverde
modelredenering staat niet in het transcript.

## Attachments en artifacts

De detailrespons bevat voor inputattachments alleen ID, bestandsnaam, MIME-type, grootte, SHA-256
en aanmaaktijd. De UI haalt de bytes met beheerauthenticatie op via:

```text
GET /v1/management/jobs/{jobId}/attachments/{attachmentId}
```

Een geslaagd resultaat bevat dezelfde metadata voor artifacts. De UI gebruikt hiervoor de
tenant-/adminroute:

```text
GET /v1/jobs/{jobId}/artifacts/{artifactId}
```

PNG-, JPEG- en WebP-bestanden worden inline als afbeelding weergegeven. Ieder bestand behoudt een
downloadknop en toont bestandsnaam, MIME-type, leesbare grootte en SHA-256. De list- en detail-API
bevatten geen Base64 of bestandbytes.

## Management-API

De Flutter-monitor gebruikt deze routes:

```text
GET  /v1/management/environment
GET  /v2/management/jobs/running
GET  /v2/management/queue
GET  /v2/management/jobs/completed?title=&consumer=&from=&until=&limit=30&cursor=
GET  /v2/management/jobs/{jobId}
GET  /v2/management/jobs/{jobId}/transcript?afterSequence=&limit=
GET  /v2/management/jobs/{jobId}/attachments/{objectId}
GET  /v2/management/jobs/{jobId}/artifacts/{objectId}
GET  /v2/management/workers
GET  /v2/management/usage/dashboard?from=2026-09-01&through=2026-09-15&timeZone=Europe/Amsterdam
GET  /v2/management/consumers?from=&until=
GET  /v2/management/usage/summary?from=&until=&groupBy=TENANT,VENDOR,MODEL,MODE,TASK_TYPE
```

De oude `/v1/management`-routes blijven tijdelijk beschikbaar voor compatibiliteit, maar de
beheerinterface toont uitsluitend v2-jobs, v2-workers en v2-statistieken. Alle managementroutes
gebruiken dezelfde adminidentiteit.

## Vormgeving en toegankelijkheid

De interface gebruikt een lichte groen-witte navigatie, witte kaarten, donkere tekst en zichtbare
randen. Status en bestandsaanwezigheid worden met tekst en iconen aangegeven, niet alleen met
kleur. Desktop en mobiel gebruiken dezelfde informatiehiërarchie.

Widgettests controleren login, previews, attachment-/artifactaantallen, inline
afbeeldingsvoorbeelden en een jobkaart op 320 pixels met 200% tekstvergroting. `flutter analyze`,
`flutter test` en de release-webbuild draaien in CI.

## Build en inbedding

```bash
cd monitor-ui
flutter analyze
flutter test
cd ..
bash monitor-ui/tool/build_and_sync.sh
```

De gesynchroniseerde bestanden worden onderdeel van de server-JAR en de servercontainer.

De webbuild registreert geen service-worker. Een oude Flutter-service-worker krijgt via
`flutter_service_worker.js` een `no-store` kill-switch die zijn caches wist en zichzelf
deregistreert. `index.html`, bootstrap, versiegegevens en vaste assets moeten steeds revalideren;
alleen `main.<content-hash>.js` is een jaar immutable. JSON-GET's gebruiken bovendien een unieke
cacheparameter en `/v1/**` en `/v2/**` antwoorden met `no-store, private`. Een open tab controleert
iedere dertig seconden `version.json` en herlaadt bij een afwijkende build-ID na het opruimen van
eventuele oude browsercaches.
