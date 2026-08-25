# Agent Runtime — eenvoudige beheerinterface

## Doel

Agent Runtime krijgt een kleine Flutter Web-interface waarmee een technisch beheerder actuele en
afgeronde AI-uitvoering kan volgen. Het is een operationele monitor, geen configuratieomgeving.

De interface beantwoordt vijf vragen:

1. Welke jobs draaien nu?
2. Wat zegt een draaiende agent op dit moment?
3. Welke jobs staan in de wachtrij en waarom wachten ze?
4. Welke workers zijn beschikbaar?
5. Wat waren het resultaat en de AI-conversatie van een afgeronde job?

Het klikbare UX-concept staat in [`../ux/index.html`](../ux/index.html). Het gebruikt synthetische
voorbeelddata en doet geen API-calls.

## Losse pagina's

De frontend heeft vijf pagina's:

- **Actieve jobs**;
- **Wachtrij**;
- **Afgeronde jobs**;
- **Workers**;
- **Jobdetail**, bereikbaar vanuit actieve en afgeronde jobs.

De hoofdnavigatie toont de eerste vier pagina's. Jobdetail heeft een duidelijke terugknop naar de
lijst waarvandaan de beheerder kwam. Er komen geen pagina's voor statistieken,
platformconfiguratie, mocks of uitgebreide betrouwbaarheidsscores.

## Actieve jobs

Toon uitsluitend jobs die nu door een worker worden uitgevoerd. Per job zijn zichtbaar:

- herkenbare taaknaam en verkort job-ID;
- aanvragende applicatie en `APPLICATION_WORK` of `REPOSITORY_WORK`;
- provider en model;
- worker;
- huidige fase en voortgang wanneer de worker die heeft gemeld;
- starttijd en verstreken looptijd.

Een regel opent het jobdetail. Daar wordt de AI-conversatie tijdens de uitvoering steeds aangevuld.
Als niets draait staat er: **Er worden nu geen jobs uitgevoerd.**

## Wachtrij

Toon jobs die nog niet worden uitgevoerd, in de volgorde waarin de Runtime ze naar verwachting laat
claimen. Per job zijn zichtbaar:

- herkenbare taaknaam en verkort job-ID;
- aanvragende applicatie en jobsoort;
- provider en model;
- wachttijd;
- een korte reden, bijvoorbeeld **klaar om te claimen**, **wacht op geschikte worker** of
  **uitgesteld tot retrymoment**.

De frontend berekent de volgorde en wachtreden niet zelf. Agent Runtime levert beide waarden. Er is
geen drag-and-drop en de beheerder kan vanuit deze interface geen prioriteit wijzigen. Als de
wachtrij leeg is staat er: **De wachtrij is leeg.**

## Afgeronde jobs

Deze pagina toont terminale jobs met status `SUCCEEDED`, `FAILED` of `CANCELLED`, nieuwste eerst.

- Er staan maximaal 30 jobs op één pagina.
- **Vorige** en **Volgende** gebruiken server-side cursorpaginering.
- Een zoekveld zoekt server-side op job-ID, herkenbare taaknaam, applicatie en veilige correlatie.
- Zoeken reset de pagina naar het begin.
- De URL bewaart zoekterm en paginacursor, zodat vernieuwen en delen dezelfde lijst opleveren.

Per regel zijn zichtbaar: taaknaam, job-ID, applicatie, jobsoort, provider/model, eindstatus,
eindtijd en totale looptijd. Een regel opent het jobdetail. Als niets gevonden is, maakt de tekst
onderscheid tussen **Er zijn nog geen afgeronde jobs** en **Geen jobs gevonden voor deze zoekterm**.

## Workers

Toon iedere geregistreerde worker. Per worker zijn zichtbaar:

- workernaam;
- `ONLINE`, `STALE` of `OFFLINE`;
- tijdstip van de laatste heartbeat;
- actieve en maximale capaciteit;
- ondersteunde providers, modellen en jobsoorten;
- naam van de actuele job, of **Beschikbaar**.

Credentialwaarden, lokale paden, fencing tokens en andere secrets worden nooit getoond. Als er nog
geen workers zijn geregistreerd staat er: **Er zijn geen workers geregistreerd.**

## Jobdetail

Het detail toont bovenaan taaknaam, volledig job-ID, applicatie, jobsoort, provider/model, status,
worker, starttijd en looptijd. Daaronder staan twee onderdelen.

### Resultaat

- Bij `SUCCEEDED` staat het volledige schema-gevalideerde JSON-resultaat als veilige tekst/JSON.
- Eventuele artifacts zijn met bestandsnaam en gecontroleerde download beschikbaar.
- Bij `FAILED` staat de veilige foutcode en foutmelding.
- Bij `CANCELLED` staat wie of wat annuleerde en wanneer.
- Bij een actieve job staat **Resultaat is beschikbaar zodra de job is afgerond.**

De frontend rendert resultaat en foutinhoud nooit als HTML.

### AI-conversatie

De conversatie toont, in vaste volgorde en met tijdstip:

- de volledige prompt die de Runtime aan de provideruitvoering gaf;
- alle door de provider zichtbare agenttekst;
- zichtbare toolaanroepen en geredigeerde tekstuitvoer van tools;
- eventuele JSON-correctieprompts en antwoorden;
- het laatste zichtbare providerantwoord.

Dit is het volledige **beschikbare zichtbare transcript**, niet de verborgen interne redeneerstappen
van een model. Chain-of-thought die een provider niet levert wordt niet gereconstrueerd of
opgevraagd. Secretwaarden en andere volgens Runtimebeleid gevoelige waarden worden vóór opslag
geredigeerd. De UI vermeldt redactie zichtbaar met **Waarde door Agent Runtime afgeschermd**.

Transcriptdelen hebben een oplopend sequence-nummer en zijn append-only. Bij een actieve job vraagt
de Flutter-app periodiek alleen delen na het laatst ontvangen sequence-nummer op. Nieuwe tekst
verschijnt zonder de leespositie te verplaatsen wanneer de beheerder omhoog heeft gescrold; staat
de beheerder onderaan, dan mag de weergave nieuwe tekst blijven volgen. Een label toont **Live**,
**Verbinding onderbroken** of **Afgerond**.

Een lang transcript wordt in pagina's geladen, maar de beheerder moet tot het begin en einde kunnen
lezen. De Runtime kapt transcripttekst nooit stilzwijgend af. Als een ingestelde opslaglimiet wordt
bereikt, wordt dat een expliciete technische fout en staat in het transcript tot waar opslag is
gelukt.

## Transcriptcontract

De huidige implementatie heeft alleen genormaliseerde voortgang en events. Voor het bovenstaande
detail is daarom een aparte duurzame transcriptstream nodig. Een transcriptdeel bevat minimaal:

```text
jobId
attemptId
sequence
createdAt
kind          PROMPT | AGENT_TEXT | TOOL_CALL | TOOL_OUTPUT | CORRECTION | PROVIDER_RESULT
text
redacted      true | false
```

Alleen de actuele, gefencete workerattempt mag nieuwe delen toevoegen. Een netwerkretry met hetzelfde
deel-ID of sequence-nummer maakt geen duplicaat. Transcriptdelen blijven na afronding
onveranderlijk en horen bij de attempt waarin ze zijn ontstaan; een technische retry wist het oude
transcript niet.

De provideradapter vertaalt providergebeurtenissen naar dit neutrale contract. Wanneer een provider
een bepaald soort tekst niet beschikbaar stelt, verzint de worker niets en wordt dat deel niet
getoond.

## Bediening en verversen

De lijsten hebben één algemene actie: **Verversen**. Actieve jobs en hun open transcript mogen
daarnaast automatisch verversen. Zichtbare gegevens blijven staan als een verversing mislukt; de UI
toont dan wanneer de laatste succesvolle momentopname is gemaakt.

Jobs annuleren, retries starten, mocks voorbereiden, workers bedienen en Runtime-instellingen
wijzigen vallen buiten deze interface. Consumenten blijven verantwoordelijk voor hun eigen
jobuitkomst; aanvullende technische diagnose kan via bestaande logs en metrics.

## Technische vorm

- Implementatie: Flutter Web, geleverd vanuit Agent Runtime of als onderdeel van dezelfde release.
- Gegevensbron: uitsluitend beveiligde beheerquery's van Agent Runtime, nooit rechtstreekse
  databasetoegang.
- Authenticatie: dezelfde beheerderlogin als de Runtime; jobs, resultaten en transcripties zijn
  nooit publiek.
- Cache: `index.html` en versie-informatie krijgen `no-store`; inhoudsgehashte assets mogen lang
  worden gecachet.
- Omgeving: bovenin staat duidelijk **Acceptatie** of **Productie**. Dit is een serverwaarde en geen
  schakelaar.

Minimaal benodigde beheerquery's zijn conceptueel:

```text
GET /management/jobs/running
GET /management/queue
GET /management/jobs/completed?query=&limit=30&cursor=
GET /management/jobs/{jobId}
GET /management/jobs/{jobId}/result
GET /management/jobs/{jobId}/transcript?afterSequence=&limit=
GET /management/workers
```

De definitieve versieerbare paden mogen aansluiten op het bestaande beheercontract. Iedere
lijstresponse bevat een servertijd. De transcriptquery retourneert een volgende cursor en geeft aan
of de job nog actief is.

## Vormgeving en toegankelijkheid

De UI gebruikt dezelfde rustige visuele familie als Product Factory: een donkergroene navigatie,
een lichte achtergrond, veel witruimte, witte kaarten en tekstlabels naast statuskleuren.

- Iedere pagina gebruikt de beschikbare desktopbreedte.
- Op mobiel worden tabellen kaarten en blijft de kern bruikbaar op 320 CSS-pixels.
- Alle informatie, zoekfuncties, paginering en verversen zijn met toetsenbord bereikbaar.
- Status wordt nooit alleen met kleur aangegeven.
- Transcripttekst kan worden geselecteerd en gekopieerd en blijft als platte tekst herkenbaar.
- Tekstvergroting tot 200% veroorzaakt geen verlies van functies of horizontale pagina-overflow.
- Beweging respecteert `prefers-reduced-motion`.

## Tests

De Flutter- en contractimplementatie krijgt minimaal tests voor:

- één en meerdere actieve jobs en een live aangevuld transcript;
- transcriptpolling zonder dubbele delen na een netwerkretry;
- transcript van meerdere attempts in de juiste volgorde;
- redactie vóór opslag en nooit geheime waarden in API of UI;
- provider zonder beschikbaar transcriptveld;
- geslaagd resultaat, veilige fout en geannuleerde job;
- 30 afgeronde jobs, volgende/vorige pagina en zoekactie;
- lege zoekuitkomst en lege lijsten;
- online, stale en offline workers;
- geen geschikte worker voor een wachtende job;
- een mislukte verversing met behoud van de vorige momentopname;
- productie- en acceptatielabel;
- 320 CSS-pixels en 200% tekstvergroting;
- backend onbereikbaar en verlopen beheerderssessie.

