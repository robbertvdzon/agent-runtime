# Lokale modellen met Codex CLI

Onderzoeksnotitie, 25 oktober 2026. Uitgangspunt: **Codex CLI als harnas**, met
een lokale modelserver op de worker-Mac. Dit document beschrijft een voorstel;
er zijn in dit onderzoek geen modellen geïnstalleerd of lokale modelruns uitgevoerd.

## Eerste advies

Begin met **Qwen3.5 35B-A3B** voor het HKH-brononderzoek en het schrijven van
Nederlandse antwoorden. Vergelijk met **Qwen3.5 27B** en gebruik **gpt-oss 20B**
als compacte technische referentie voor Codex. Dit is een te testen shortlist,
geen gemeten kwaliteitsranglijst. Gemma 4 26B/31B is een aanvullende kandidaat.

De onderzochte Mac heeft 64 GiB unified memory (`sysctl hw.memsize`), gedeeld
door macOS, apps, Docker en inference. De genoemde modellen zijn op basis van hun
gewichtsomvang plausibele kandidaten; het maximale bruikbare contextvenster en
de snelheid op deze Mac zijn nog onbekend.

Lees verder:

- [HKH: werkelijk AI-gebruik en modeleisen](hkh-gebruik.md)
- [Modelopties en afwegingen](modelkeuze.md)
- [Codex, lokale uitvoering en proefopzet](codex-en-proefopzet.md)

## Keuzes voor de runtime

Behandel harnas, model en endpoint als afzonderlijke keuzes. Codex CLI betekent
niet noodzakelijk een OpenAI-model. Het gewenste pad is:

```text
HKH → Agent Runtime → worker → Codex CLI in jobcontainer
                                      ↓
                         lokale modelserver op macOS → model
```

Gebruik voor deze eerste stap Ollama: er is een gedocumenteerde Codex-integratie.
LM Studio is een tweede rechtstreeks ondersteunde optie. MLX/llama.cpp kunnen
later worden onderzocht; een Chat Completions-endpoint alleen is onvoldoende
bewijs dat een server met de gebruikte Codex-versie werkt. Controleer de
Responses-API, tool calls, streaming en outputschema's met een echte run.

De lokale CLI is hier versie `0.147.0`; `codex exec --help` bevestigt `--oss`,
`--local-provider`, `--json` en `--output-schema`. De versie in het execution-image
en de modelserverversie moeten bij een proef afzonderlijk worden vastgelegd.

Er is geen OpenAI-abonnement of OpenAI API-key nodig voor een lokale provider
zonder authenticatie. Modellicenties gelden afzonderlijk. Lokaal gebruik heeft
wel hardware-, stroom- en beheerkosten.

Bronnen: [OpenAI authenticatie](https://learn.chatgpt.com/docs/auth#alternative-model-providers),
[Codex CLI-opties](https://learn.chatgpt.com/docs/developer-commands#codex-exec),
[Ollama/Codex](https://docs.ollama.com/integrations/codex).
