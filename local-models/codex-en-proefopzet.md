# Codex CLI: uitvoering en proefopzet

Voorstel, 25 oktober 2026. Alleen documentatie; onderstaande stappen zijn nog niet
uitgevoerd. De lokale CLI-help is gecontroleerd voor versie 0.147.0.

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
  --output-schema response-schema.json - < prompt.md
```

`prompt.md`, schema en bronbestanden zijn fixtures in die proefdirectory. Test
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

## Benodigde runtime-uitwerking (nog niet geïmplementeerd)

1. Maak harnas/endpoint/model onafhankelijk van de bestaande OpenAI-koppeling.
   Een mogelijk v2-profiel is `vendorId: local`, `mode: LOCAL` en een lokaal model,
   met Codex als workerengine. Dit is een ontwerpkeuze, geen huidig API-contract.
2. Laat workerregistratie lokale capabilities melden zonder OpenAI-credentials
   te vereisen. Breid serverpolicy/catalogus uit voor lokale structured generation.
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
