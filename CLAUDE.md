# Agent Runtime

Gedeeld platform waarmee geautoriseerde applicaties op OpenShift (Software Factory, Product Factory, HKH, Newsfeed) betrouwbare AI-agenttaken kunnen laten uitvoeren door lokale workers op Robberts MacBook, zonder die MacBook vanaf internet bereikbaar te maken.

## Begin hier

Lees eerst [`docs/agent-runtime-stappenplan.md`](docs/agent-runtime-stappenplan.md). Dat is de volledige en enige bron van waarheid voor dit project: aanleiding, architectuur, jobsoorten, jobstatussen, alle fases (0 t/m 10), beveiligingsgrenzen, betrouwbaarheids- en uitrolstrategie, openstaande beslissingen en ontwerpvragen. Deze repo bevat verder nog geen code — het stappenplan is het vertrekpunt voor alles.

Kernbeslissingen om in het achterhoofd te houden (staan uitgewerkt in het stappenplan):

- **Transport worker↔server**: HTTPS long-polling (geen WebSocket, geen directe databasetoegang vanaf de worker — ook al staat de laptop in hetzelfde netwerk als OpenShift). De server gebruikt intern PostgreSQL `LISTEN`/`NOTIFY` om long-poll-requests direct te beantwoorden.
- **Crash-/herstartherstel**: agentcontainers krijgen een Docker-label met job-ID en lease-token; de worker reconcilieert bij opstarten via `docker ps --filter label=...` in plaats van op lokale state te vertrouwen, en voorkomt zo dubbele uitvoering na een lease-conflict.
- **Credentialmodel**: allowlist per jobprofile (niet een denylist) — elk jobprofile bepaalt vooraf exact welke secrets, mounts en tools een job krijgt.
- **Eén breed gedeeld execution-image**, niet per-profiel images: browser (Playwright/Chromium), build/test-toolchains, `oc`/`kubectl` en databaseclients zitten er allemaal in. Aanwezigheid van een tool geeft geen rechten; alleen de credentials/mounts die het jobprofile toestaat bepalen wat een job echt kan.
- **Deterministische verificatierunner**: build/testcommando's die de AI claimt te hebben uitgevoerd, worden door een aparte, niet-AI component nogmaals gedraaid en tegen de Git-toestand vóór/na gecontroleerd.

## Referentierepositories

Beide staan naast deze repo in `~/git/` en mogen vrij gelezen worden voor context — er hoeft niets in gewijzigd te worden.

- **Software Factory** — `/Users/robbertvdzon/git/softwarefactory` (GitHub: `robbertvdzon/software-factory`). Robbert's bestaande autonome multi-agent pipeline (planner/developer/reviewer/tester-agents) die tracker-stories omzet in gemergde PR's. De volwassen referentie voor hoe agents nu al in Docker draaien, browser-automation, build/test-toolchains, `oc`-toegang en de verificatierunner werken — deze runtime hergebruikt de architectuur en lessen daarvan, niet de code. Zie de sectie "Referentiemateriaal in andere repositories" in het stappenplan voor concrete bestandsverwijzingen (`Dockerfile.agent`, `DockerAgentRuntime.kt`, `TesterVerificationRunner.kt`, `.factory/verification.yaml`, `AgentWorkspace.kt`).
- **Product Factory** — `/Users/robbertvdzon/git/product-factory` (GitHub: `robbertvdzon/product-factory`). Waar dit stappenplan oorspronkelijk is opgesteld, en de beoogde eerste pilot-consument van deze runtime (Fase 6).
