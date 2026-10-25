# Modelopties voor HKH

Stand 25 oktober 2026. Advies op basis van taakprofiel, gepubliceerde modelkenmerken
en gewichtsomvang. Geen van deze kandidaten is in dit onderzoek op HKH gemeten.

## Shortlist voor een Mac met 64 GiB

| Model / Ollama-tag | Gepubliceerde downloadomvang | Context in catalogus | Voorstel voor HKH |
| --- | --- | --- | --- |
| `qwen3.5:35b` (35B-A3B) | circa 22 GB | 256K | Eerste kandidaat voor richten én schrijven; algemeen meertalig model, met 35B totaal en circa 3B actieve parameters per token. |
| `qwen3.5:27b` | circa 17–20 GB | 256K | Vergelijkingskandidaat voor schrijfkwaliteit; een dense model kan een andere kwaliteit/snelheidsbalans hebben. Niet bij voorbaat beter dan 35B. |
| `gpt-oss:20b` | circa 14 GB | 128K | Compacte technische referentie voor Codex en toolgebruik. Nederlands en brontrouw expliciet beoordelen; minder contextruimte. |
| `gemma4:26b` / `gemma4:31b` | circa 16–19 / 19–20 GB | 256K | Tweede familie als Qwen onvoldoende brontrouw of Nederlands levert; eerst één variant toevoegen om de proef beheersbaar te houden. |
| `qwen3.5:9b` | circa 6,6–7,6 GB | 256K | Snelle/kleine baseline voor richten of kaartverwerking; geschiktheid voor complexe historische synthese nog onzeker. |
| `devstral-small-2:24b` | circa 15 GB | catalogus 384K | Optionele agent-/toolbaseline, primair op software-engineering gericht. Geen eerste keuze voor Nederlandse historische verhalen. |

Downloadomvang is **geen totaal RAM-verbruik**. Varianten en quantisatie verschillen.
Contextcatalogi zijn maxima, geen belofte dat dat venster op deze Mac snel of zelfs
bruikbaar past. Leg bij de proef de exacte tag, digest, quantisatie en ingestelde
context vast; gebruik geen veranderlijke `latest`-tag als benchmarkidentiteit.

Bij MoE-modellen worden minder parameters per token gebruikt, maar alle gewichten
moeten beschikbaar blijven. “3B actief” betekent dus niet het geheugenverbruik van
een model met slechts 3B totaal. Een snelheidsvoordeel op deze Mac moet worden gemeten.

## Waarom Qwen als eerste inhoudelijke proef

Qwen publiceert voor de 35B-A3B-variant 262.144 native contexttokens en ondersteunt
Nederlands binnen zijn meertalige opzet. Dat maakt de familie een passende
onderzoekskandidaat voor lange Nederlandstalige bronnen en antwoorden. Het bewijst
nog niet dat namen, OCR-fouten en familieverbanden in HKH correct worden behandeld.

Voor gpt-oss is de combinatie van beperkte gewichtsomvang, redeneervermogen en
toolgebruik interessant. De training was volgens OpenAI overwegend Engelstalig;
beoordeel het Nederlandse antwoord daarom apart. Een 128K-venster kan bij de
standaard HKH-invoer krap zijn.

Voorlopige volgorde: eerst gpt-oss 20B als technische rookproef, daarna Qwen3.5
35B-A3B voor inhoudelijke proeven en Qwen3.5 27B als vergelijking. Pas daarna een
andere familie of een kleiner model voor alleen RESEARCH. Gebruik eerst hetzelfde
model voor beide rollen zodat fouten eenvoudig te herleiden zijn.

## Wat voorlopig afvalt

`gpt-oss:120b` (circa 65 GB gewichten) en `qwen3.5:122b` (circa 81 GB) zijn geen
praktisch startpunt op deze 64 GiB-Mac: er moet ook ruimte zijn voor context,
runtime en andere processen. Een krachtiger machine kan deze afweging veranderen.
Een `:cloud`-tag is geen lokaal model, ook al loopt de verbinding via Ollama.

Een embeddingmodel kan retrieval helpen, maar vervangt de generatieve schrijver
niet. HKH gebruikt nu al SQL/trigram/full-text en trefwoordkaarten; embeddings zijn
een afzonderlijk onderzoek, geen vereiste om lokaal te beginnen.

## Context en budget

Begin met een ingestelde context van 64K en een **proefbudget** van 50.000–100.000
tekens brontekst. Dit is een conservatieve startkeuze, geen bewezen capaciteit.
Meet tokens voor het concrete model en reserveer ruimte voor systeeminstructies,
gesprekscontext, toolhistorie, reasoning en antwoord. Een vaste tekens/tokens-ratio
is onvoldoende voor Nederlandse OCR, HTML en eigennamen.

Vergroot vervolgens context en invoer stapsgewijs, bijvoorbeeld naar 128K en pas
daarna richting het bestaande budget. Controleer ook de werkelijk verzonden
kaarten en korte bronnen; alleen het lange-documentbudget verlagen garandeert
niet dat de totale prompt past. Vergelijk modellen eerst op dezelfde bronset en
hetzelfde budget, daarna eventueel op hun eigen beste werkbare instellingen.

## Primaire bronnen

Geraadpleegd op 25 oktober 2026; catalogi kunnen veranderen.

- [Qwen3.5 Ollama-catalogus](https://ollama.com/library/qwen3.5)
- [Qwen modelkaart 35B-A3B](https://huggingface.co/Qwen/Qwen3.5-35B-A3B)
- [Qwen3.5 talen en modeluitleg](https://qwen.ai/blog?id=qwen3.5)
- [gpt-oss Ollama-catalogus](https://ollama.com/library/gpt-oss)
- [OpenAI gpt-oss 20B](https://developers.openai.com/api/docs/models/gpt-oss-20b)
- [Gemma 4 Ollama-catalogus](https://ollama.com/library/gemma4)
- [Devstral Small 2 Ollama-catalogus](https://ollama.com/library/devstral-small-2)
