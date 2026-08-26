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

De monitor heeft vier lijsten en een jobdetail:

- **Actieve jobs**;
- **Wachtrij**;
- **Afgeronde jobs**;
- **Workers**;
- **Jobdetail**, bereikbaar door een jobkaart te openen.

Op desktop staat de navigatie links. Onder 760 pixels gebruikt de interface een onderste
navigatiebalk. De header toont de serveromgeving, een verversactie, de loginactie en bij een fout
de laatste succesvolle momentopname.

## Joblijsten

Iedere jobkaart toont:

- technische naam en status;
- applicatie, jobsoort, provider en model;
- fase, wachtreden of voortgang wanneer aanwezig;
- de eerste 240 tekens van de prompt;
- de eerste 240 tekens van het resultaat wanneer aanwezig;
- het aantal meegegeven inputattachments;
- het aantal teruggekomen outputartifacts.

De actieve lijst bevat uitsluitend `RUNNING`. De wachtrij bevat `QUEUED` en
`WAITING_FOR_WORKER`, gesorteerd op serverprioriteit en aanmaaktijd. De server levert als reden
`klaar om te claimen`, `wacht op geschikte worker` of `uitgesteld tot retrymoment`.

Actieve jobs en de wachtrij verversen iedere vijf seconden. De verversknop haalt iedere lijst
direct opnieuw op. Een mislukte refresh laat de vorige momentopname staan en toont een
verbindingsindicator.

## Afgeronde jobs

De afgeronde lijst bevat `SUCCEEDED`, `FAILED` en `CANCELLED`, nieuwste eerst. De server levert
maximaal dertig regels per pagina. **Vorige** en **Volgende** gebruiken een opaak cursorveld.

Zoeken gebeurt server-side op job-ID, technische naam en applicatie. Zoekterm en cursor staan in de
browser-URL, zodat een refresh dezelfde pagina opent.

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
GET  /v1/management/jobs/running
GET  /v1/management/queue
GET  /v1/management/jobs/completed?search=&limit=30&cursor=
GET  /v1/management/jobs/{jobId}
GET  /v1/management/jobs/{jobId}/transcript?afterSequence=&beforeSequence=&limit=
GET  /v1/management/jobs/{jobId}/attachments/{attachmentId}
GET  /v1/management/workers
```

De server biedt daarnaast managementroutes voor resultaat, samenvatting en het opnieuw aanbieden
van een `FAILED` of `CANCELLED` job. Deze routes gebruiken dezelfde adminidentiteit.

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
flutter build web --release
cd ..
rsync -a --delete monitor-ui/build/web/ agent-runtime-server/src/main/resources/static/
```

De gesynchroniseerde bestanden worden onderdeel van de server-JAR en de servercontainer.
