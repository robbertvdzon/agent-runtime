# Agent Runtime

Gedeeld platform waarmee geautoriseerde applicaties op OpenShift (Software Factory, Product Factory, HKH, Newsfeed) betrouwbare AI-agenttaken kunnen laten uitvoeren door lokale workers op Robberts MacBook, zonder die MacBook vanaf internet bereikbaar te maken.

## Begin hier

Lees eerst [`README.md`](README.md) en daarna [`docs/agent-runtime-stappenplan.md`](docs/agent-runtime-stappenplan.md).
De repository bevat een werkende eerste platformrelease, maar nog geen actieve API-consumers. De
huidige contractvorm mag daarom bewust breaking worden vereenvoudigd; pas contract, implementatie,
tests en documentatie altijd samen aan. Bouw geen parallelle compatibiliteits-API.
Lees voor nieuw `APPLICATION_WORK` ook
[`docs/application-work.md`](docs/application-work.md); dit is het leidende contract voor de huidige
API.

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
- **Provider en model**: een vertrouwde consument vraagt beide exact aan; de runtime valideert de
  keuze tegen serverpolicy en wisselt nooit stilzwijgend van model.
- **Credentialmodel**: de vaste policy volgt uit de consumentidentiteit. `APPLICATION_WORK` vraagt
  alleen namen die een worker lokaal uit
  `project-credentials.env` ontdekt. Runtime- en providercredentials blijven altijd buiten de
  agentcontainer.
- **Eén breed gedeeld execution-image**: browser (Playwright/Chromium), build/test-toolchains,
  `oc`/`kubectl` en databaseclients zitten er allemaal in. Aanwezigheid van een tool geeft geen
  rechten; alleen serverpolicy en de geselecteerde credentials/mounts bepalen wat een job kan.
- **Deterministische verificatierunner**: repositoryprofielen die dit vereisen laten build- en
  testcommando's door een aparte, niet-AI component herhalen en vergelijken de Git-toestand vóór en
  na.

## Referentierepositories

Beide staan naast deze repo in `~/git/` en mogen vrij gelezen worden voor context — er hoeft niets in gewijzigd te worden.

- **Software Factory** — `/Users/robbertvdzon/git/softwarefactory` (GitHub: `robbertvdzon/software-factory`). Robbert's bestaande autonome multi-agent pipeline (planner/developer/reviewer/tester-agents) die tracker-stories omzet in gemergde PR's. De volwassen referentie voor hoe agents nu al in Docker draaien, browser-automation, build/test-toolchains, `oc`-toegang en de verificatierunner werken — deze runtime hergebruikt de architectuur en lessen daarvan, niet de code. Zie de sectie "Referentiemateriaal in andere repositories" in het stappenplan voor concrete bestandsverwijzingen (`Dockerfile.agent`, `DockerAgentRuntime.kt`, `TesterVerificationRunner.kt`, `.factory/verification.yaml`, `AgentWorkspace.kt`).
- **Product Factory** — `/Users/robbertvdzon/git/product-factory` (GitHub:
  `robbertvdzon/product-factory`). Referentie voor een toekomstige `APPLICATION_WORK`-consumer;
  implementatiewerk in die repository staat niet in het Runtime-stappenplan.
