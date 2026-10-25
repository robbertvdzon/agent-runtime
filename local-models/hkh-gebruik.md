# Hoe HKH AI gebruikt

Codeonderzoek op 25 oktober 2026 in de naburige repository `../../hkh`.
Dit beschrijft de checkout, geen controle van de actieve productieconfiguratie.

## Pijplijn en agent zijn verschillende taken

HKH ondersteunt een agent die zelf via de collectie-API onderzoekt, en een
pijplijn waarin Kotlin/SQL het zoeken grotendeels uitvoert. De gekozen methode
komt uit instellingen; de codefallback in `AiModelSettings.method()` is `AGENT`.
We nemen dus niet aan dat de pijplijn in productie actief is.

De pijplijn is het beste eerste doel voor lokale modellen omdat de taken begrensd
zijn. In `ResearchPipelineService.run` gebeurt het volgende:

1. `QuestionTerms` haalt termen uit de vraag; SQL zoekt kaarten via
   `KeywordSearch`. Alleen bij ontbrekende termen/treffers volgt een AI-planjob.
2. **Richten (RESEARCH):** vraag, gesprekscontext en kandidaatkaarten worden
   aangeboden. Het model produceert een tussenconclusie en een zoekquery met
   termen, periode, collecties, beeldfilter, sortering en maximaal 60 expliciete
   records. Kandidaten/selecie hebben in de code plafonds van 300; eerdere bronnen
   worden bij de kandidaatset gevoegd. De AI voert de SQL niet zelf uit.
3. SQL selecteert records; de code laadt en verpakt hun teksten.
4. **Schrijven (WRITER):** het model ontvangt genummerde bronnen en schrijft
   uitsluitend daaruit een Nederlands HTML-antwoord met bronnen en vervolgvragen.
5. **Uitwerken:** dezelfde selectie wordt opnieuw geladen; een aparte schrijfjob
   maakt een langere versie. Zoeken en richten worden overgeslagen.

Richten heeft een jobtimeout van 600 seconden, schrijven 1200 seconden in deze
code. De planfallback gebruikt 420 seconden. Dit zijn technische grenzen, geen
gewenste responstijden.

## Omvang en uitvoer

- Kaartsamenvattingen worden op 1.500 tekens afgekapt; trefwoorden en fragmenten
  komen daar nog bij. Ook de richtstap kan dus grote invoer krijgen.
- Het schrijfbudget is standaard 450.000 tekens; de kleine-modelheuristiek geeft
  250.000 voor namen met `haiku|mini|nano|flash|lite`. **Qwen3.5 en gpt-oss krijgen
  door die heuristiek niet automatisch een kleiner budget.** Een beheerinstelling
  overschrijft het budget; dit is een globale instelling voor het schrijfbudget,
  geen aangetoonde tabel met afzonderlijke opgeslagen budgetten voor elk model.
- Korte documentteksten tot 1.500 tekens gaan volledig mee; lange documenten
  worden tot 60.000 tekens per document begrensd. Wat niet volledig past, wordt
  als kaart/fragment meegegeven. Het budget is geen harde tokenlimiet voor de
  totale prompt: kaarten, korte records en overige promptinhoud tellen ook mee.
- `JobPrompts.assemble` gebruikt bij grote inhoud bestanden (inlinegrens 60.000
  tekens). Een bestand omzeilt geen modelcontext: toolresultaten moeten nog steeds
  in het contextvenster passen.
- Het eerste antwoord is begrensd tot ongeveer 4.000–7.000 tekens, maximaal tien
  alinea's en zes foto's; een uitwerking heeft als richtlengte 12.000–25.000 tekens.
- Het schrijfschema vereist `title`, `answerHtml`, `sources`,
  `suggestedFollowUps`, `moreAvailable` en `moreSummary`.
  `sources` bevat hier **integers**: de code vertaalt de genummerde bronnen naar
  collection/ident. Het uiteindelijke agentschema gebruikt die objecten.

## Wat een lokaal model moet kunnen

Het belangrijkste is zorgvuldig Nederlands schrijven uit aangeleverde bronnen:
geen zelfbedachte jaartallen, personen of familierelaties, onzekerheden benoemen,
en iedere verwijzing laten kloppen. Richten vraagt daarnaast goede selectie,
spellingvarianten en chronologie. Code schrijven is voor deze pijplijn geen
hoofddoel. Een codingmodel is daarom niet automatisch de beste keuze.

Vision is voor de huidige schrijfroute geen harde eis: de prompt gebruikt
bronmetadata en fotoverwijzingen. Dat een foto in het antwoord verschijnt bewijst
niet dat het model de beeldpixels heeft beoordeeld. OCR/beeldinterpretatie als
afzonderlijke taak vraagt een aparte proef.

De agentmethode stelt extra eisen: zelfstandig API-aanroepen doen, bestanden
beheren, itereren en weten wanneer genoeg gevonden is. Test die pas nadat de
begrensde pijplijntaken goed werken.

## Herleidbare code en bestaande tests

- [ResearchPipelineService.kt](../../hkh/backend/src/main/kotlin/nl/vdzon/hkh/research/ResearchPipelineService.kt): stappen, prompts, schema's, packing en bronvertaling.
- [StructuredJobRunner.kt](../../hkh/backend/src/main/kotlin/nl/vdzon/hkh/research/StructuredJobRunner.kt): `JobPrompts` en attachments.
- [AiModelSettings.kt](../../hkh/backend/src/main/kotlin/nl/vdzon/hkh/aisearch/AiModelSettings.kt): RESEARCH/WRITER-keuze, methode en budget.
- [AgentRuntimeClient.kt](../../hkh/backend/src/main/kotlin/nl/vdzon/hkh/aisearch/AgentRuntimeClient.kt): `/v2/jobs`, `APPLICATION_WORK`, `STRUCTURED_GENERATION`, vendor/model/mode.
- [AiSearchService.kt](../../hkh/backend/src/main/kotlin/nl/vdzon/hkh/aisearch/AiSearchService.kt): agentprompt en keuze van onderzoeksroute.
- [Bestaande HKH-testset](../../hkh/docs/ideas/ai-onderzoek-testset.md): acht vragen met verschillende foutklassen; oude antwoorden zijn vergelijkingsmateriaal, geen foutloze waarheid.
- [Mistral-naspels](../mistral-test/BEVINDINGEN.md): bestaande schrijf- en uitwerkingsfixtures; dit zijn metingen met gehoste modellen via Vibe, geen lokaal benchmarkbewijs.
