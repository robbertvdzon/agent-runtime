# Bevindingen: Mistral (Vibe) in de Agent Runtime

Status: onderzoek + naspel-tests, 24 oktober 2026. Alles in deze map is voorbereidend
werk voor het toevoegen van Mistral als derde provider naast CODEX (OpenAI) en
CLAUDE (Anthropic). Nog geen productiecode gewijzigd.

## 1. Naspel-tests overzicht

Twee echte HKH-productiejobs zijn 1-op-1 nagespeeld (prompt, schema en
bronbestanden via de runtime-API/database overgenomen; alleen de `/job/input`-paden
zijn herschreven naar de lokale map, inclusief de slotzin die `run-agent.sh` normaal
toevoegt). De claude-referentie stond buiten de werkmap van de agent (tegen spieken).

| Naspel | Productiejob | Fase | Wat |
|---|---|---|---|
| `hkh-naspel/` | `e24e334a-…` (`hkh-ai-…-expand`) | expand | uitwerking: lang antwoord (12–25k tekens) over de Componistenbuurt, 11 bronbestanden (319 bronnen) |
| `hkh-naspel-2/` | `f0699435-…` (`hkh-ai-…-write`) | write | begrensd eerste antwoord (~8k tekens), zelfde onderwerp, 12 bronbestanden |

Gedraaid met: `vibe --prompt-file … --output json --auto-approve --trust
--workdir . --output-dir output-* --time-limit 1500 --max-turns 60`.

### 1a. Resultaten expand-job (`hkh-naspel/`)

| | claude-sonnet-5-5 (prod) | glm-5.3 run 1 | glm-5.3 herhaal | mistral-large-4 | glm-5.3-flash |
|---|---|---|---|---|---|
| Pogingen | 1 | 1 | 1 | 1 | 2 |
| Duur | 6,9 min | ~2 min | 4,1 min | **12,4 min** | 111 s (beide mislukt) |
| Steps | ~25 rondes | 14 | 14 | 10 | 13 |
| Input (cached) | 1,35 mln | 1,16 mln (0,99) | 1,18 mln (1,01) | 0,79 mln (0,60) | 1,14 mln (0,60) |
| Output | 38,6k | 18,7k | 20,3k | **57,6k** | 12,9k (alleen reasoning) |
| answerHtml | 36,5k | 25,0k | 30,0k | **54,9k** | — |
| Bronnen / figures | 86 / 18 | 62 / 12 | 71 / 13 | **107 / 29** | — |
| Schema | geldig | 3 fouten | **geldig** | **geldig** | — |
| API-kost/run | $0,65 (officieel) | $0,58 | $0,59 | $0,30 | — |

Schemafouten glm run 1: `title` ontbreekt, `sources` als strings i.p.v. integers,
ongesorteerd. De herhaalrun met hetzelfde model was foutloos: **schemafouten zijn
run-variantie, geen systematiek** — maar ze treden wél op, dus afdwinging blijft nodig.

### 1b. Resultaten write-job (`hkh-naspel-2/`)

| | claude (prod) | glm-5.3 | mistral-large-4 | glm-5.3-flash |
|---|---|---|---|---|
| Pogingen | 1 | 1 | 2 | 2 |
| Duur | 109 s | **74 s** | 156 s → faal; 2e: 423 s | 119 s; 2e: **93 s** |
| Input (cached) | 0,76 mln totaal | 1,29 mln (1,12) | 2,01 mln → faal; 2e: 1,58 mln (1,33) | 1,29 mln; 2e: 0,69 mln |
| answerHtml | 7.862 | 8.295 | 7.611 (2e poging) | 6.716 (2e poging) |
| Bronnen / figures | 32 / 5 | 30 / 6 | 29 / 6 | 22 / 6 |
| Schema | geldig | **geldig** | **geldig** (2e) | **geldig** (2e) |
| API-kost/run | $0,18 (officieel) | $0,58 | $0,31 | n.v.t. (geen API-tarief) |

### 1c. Geobserveerde foutmodi (alle runs)

1. **Schemafouten zonder afdwinging** (glm expand run 1): valide JSON, verkeerde
   vorm. Bevestigt dat de `post_agent`-validatiehook noodzakelijk is.
2. **Vibe reserveert te veel output** (flash poging 1): `max_output_tokens: null`
   in de abbo-catalogus → Vibe vraagt de volledige resterende context als output
   (1.008.516 tokens) → 400 ContextWindowExceeded. Oplossing: expliciete
   model-override met `max_output_tokens` (staat in `.vibe/config.toml`).
   `--max-tokens` (sessiebudget) begrenst dit níet.
3. **"finished" zonder antwoord** (flash expand): outcome `finished`, exit 0, maar
   het model eindigde met een aankondiging in plaats van het antwoord. De worker
   moet dus resultaat-aanwezigheid valideren, niet alleen de exit-code.
4. **Kapotte JSON** (flash write poging 1): compleet antwoord, maar een structurele
   JSON-fout (vermoedelijk ontbrekende komma na de lange answerHtml-string) —
   één correctieronde-klas.
5. **Sessie-limietverbruik** (large-4 write poging 1): reasoning-explosie (2,01 mln
   tokens, 35 steps) → `outcome: token_limit` zonder antwoord. Met een cap van
   3,5 mln lukte poging 2. **Per model een ruime maar reële sessie-cap zetten.**
6. **Run-variantie**: tijd (2 vs 4,1 min), tokenverbruik (±10%) en schemafouten
   variëren per run — voor kwaliteitsconclusies geldt: meerdere runs meten.

### 1d. Kwaliteitsconclusies

- **glm-5.3 is de beste default**: 4/4 runs geslaagd, nooit schemafouten behalve
  run 1 van expand, snelste van alles, output het dichtst bij de claude-referentie.
- **mistral-large-4**: inhoudelijk het rijkst (expand: 54,9k HTML, 107 bronnen,
  29 figures — boven claude), foutloos, maar 3× langzamer en wilder in
  tokenverbruik. Geschikt als zwaar alternatief.
- **glm-5.3-flash**: 3 van 4 pogingen mislukt (3 verschillende foutmodi); alleen
  inzetten voor korte taken mét output-cap en resultaat-controle.
- Token-efficiëntie t.o.v. claude: glm ±1,7× méér tokens voor write-jobs; voor
  expand juist ±0,7× (claude gebruikte daar 1,64 mln t.o.v. glm 1,18 mln).

### 1e. Bestanden

- `hkh-naspel/output*/`: per run `result.json` (JSON-antwoord), `antwoord.html`
  (tekst), `antwoord-compleet.html` (met echte beeldbank-foto's en klikbare
  links), `export.json` (usage/kosten/model), `vibe-journal.json` (volledige sessie)
- `hkh-naspel/fotos/` en `fotos-2/`: beeldbank-foto's per bronnummer
- `referentie/`: claude-productieresultaat + gerenderde versie
- `render-antwoord.py`: koppelt bronnummers aan beeldbank-objecten
  (`beeldbank/large/<objectnr>.jpg`), downloadt netjes (0,4 s spoeling) en rendert
  complete HTML; niet-beeldbankbronnen (Heemskring, Dagblad) krijgen alleen een
  titel-annotatie — conform de originele promptregels
- `run-naspel.sh` (één run), `run-vergelijking.sh` / `run-retries.sh` (series)

## 2. Modelcatalogus van het abbo (oktober 2026)

| Alias | Model | Vision | AA Intelligence Index | Snelheid | API-tarief (per mln) |
|---|---|---|---|---|---|
| `glm-5-3` | GLM-5.3 (default routing) | nee | 45 (#3 open-weights) | 85 t/s | $1,40 in / $0,26 cached / $4,40 uit |
| `mistral-large-4-0-3` | Mistral Large 4 (Preview) | ja | 38,4 | ~200 t/s | $0,68 / $0,07 / $2,09 (preview; lijst $1,36/$4,18) |
| `zai-glm-5-3-flash` | GLM-5.3 Flash | ja | 42 | ~65–69 t/s | geen Mistral-API-tarief; wel via Z.ai e.a. |

Externe bronnen: Artificial Analysis (okt 2026; ook als leaderboard op Hugging
Face Spaces). Ter schaal: Claude Opus 5.5 (max) = 58. Opvallend: de door Mistral
gehoste GLM-5.3 scoort hoger op intelligentie-index dan Mistral's eigen Large 4.

## 3. Kosten

### 3a. Gemeten kosten per testrun (API-equivalent)

| Run | claude-sonnet-5-5 | glm-5.3 | mistral-large-4 |
|---|---|---|---|
| expand (groot) | $0,65 | $0,58–0,59 | $0,30 |
| write (klein) | $0,18 | $0,58 | $0,31 |

Claude-tarieven (runtime-catalogus, gecontroled): $2,- in / $0,20 cached / $10,-
uit per mln. **Let op: de catalogus mist het `CACHE_WRITE_TOKENS`-tarief voor
Claude** — Anthropic rekent wél cache-writes (±1,25× input = $2,50/M). Met writes
erbij is de echte API-kost: expand ~$1,29, write ~$0,87. De runtime markeert die
pogingen correct als gedeeltelijk geprijsd; **actie: cache-write-tarieven toevoegen**.

Vibe's `cost_usd` in export.json = exact het API-tarief (glm-metingen komen tot op
de cent overeen).

### 3b. Wat kost wat per miljoen tokens (API)

| | input | cached | output |
|---|---|---|---|
| claude-sonnet-5-5 | $2,00 | $0,20 | $10,00 (writes $2,50) |
| glm-5.3 | $1,40 | $0,26 | $4,40 |
| mistral-large-4 (preview) | $0,68 | $0,07 | $2,09 |

Verrassende conclusie: **large-4 is via de API de goedkoopste per run** (lagere
tarieven wegen zwaarder dan zijn hogere tokenverbruik); claude is per run de
duurste ondanks token-efficiëntie.

## 4. Limieten en capaciteit

### 4a. Mistral-abbo (Vibe)

- Gemeten: **4,33% maandquota verbruikt** na ±11,4 mln testtokens van vanavond
  → geschat maandquota **±265 mln tokens** (reset 1 nov; `quota_changed_this_month:
  false`).
- **`payg_enabled: false`** → de API-key kan géén kosten buiten het abbo maken;
  de geplande abbo→API-fallback vereist eerst PAYG-activatie of een aparte key.
- Limietenstructuur: requests/seconde, tokens/minuut (nog ongemeten — TPM-probe
  gepland), tokens/maand.

**Capaciteit per type test bij ±265 mln quota:**

| Soort verzoek | Tokens/verzoek | Max/maand |
|---|---|---|
| HKH expand-naspel (glm, zoals getest) | ~1,25 mln | **±205–210** |
| Productie-HKH-job gemiddeld (claude-gem. 111k × 1,7 glm) | ~190k | ±1.400 |
| PNF-samenvatting | ~160k | ±1.650 |
| Repository-agent (SF) | ~2,3 mln | ±115 |

### 4b. HKH-zoekscherm: echte verbruiksprofiel (30 dagen, claude)

Het interactieve zoekscherm (`hkh-ai-*`-jobs) bestaat uit fasen:

| Fase | Jobs/maand | Gem. tokens | Totaal |
|---|---|---|---|
| zwaar zoekantwoord | 104 | 733k | 76,3 mln |
| write | 39 | 406k | 15,8 mln |
| round | 27 | 135k | 3,7 mln |
| expand | 5 | 724k | 3,6 mln |
| direct | 20 | 150k | 3,0 mln |
| plan | 19 | 32k | 0,6 mln |
| **Totaal interactief** | **214** | gem. 481k | **103 mln** |

Daarnaast draaien 1.794 collectie-batchjobs (transcripties, beeldbank, artikelen,
archief, library, bidprent, objecten; gem. 67k tokens, 120,7 mln totaal) — dat is
achtergrondverwerking, geen interactief zoekscherm.

### 4c. Doel: 700 zoekscherm-verzoeken/maand op Mistral

- 700 × zware write/expand-klasse (~1,25 mln glm) = **±875 mln tokens = 3,3× de
  geschatte quota — past níet in één abbo.**
- Wat er in één abbo past: ±205–210 zware verzoeken.
- Om 700 te halen: (a) hoger plan (Team/Enterprise), (b) PAYG-API voor de
  overloop: ±490 verzoeken × ~$0,45/M ≈ **$250/maand** bij glm-API, of ±$0,30/run
  ≈ **$150/maand** via mistral-large-4-API, (c) fase-gemengd routeren.
- **Routeringsadvies**: lichte fases (plan/direct/round) + een deel van
  write/expand naar `mistral/glm-5-3` (±100 mln = 38% van quota, dekt huidig
  volume ruim ×2); de zware rest op claude tot het echte volume de ±200 nadert;
  dán PAYG aanzetten.

### 4d. Vergelijking: €100 Claude Max 5x voor hetzelfde werk

Anthropic publiceert geen token-aantallen; community-metingen (sep–okt 2026):
per 5-uursvenster ~39 mln tokens (~$37 API-waarde) op Max 5x, het weekplafond
bond zelden; cache reads tellen nauwelijks mee (en deze zoekscherm-runs zijn
82% cached).

| | Per 5-uursvenster | Per maand (schatting) |
|---|---|---|
| expand-runs (1,64 mln) | ±24 | **±250–350** |
| write-runs (772k) | ±50 | ±500+ |

Voor dit specifieke HKH-werk geeft een €100 Claude Max dus ruwweg evenveel tot
meer testruns dan het Mistral-abbo (±250–350 vs ±205–210). Onzekerheden:
metingen lopen uiteen (factor 1,7–3,5×), het bucket is gedeeld met Claude.ai-chat,
en het venstermodel verspreidt piekdruk anders. Harde meting mogelijk door
naspels te draaien tegen een Max-account en `/status` af te lezen.

## 5. Vibe-CLI-bevindingen voor de integratie

### Waarom Vibe CLI

- codex ≥ 0.147 spreekt alleen nog het OpenAI Responses-API-formaat; Mistral biedt
  dat niet → direct codex→Mistral is dood; alleen via vertaalproxy.
- claude-code + proxy kan, maar voegt een proxy aan het execution-image toe zonder
  voordeel.
- Mistral Vibe: native agentische CLI met abonnement, zelfde paradigma als
  codex/claude (CLI + credential + abonnement). Eénmalig `vibe --setup` op de
  worker-Mac; key vervalt niet automatisch (rotatie is beleid).

### Geconcurreerde mechanismen (allen geverifieerd)

| Runtime-eis | Vibe-mechanisme |
|---|---|
| Headless run | `vibe -p` / `--prompt-file` (bestand, geen E2BIG) |
| Agentisch | volledig; `--auto-approve` |
| Geen trust-prompt | `--trust` |
| Werkmap | `--workdir` |
| Model per job | `VIBE_ACTIVE_MODEL` (export bevat `active_model` naast `routed_default_model`) |
| Resultaat | laatste assistant-message in `--output json` |
| Live transcript | `--output streaming` (JSONL per regel) |
| Usage per ronde | `--output-dir` → `export.json` (input/cached/output, `cost_usd`, `stop_reason`, `outcome`) |
| Harde deadline | `--time-limit` (exit 3, export weggeschreven); SIGTERM netjes |
| Extra remmen | `--max-turns`, `--max-tokens`, `--max-price` |

Gevonden grenzen: `--max-tokens` begrenst de per-request outputreservering niet;
modellen zonder catalogus-`max_output_tokens` kunnen falen (zie 1c); `outcome:
finished` ≠ bruikbaar resultaat.

### JSON-schema (belangrijkste lacune + oplossing)

Vibe heeft geen `--output-schema`; zie 1c punt 1. Oplossing: `post_agent`-hook die
het eindantwoord tegen `response-schema.json` valideert, bij falen `decision:
"deny"` met de validatiefout als retry (max 3 in-run), plus resultaat-aanwezigheidscheck.
Server-validatie en de drie correctieronden blijven als achtervang.

### Wat er in de code moet veranderen (checklist)

1. `agent-runtime-contracts`: `Provider`-enum + v1-OpenAPI met `MISTRAL` (v2 heeft
   al vrije `vendorId`-strings).
2. `agent-runtime-worker`: `AR_MISTRAL_API_KEY`/`AR_MISTRAL_MODELS`, registratie,
   `ExecutorCapability("mistral", model, SUBSCRIPTION/API, …)`, credential als
   env-var, vendor→engine-mapping, per-model output-cap en sessie-cap.
3. `V2ExecutionUsage.kt`: usage uit `export.json`; `session_id` als
   `provider_request_id`; resultaat-aanwezigheidscheck.
4. `execution-images`: `uv tool install mistral-vibe`; `run-agent.sh` `MISTRAL`-case
   + `post_agent`-hook + models-override met output-caps.
5. Server `V2Catalogs.kt`/`V2Jobs.kt`: `"mistral"` in SUBSCRIPTION/API-sets;
   tenantprovider-lijsten uitbreiden; fase-gebaseerd routeringsbeleid mogelijk.
6. Prijscatalogus: mistral-tarieven (zie 3b; large-4 preview-tarief met
   bronverwijzing en valid_from) + **Claude cache-write-tarieven aanvullen**.
7. `monitor-ui/lib/main.dart`: weergavenaam.
8. Docs: README, jobs-en-uitvoering, verbruiksregistratie, worker-instructies.

### Toegang tot runs voor onderzoek (zoals in deze sessie gebruikt)

- Cluster: read-only `claude-agent`-SA (`~/okd-sno/sno/auth/kubeconfig-agent-readonly`).
- Database `ar_prod` via `scripts/postgresql/port-forward.sh` + rol
  `robbert_db_viewer` (`~/.pgpass`): jobs, attempts, usage, kosten, prompts
  (`request_json`), resultaten (`result_json`). Objectbytes via consumer-API
  (`GET /v2/jobs/{id}/objects/{oid}/content`).
- Replay zonder codewijziging: `POST /v2/jobs` met nieuwe idempotency-key (andere
  vendor/model mogelijk), of de prompt direct door een CLI halen zoals hier.

## 6. Open punten / vervolg

- TPM/RPS-grens van het abbo meten (goedkope probe + duurtest, ±100k–8 mln tokens).
- REPOSITORY_WORK-naspel (Git-checkout, verificatie, commit-pad) nog niet getest.
- Exit-codecontract van Vibe bij fouten vastleggen in de worker.
- Mistral Large 4: preview-prijzen en stabiliteit opvolgen; bij definitieve
  tarieven een nieuwe prijscatalogus-versie invoeren.
- `payg_enabled: false`: PAYG activeren vóór de fallback-route bruikbaar is.
- Quota-schatting (±265 mln) verfijnen na de maandreset (1 november).
- Implementatie uitwerken zodra groen licht; zie de checklist hierboven.

## 5. Gerichte proef `post_agent`-herstel (25 oktober 2026)

Uitgevoerd met lokaal geïnstalleerde **Vibe 3.0.2** en **glm-5-3**
(`active_model` én `routed_default_model` bevestigd in iedere export). Geen
productietoegang of productiejob gebruikt: alleen synthetische brondata en het
bestaande HKH-response-schema uit de tweede naspel. Geen runtimecode gewijzigd.
Scope voor de beoogde integratie: alleen HKH STRUCTURED_GENERATION via SUBSCRIPTION;
API-fallback en repositorywerk vallen buiten deze eerste versie.

De hook parseert het kandidaatantwoord met `json.loads` en valideert het volledige
schema met `jsonschema` (Draft202012Validator). Bij fouten retourneert hij
`decision: deny` met de concrete validatiefout, het schema en het afgekeurde
antwoord. Bij een geldig antwoord retourneert hij `allow`.

| Proef | Hook-aanroepen | Resultaat |
|---|---:|---|
| Kapotte JSON (ontbrekende sluitaccolade) | 2 | Na één herstelbeurt schema-geldig |
| Geldige JSON, ontbrekend title en sources met string | 2 | Beide schemafouten na één herstelbeurt opgelost |
| Alleen “Ik ga nu het antwoord schrijven.” | 2 | Na één herstelbeurt volledig schema-geldig JSON-resultaat |
| Geldig antwoord als controle | 1 | Direct geaccepteerd, geen extra modelbeurt |
| Blijvend NIET_JSON, ook na feedback | 4 | Initiële beurt + drie herstelbeurten, eindantwoord nog ongeldig |

In iedere proef hebben alle hook-aanroepen dezelfde session_id, overeenkomend
met de export. Iedere proef start één Vibe-proces. De drie herstelde resultaten
zijn ook na afloop apart tegen het schema gevalideerd. Het gaat om bewust
opgewekte fouten om het mechanisme te toetsen, niet om een nieuwe kwaliteitsmeting
van HKH-antwoorden. Een lege string wordt door dezelfde parser ook afgekeurd;
geen apart live-modelgeval voor een volledig lege assistant-message getest.

### Belangrijke implementatielessen

1. **Herstel binnen dezelfde sessie werkt.** Geen nieuwe CLI-run nodig. Een geldig
   kandidaatantwoord veroorzaakt geen onnodige herstelbeurt.
2. **De herstelgrens faalt open.** De blijvend ongeldige proef eindigt ondanks vier
   afkeuringen met `outcome: finished`, exit 0 en `NIET_JSON` als eindantwoord.
   Definitieve worker/servervalidatie moet dit als ongeldige uitvoer afhandelen;
   CLI-succes alleen bewijst geen jobsucces.
3. **Het kandidaatantwoord zit niet in hook-stdin.** Vibe geeft `transcript_path`
   mee, in deze versie een unified-session-directory. De proef leest daar de
   meest recente `post_agent_turn`-kandidaat uit het journal. Dit is een
   versiegebonden opslagformaat, geen bewezen stabiele productie-interface.
   Pin de Vibe-versie en verifieer deze extractie bij upgrades; bij extractie- of
   hookfouten mag de runtime nooit zonder eindvalidatie accepteren.
4. **Geef het afgekeurde antwoord expliciet mee als herstelfeedback.** In het
   journal markeert Vibe afgekeurde antwoorden als discarded. De proef levert
   daarom naast de foutmelding ook de kandidaattekst aan voor gericht herstel.
5. **Usage-export vraagt apart onderzoek bij implementatie.** In de kapotte-JSON-
   proef bevatten de twee completion-results respectievelijk 7.904/75 en
   8.175/76 input/output-tokens. `export.json` rapporteert 8.175/76, gelijk aan
   alleen de laatste completion. Gebruik de export dus niet ongecontroleerd als
   optelsom voor alle herstelbeurten. Dit is een waarneming uit deze versie/proef,
   nog geen volledig onderzocht usagecontract.

### Bewijs en reproductie

- `hook-proef/validate_hook.py`: echte JSON-/schemavalidatie en feedback.
- `hook-proef/run_tests.py`: vijf sequentiële live-proeven met deadline en tokenlimiet.
- `hook-proef/response-schema.json`: kopie van het gebruikte HKH-schema.
- `hook-proef/results.json`: samengevatte resultaten.
- `hook-proef/cases/<naam>/validation.jsonl`: kandidaten, fouten en hookbesluiten.
- `hook-proef/cases/<naam>/journal.json` en `output/`: Vibe-transcript en sessie-export.
- `hook-proef/cases/broken/` en `probe.py`: voorafgaande technische verkenning,
  met opzettelijk altijd afkeurende hook; niet een van de vijf validatorproeven.

Reproductie: gebruik de Python uit de bestaande uv-installatie van mistral-vibe
(die jsonschema bevat) om `hook-proef/run_tests.py` te starten. De runner gebruikt
absolute lokale CLI-/Pythonpaden en weigert bestaande casemappen te overschrijven;
verplaats de vijf gegenereerde casemappen eerst als je opnieuw wilt draaien.
De proef gebruikt de bestaande lokale Vibe-authenticatie; er zijn geen credentials
naar de repository of hookconfig gekopieerd.
