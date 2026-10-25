# Codex CLI: uitvoering en proefopzet

Stand 25 oktober 2026. De Ollama-runtimekoppeling is geïmplementeerd; de inhoudelijke
HKH-benchmark hieronder blijft een proefvoorstel. De lokale CLI-help is gecontroleerd
voor versie 0.147.0.

## Uitvoering

Laat inference op macOS draaien voor toegang tot Apple Silicon/Metal. Codex kan
in de bestaande Linux-jobcontainer blijven. Onder Docker Desktop is
`host.docker.internal` een gebruikelijke route naar de host; controleer de feitelijke
Dockeromgeving, binding en bereikbaarheid. `localhost` binnen de container is niet
de Mac. Stel bereikbaarheid alleen open voor de benodigde verbinding.

Ollama is het eerste voorstel; LM Studio is ook een ondersteunde lokale Codex-provider.
Voorbeeld voor een native rookproef vanuit een aparte proefdirectory, met een
geïnstalleerd model en een draaiende Ollama-server met passend contextvenster:

```bash
codex exec --oss --local-provider ollama -m gpt-oss:20b \
  --skip-git-repo-check --sandbox read-only --json \
  --output-last-message result.json \
  -c 'web_search="disabled"' - < prompt.md
```

`prompt.md`, schema en bronbestanden zijn fixtures in die proefdirectory. Laat de
prompt expliciet het bronbestand en schema lezen. Dit handmatige voorbeeld valideert
de uitvoer niet automatisch; de runtime-adapter doet dat wel. Gebruik voor Ollama
geen native `--output-schema` bij agentische bestandslezing (zie de bevinding onderaan). Test
eerst een minimale bestandsleesopdracht en klein JSON-schema. Daarna het echte
HKH-schema, tool calling, streaming, resultaatbestand en tokenregistratie. Een
aanwezige CLI-vlag bewijst geen ondersteuning door elke server/modelcombinatie.
Een HTTP-200 of exitcode 0 bewijst geen correct antwoord. De runtime moet het
resultaat zelf blijven parsen en valideren.

Ollama adviseert minstens 64K context voor Codex. Codex gebruikt bij deze
integratie de Responses-API; een server met alleen `/v1/chat/completions` is niet
zonder meer voldoende. Configureer voor de container een expliciet lokaal
endpoint en controleer dat er geen cloudfallback of cloudmodel wordt gebruikt.
Leen geen persoonlijke cloudcredentials voor de lokale proef.

## Runtime-integratie

1. Maak harnas/endpoint/model onafhankelijk van de bestaande OpenAI-koppeling.
   Het v2-profiel is `vendorId: ollama`, `mode: API` en een lokaal model,
   met Codex als workerengine. De bestaande `local`/`LOCAL`-route is transcriptie.
2. Laat workerregistratie lokale capabilities melden zonder OpenAI-credentials
   te vereisen. Serverpolicy/catalogus ondersteunt Ollama structured generation voor toegestane tenants;
   HKH is standaard toegestaan.
3. Configureer lokaal endpoint, context, concurrency en model expliciet. Begin
   met één inferencejob tegelijk; modelswaps en gelijktijdige jobs kunnen extra
   geheugen en wachttijd kosten.
4. Behoud deadline, stopgedrag, leases, outputcontrole, schemaherstel en
   artifactregels. Meet prompt- én generatietijd; grote bronsets kunnen veel
   voorverwerking kosten voordat het eerste antwoordtoken verschijnt.
5. Houd lokale usage herkenbaar. OpenAI-API-kosten zijn nul voor lokale inference;
   ontbrekende usage is geen nul tokens. Eventuele hardwarekosten en API-equivalent
   moeten afzonderlijk en expliciet worden benoemd.
6. Laat HKH lokale modellen per RESEARCH/WRITER selecteren via de runtimecatalogus.
   Geef lokale modellen een passend invoerbudget; de huidige naamheuristiek doet
   dit niet automatisch.

De checkout bevat al werk aan Mistral; deze notities vervangen of wijzigen dat niet.
Controleer bij implementatie opnieuw de actuele worker- en servercode.

## Vergelijkingsproef

Begin met opgeslagen bronbestanden uit de bestaande Mistral-naspels voor schrijven
en uitwerken, zonder productie-API of productiecredentials. Houd de referentie
buiten de agentwerkmap. Voor richten zijn aanvullende fixtures nodig met vraag,
kaarten, verwacht schema en controleerbare gewenste zoekcriteria; schrijf-naspels
alleen testen de bronselectie niet.

Gebruik daarnaast de acht vragen uit de [HKH-testset](../../hkh/docs/ideas/ai-onderzoek-testset.md).
Leg voor offline vergelijkingen hun kaarten en bronnen vooraf vast uit passend
testmateriaal. Verwachte feiten moeten tegen die bronnen worden gecontroleerd:
oude referentie-antwoorden kunnen zelf fouten bevatten.

| Stap | Doel | Bewijs |
| --- | --- | --- |
| Rookproef | Technische verbinding | Bestand gelezen via tool, geldig klein JSON-resultaat, terminale status |
| Richten | Juiste selectie | Relevante namen/varianten, periode en vaste records; geldige query |
| Begrensd schrijven | Brontrouw en Nederlands | Feiten terug te vinden, bronnummer klopt, geen verzonnen details, HTML/fotoverwijzingen geldig |
| Niets gevonden | Onzekerheid | Geen verhaal uit algemene kennis verzinnen |
| Lange uitwerking | Context en stabiliteit | Geen weggevallen bronnen of vroegtijdig einde; compleet geldig resultaat |

Doe per combinatie minimaal drie herhalingen van een kleine selectie (bijvoorbeeld
Nielen, Oostrum en niets gevonden), en daarna de volledige set voor de beste twee.
Beoordeel selectie en schrijven los. Meet:

- exacte modeldigest/quantisatie, Codex- en serverversie, ingestelde context en budget;
- aangeboden bronset en werkelijk prompt-/outputtokenverbruik indien beschikbaar;
- laadtijd, tijd tot eerste token, totale tijd, geheugenpiek en swap;
- toolrondes, schemafouten, herstelpogingen, timeouts en ontbrekende antwoorden;
- onjuiste claims, ongedekte claims, gemiste kernfeiten en verkeerde bronverwijzingen;
- kwaliteit van het Nederlands, samenhang en naleving van lengte/fotoafspraken.

Een model dat schema's haalt maar personen of jaartallen verwisselt is geen
geslaagde HKH-vervanger. Kies pas na deze proef een standaard. Verander geen
productie-instellingen om een benchmark te draaien en geef automatische testers
geen productie-logincredentials; functionele integratieproeven horen op preview/test.

Bronnen: [Codex CLI](https://learn.chatgpt.com/docs/developer-commands#codex-exec),
[Ollama-integratie](https://docs.ollama.com/integrations/codex).

## Gevonden tijdens de integratie

De echte containerproef met Qwen3.5 35B op Ollama 0.40.11 toonde dat Codex' native
`--output-schema` de intermediate modelantwoorden kon begrenzen: het model gaf
syntactisch geldige JSON maar las het bronbestand niet en verzon de plaatsnaam.
Zonder die optie las het model de bron met een tool en gaf het Heemskerk correct terug.
Daarom gebruikt de Ollama-adapter agentische Codex-stappen zonder native outputschema,
normaliseert alleen een compleet JSON-codeblok, valideert met JSON Schema en probeert
maximaal drie correcties. De server valideert onafhankelijk. Usage van afgewezen
antwoorden blijft meetellen. Dit is een technische rookproef, geen HKH-kwaliteitsbenchmark.

De interne Codex-configuratie gebruikt de naam `ar-ollama`, omdat Codex de naam
`ollama` reserveert. Het publieke runtime-vendorId blijft `ollama`.

## Eigen modelmetadata en gericht herstel

De Ollama-container genereert een Codex-modelcatalogus voor de exacte modelalias en het
geconfigureerde contextvenster. Daarmee gebruikt Codex bekende metadata in plaats van
fallbackmetadata. De catalogus geldt uitsluitend voor Ollama; de gewone OpenAI- en
Claude-uitvoering verandert niet. Catalogus en hersteladapter zitten in het execution-image,
zodat een nieuwe container geen handmatige installatie nodig heeft.

Voor een aparte native CLI-proef op een Mac:

```bash
bash execution-images/install-ollama-codex.sh
~/.local/bin/codex-ollama
```

Dit installeert een eigen catalogus, Codex-home en startscript, zonder de gewone
`~/.codex/config.toml` te wijzigen. Standaard: `qwen3.5:35b`, 65.536 tokens en
`http://127.0.0.1:11434/v1`. Andere waarden kunnen bij installatie via `AR_MODEL`,
`AR_OLLAMA_CONTEXT_WINDOW` en `AR_OLLAMA_BASE_URL`. Stel ook Ollama zelf op hetzelfde
contextvenster in; metadata vergroot het daadwerkelijke modelgeheugen niet.

De uitvoering heeft nu drie zichtbare fasen: **bronnen lezen**, **antwoord controleren**
en **antwoord herstellen**. De worker meldt deze fasen via de bestaande progress-API;
er is geen monitor- of API-contractwijziging nodig.

1. Codex voert het onderzoek uit met bestanden en tools. Het model mag het complete
   antwoord bewaren in `/job/output/answer-draft.json`. De runtime leest dit ook als het
   eindbericht alleen een ontvangstbevestiging bevat. Na zijn broncontroles kan het model
   het document expliciet publiceren door daarna `{"complete":true}` naar
   `/job/output/answer-draft.ready` te schrijven. Een succesvolle toolafronding na die
   publicatie draagt het werk over: de runtime stopt Codex en controleert het document,
   in plaats van te wachten op onnodige nieuwe broncontroles. Alleen het bestaan van
   een JSON-bestand is geen publicatie. Voor compatibiliteit wordt een
   tijdens dezelfde uitvoering gewijzigd `/work/answer.json` ook herkend. Alleen deze
   vaste paden en het CLI-resultaat worden gelezen: reguliere bestanden, geen symlinks,
   maximaal 1 MiB. Een bestaand ongewijzigd bestand is geen nieuw antwoord.
2. Na normale CLI-afronding of expliciete publicatie valideert de runtime het document. Uitvoeringsfouten en
   JSON-errorenveloppen gaan niet naar de formatter. Ontbreekt een bruikbaar document,
   dan is maximaal één nieuwe onderzoeksuitvoering toegestaan.
3. Een apart, toolvrij verzoek aan hetzelfde Ollama-model herstelt het bestaande
   antwoord. Het krijgt uitsluitend de kandidaat, het JSON-schema en concrete fouten,
   zonder oorspronkelijke onderzoeksopdracht of bronbestanden. Dit gebruikt de native
   `/api/chat`-route naast de geconfigureerde `/v1`-route, met structured output,
   temperatuur 0 en zonder thinking. Het begrenzen van de formatter hindert daardoor
   geen toolcalls tijdens het onderzoek. Het volledige resultaat wordt onafhankelijk
   opnieuw gevalideerd; bij eenvoudige objectschema's blijven al geldige velden zoals
   artikeltekst en bron-ID's behouden, ook als het model die ongevraagd herschrijft.
4. Ontbreken feiten die niet uit het bestaande antwoord afleidbaar zijn, dan kan de
   formatter expliciet `needs_research` met één concrete vraag teruggeven. Alleen dan
   volgt gericht vervolgonderzoek met toegang tot de bronbestanden en het bewaarde
   antwoord. Maximaal twee onderzoeksuitvoeringen en drie formatterpogingen in totaal.

Iedere onderzoeksuitvoering is begrensd op acht minuten, een formatterverzoek op één
minuut en de hele adapter op tien minuten of de kortere resterende jobdeadline.
Annulering stopt het modelproces; de worker behoudt zijn harde deadline. De native
Ollama-route moet op dezelfde server naast `/v1` bereikbaar zijn. Schema's met lokale
referenties behouden hun oorspronkelijke referentiebasis: die worden tekstueel aangeboden
in plaats van ingebed in de formatter-envelop, en achteraf volledig gevalideerd.

Bronbestanden blijven bestanden. Gerapporteerde usage van onderzoek en formatterpogingen
wordt opgeteld. Bij expliciete publicatie kan Codex zijn laatste tokenmeting nog niet
hebben verstuurd: die uitvoering blijft daarom gedeeltelijk/geschat, ook als de
formatter zijn eigen usage wel rapporteert;
de server valideert onafhankelijk. Geldig JSON bewijst geen inhoudelijke juistheid.
Claude en OpenAI behouden hun bestaande uitvoeringsroute.

Bronnen: [Codex-modelcatalogus](https://learn.chatgpt.com/docs/model-catalogs),
[Ollama structured outputs](https://docs.ollama.com/capabilities/structured-outputs).

### Verificatie van de scheiding (25 oktober 2026)

De bewaarde mislukte HKH-schrijverskandidaat is met het echte Qwen-model via de
formatter opnieuw verwerkt. Ontbrekende titel, verkeerde vervolgvragentypen en een
te lange samenvatting zijn hersteld; het volledige schema valideert. Met behoud van
al geldige velden blijven de oorspronkelijke artikeltekst en bron-ID's gelijk.
De begrensde formatterproef duurde circa 32 seconden, zonder toegang tot bronbestanden.

De volledige onderzoeksproef met vier bevroren bronbestanden publiceerde binnen de
achtminutengrens geen antwoord en eindigde met `EXECUTION_TIMEOUT`. De formaatfix
bewijst dus niet dat Qwen deze grote onderzoekstaak tijdig aankan. Deze grens blijft
zichtbaar en wordt niet omzeild door telkens het onderzoek opnieuw te starten.
