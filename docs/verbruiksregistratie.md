# Verbruik van workeruitvoeringen

De v2-worker registreert verbruik onafhankelijk van resultaatvalidatie, uploads en
Git-publicatie. Iedere uitvoerings- en herstelronde meldt afzonderlijke toenames;
een fout na AI-uitvoering verwijdert eerder geregistreerde tokens niet.

De execution-image gebruikt Codex `--json` en Claude `--output-format stream-json`
met gedeeltelijke berichten. Codex meldt tokens bij `turn.completed`. Claude meldt
input en caching per uniek message-id, output via streaming-delta's en een totaal
bij het eindresultaat. Het eindtotaal wordt niet opnieuw bij de reeds gemelde
hoeveelheden opgeteld. Codex cached input wordt van gewone input afgetrokken.

De parser volgt de [Codex JSONL-documentatie](https://developers.openai.com/codex/noninteractive/)
en [Claude-verbruiksdocumentatie](https://code.claude.com/docs/en/agent-sdk/cost-tracking).
Claude-resultaten worden uit `structured_output` of `result` gehaald; het
usage-envelope wordt niet als applicatieresultaat doorgegeven.

Verbruik onderweg krijgt kwaliteit `PARTIAL`. Pas wanneer alle uitgevoerde rondes
een volledige providerregistratie hebben, volgt `COMPLETE`. Subagentgebruik kan
een ander model betreffen en wordt daarom niet tegen het hoofdmodeltarief opgeteld;
een dergelijke registratie blijft gedeeltelijk. Niet-geprijsde positieve metrics,
zoals cache writes zonder tarief, blijven zichtbaar als gedeeltelijk geprijsd.

Bij een oudere execution-image zonder tokenmeldingen blijft de schatting op basis
van prompt/input en ruwe resultaatgrootte beschikbaar. Deze gebeurt per ronde,
ook bij ongeldige JSON. Zonder meetgegevens of resultaat wordt geen bedrag verzonnen.
Een harde proces-/machinecrash vóór een provider-event kan dus nog steeds onbekend
verbruik opleveren. De logverbinding blokkeert het uitlezen van provider-events niet.

De worker bewaart uitgaande usage-events gedurende de poging in een wachtrij en
herhaalt mislukte verzendingen met hetzelfde event-id. Aan het einde worden nog
drie afleverpogingen gedaan. Een blijvend onbereikbare server wordt in de workerlog
gemeld; de wachtrij overleeft geen workerherstart. Reeds ontvangen events blijven
wel behouden, ook na mislukking of annulering.

Alleen het accounting-endpoint accepteert tot 24 uur na het einde van de poging
nog verbruik met de oorspronkelijke worker-, job-, attempt- en fencing-identiteit.
De observatietijd moet bij de oorspronkelijke uitvoering horen (60 seconden marge
voor afsluiting en klokverschil). Dit heropent geen job en verruimt de toegang tot
resultaten, bestanden of heartbeats niet. Database-inserts zijn idempotent, ook
wanneer het antwoord op een geslaagde insert onderweg verloren gaat.

Uitrol vereist de nieuwe server, execution-image en worker-JAR. Rol eerst de
server uit en werk daarna image en worker bij. Historische pogingen zonder
opgeslagen verbruik worden niet achteraf met gefingeerde bedragen aangevuld.

## Mistral Vibe

De gepinde Vibe 3.0.2-adapter leest iedere voltooide modelaanroep uit het sessiejournal.
Ook door de JSON-hook afgekeurde antwoorden tellen mee; action-ID's voorkomen dubbeltelling
bij herhaald uitlezen. `input_tokens` bevat cached input: de worker registreert het verschil
als INPUT_TOKENS en cached input afzonderlijk als CACHED_INPUT_TOKENS. De sessie-ID wordt
providerRequestId. De samenvatting uit export.json wordt niet als sessietotaal gebruikt.

Verbruik is alleen compleet wanneer alle door Vibe gemelde modelbeurten zijn aangetroffen.
Ontbrekende/afgebroken telemetry blijft gedeeltelijk of onbekend. Subagents, automatische
sessietitels en externe connectoren zijn in deze adapter uitgeschakeld. Er zijn voor Mistral
geen prijsregels toegevoegd: onbekende kosten zijn geen nulbedrag en geen API-fallback.
