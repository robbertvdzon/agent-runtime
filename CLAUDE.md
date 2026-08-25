# Agent Runtime

Gedeeld platform waarmee geautoriseerde applicaties op OpenShift (Software Factory, Product Factory, HKH, Newsfeed) betrouwbare AI-agenttaken kunnen laten uitvoeren door lokale workers op Robberts MacBook, zonder die MacBook vanaf internet bereikbaar te maken.

## Begin hier

Lees eerst [`README.md`](README.md) en daarna [`docs/agent-runtime-stappenplan.md`](docs/agent-runtime-stappenplan.md).
De repository bevat een werkende eerste platformrelease. Verander het externe contract alleen
achterwaarts compatibel en pas contract, tests en documentatie samen aan.
Lees voor nieuw `APPLICATION_WORK` ook
[`docs/application-work-v2.md`](docs/application-work-v2.md); dit is het leidende doelcontract voor
de Product Factory-migratie en wordt naast v1 ingevoerd.

Kernbeslissingen om in het achterhoofd te houden (staan uitgewerkt in het stappenplan):

- **Twee jobsoorten**: Product Factory gebruikt `APPLICATION_WORK`; Software Factory gebruikt
  `REPOSITORY_WORK`. Agent Runtime kent hun agentrollen en domeinentiteiten niet.
- **Eén technische eigenaar**: Agent Runtime beheert jobqueue, attempts, leases, heartbeats,
  fencing, technische retries, resultaten en artifacts. Consumenten houden alleen hun eigen
  orkestratie en domeinverwerking.
- **Transport worker↔server**: HTTPS long-polling (geen WebSocket en nooit directe databaseverbinding vanuit de worker).
- **Fencing**: agentcontainers krijgen alleen labels met workerbootsessie, job-ID en attempt-ID;
  tokens of andere secrets mogen nooit in labels of logs staan. De server accepteert iedere
  mutatie alleen met het actuele attempt en fencing token.
- **Centrale mocks**: `MOCKED` wordt server-side afgehandeld met dezelfde jobopslag,
  schema-validatie en resultaten, maar zonder worker, lease of Dockercontainer. Productie weigert
  deze route.
- **Provider en model**: een vertrouwde consument vraagt beide exact aan binnen haar jobprofile; de
  runtime valideert de keuze en wisselt nooit stilzwijgend van model.
- **Credentialmodel**: v1 en `REPOSITORY_WORK` gebruiken jobprofielen; `APPLICATION_WORK` v2 leidt
  de vaste policy uit de consumentidentiteit af en vraagt alleen namen die een worker lokaal uit
  `project-credentials.env` ontdekt. Runtime- en providercredentials blijven altijd buiten de
  agentcontainer.
- **Eén breed gedeeld execution-image**, niet per-profiel images: browser (Playwright/Chromium), build/test-toolchains, `oc`/`kubectl` en databaseclients zitten er allemaal in. Aanwezigheid van een tool geeft geen rechten; alleen de credentials/mounts die het jobprofile toestaat bepalen wat een job echt kan.
- **Deterministische verificatierunner**: repositoryprofielen die dit vereisen laten build- en
  testcommando's door een aparte, niet-AI component herhalen en vergelijken de Git-toestand vóór en
  na.

## Referentierepositories

Beide staan naast deze repo in `~/git/` en mogen vrij gelezen worden voor context — er hoeft niets in gewijzigd te worden.

- **Software Factory** — `/Users/robbertvdzon/git/softwarefactory` (GitHub: `robbertvdzon/software-factory`). Robbert's bestaande autonome multi-agent pipeline (planner/developer/reviewer/tester-agents) die tracker-stories omzet in gemergde PR's. De volwassen referentie voor hoe agents nu al in Docker draaien, browser-automation, build/test-toolchains, `oc`-toegang en de verificatierunner werken — deze runtime hergebruikt de architectuur en lessen daarvan, niet de code. Zie de sectie "Referentiemateriaal in andere repositories" in het stappenplan voor concrete bestandsverwijzingen (`Dockerfile.agent`, `DockerAgentRuntime.kt`, `TesterVerificationRunner.kt`, `.factory/verification.yaml`, `AgentWorkspace.kt`).
- **Product Factory** — `/Users/robbertvdzon/git/product-factory` (GitHub: `robbertvdzon/product-factory`). Eerste consument van `APPLICATION_WORK` in fase 5. Product Factory bouwt geen nieuwe eigen laptopworker.
