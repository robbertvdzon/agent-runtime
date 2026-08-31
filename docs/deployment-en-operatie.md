# Deployment en operatie

## Omgevingen

| Omgeving | Providers | Worker-API | Namespace | Database | Route |
| --- | --- | --- | --- | --- | --- |
| Acceptatie | Alleen `MOCKED` | Uit | `agent-runtime-acceptance` | H2 in-memory | `agent-runtime-acceptance.vdzonsoftware.nl` |
| Productie | `CODEX`, `CLAUDE` | Aan | `agent-runtime` | PostgreSQL op 5 GiB PVC | `agent-runtime.vdzonsoftware.nl` |

Acceptatie weigert Codex en Claude, blokkeert `/v1/workers` en verliest H2-data bij een nieuwe pod.
De overlay zet alle consumerallowlists expliciet op `MOCKED`; de server weigert te starten als de
acceptatieconfiguratie die grens verruimt of de worker-API activeert. Productie weigert
mockuitvoering en bewaart data in PostgreSQL.

Cloudflare beëindigt publieke HTTPS en bereikt de OpenShift-route via HTTP. De routes gebruiken
`insecureEdgeTerminationPolicy: Allow` om een redirectlus achter de proxy te voorkomen. Beide
omgevingen gebruiken dezelfde serverartifact en hetzelfde `/v1`-contract.

## OpenShift-secrets

De lokale, gitignored bron is `secrets.env` met mode `0600`:

```bash
./deploy/initialize-secrets.sh
./deploy/seal-secrets.sh
```

`initialize-secrets.sh` neemt aanwezige Factory-servicetokens over zonder ze te tonen en genereert
ontbrekende waarden. `seal-secrets.sh` maakt afzonderlijke namespacegebonden Sealed Secrets voor
acceptatie en productie. De versleutelde manifests staan onder de deploymentoverlays in Git; het
bronbestand wordt nooit gecommit.

De serversecretset bevat databaseconfiguratie, zes bearercredentials, Google OAuth-client-ID,
beheerderallowlist en sessieondertekeningssecret. Productiestart faalt wanneer tokens leeg, te kort
of lokale standaardwaarden zijn, of wanneer Google-login niet volledig is geconfigureerd.

`AR_HKH_AUTOPILOT_TOKEN` en `AR_HKH_TOKEN` zijn zelfstandige consumentcredentials. De eerste mag
alleen `APPLICATION_WORK` met projectprefix `HKH_AUTOPILOT` aanvragen; de tweede alleen
`APPLICATION_WORK` met prefix `HKH`. Geen van beide heeft worker-, beheer- of repositoryrechten.
`deploy/configure-hkh-local-secret.sh` kan de tweede credential zonder weergave naar de genegeerde
`secrets.env` van een siblingcheckout `../hkh` overnemen en zet die file op mode `0600`.

PvdD gebruikt eveneens een zelfstandige consumentcredential en mag uitsluitend
`APPLICATION_WORK` met environmentprefix `PVDD` aanvragen. `AR_PVDD_TOKEN` is het productietoken;
`AR_PVDD_ACCEPTANCE_TOKEN` bestaat alleen in de lokale sealbron en wordt bij sealing als
`AR_PVDD_TOKEN` in namespace `agent-runtime-acceptance` geplaatst. Acceptance staat uitsluitend
`MOCKED`/`mock-model` toe; productie vereist expliciete echte providers en modellen.

## CI en releaseketen

`.github/workflows/verify.yml` draait voor pull requests en iedere push naar `main`:

- Flutter-tests en release-webbuild;
- Maven `verify`, inclusief server-, worker-, integratie- en Modulithtests;
- server-Dockerbuild;
- rendercontrole van acceptatie- en productie-Kustomize-overlays.

Na een groene `main`-verificatie start `.github/workflows/images.yml`. Deze workflow publiceert:

```text
ghcr.io/robbertvdzon/agent-runtime-server:main
ghcr.io/robbertvdzon/agent-runtime-server:sha-<main-commit>
ghcr.io/robbertvdzon/agent-runtime-execution:main
ghcr.io/robbertvdzon/agent-runtime-execution:sha-<main-commit>
```

De execution-image wordt voor `linux/amd64` en `linux/arm64` gebouwd. De serverimage gebruikt
`linux/amd64`.

Nadat beide images zijn gepubliceerd, past de workflow `deploy/base/kustomization.yaml` aan naar de
immutable servertag en pusht een `[skip ci]`-systeemcommit naar `main`. Daardoor ontstaat geen
nieuwe buildlus.

## Automatische uitrol

De Argo CD Applications `agent-runtime-acceptance` en `agent-runtime` volgen `main`. Zij hebben
automatische synchronisatie, prune en self-heal. De image-pincommit activeert de rollout naar beide
namespaces.

De uitrolstatus is zichtbaar met:

```bash
oc get applications.argoproj.io -n argocd \
  agent-runtime agent-runtime-acceptance
oc rollout status deployment/agent-runtime-server \
  -n agent-runtime-acceptance --timeout=300s
oc rollout status deployment/agent-runtime-server \
  -n agent-runtime --timeout=300s
```

De daadwerkelijk draaiende productie-image staat in:

```bash
oc get deployment agent-runtime-server -n agent-runtime \
  -o jsonpath='{.spec.template.spec.containers[0].image}{"\n"}'
```

## Handmatige synchronisatie

Wanneer Argo CD niet beschikbaar is, renderen en toepassen dezelfde overlays rechtstreeks:

```bash
oc apply -k deploy/acceptance
oc rollout status deployment/agent-runtime-server \
  -n agent-runtime-acceptance --timeout=300s

oc apply -k deploy/production
oc rollout status deployment/postgres -n agent-runtime --timeout=300s
oc rollout status deployment/agent-runtime-server \
  -n agent-runtime --timeout=300s
```

De overlay verwijst altijd naar een immutable image die al in GHCR staat.

## Productiecontrole

```bash
curl -fsS https://agent-runtime.vdzonsoftware.nl/healthz
curl -fsS -o /dev/null -w '%{http_code}\n' \
  https://agent-runtime.vdzonsoftware.nl/
oc get pods -n agent-runtime
oc get application agent-runtime -n argocd \
  -o jsonpath='{.status.sync.status} {.status.health.status}{"\n"}'
```

Een geslaagde uitrol heeft `Synced Healthy`, een `1/1` ready serverpod, `{"status":"UP"}` op
`/healthz` en HTTP 200 op de monitor.

## Probes en metrics

- liveness: `/actuator/health/liveness`;
- readiness: `/actuator/health/readiness`;
- publieke routecheck: `/healthz`;
- Prometheus: `/actuator/prometheus`;
- monitor: `/`.

De server draait non-root, zonder Linux-capabilities, met CPU-/geheugenlimieten en graceful
shutdown. PostgreSQL heeft een eigen serviceaccount en persistente volumeclaim.

## Databasebackups

Productie maakt iedere nacht om 02:17 UTC een PostgreSQL custom-format dump. De job controleert de
dump met `pg_restore --list`, schrijft een SHA-256-bestand en publiceert beide bestanden via een
atomische rename. Backups ouder dan veertien dagen worden verwijderd.

Een restore-oefening gebruikt een lege afzonderlijke database, verifieert eerst de SHA-256 en start
dezelfde serverversie tegen de herstelde database. De oefening controleert Flywaystatus,
idempotente jobopvraging en succesvolle resultaatsmokes.

## Rollback

Herstel bij een applicatiefout door een nieuwe, gerepareerde immutable release via dezelfde
CI-keten te publiceren. Wanneer terugkeer naar een oudere server noodzakelijk is, worden writers
gestopt en wordt een backup van vóór de incompatibele migratie naar een nieuwe database hersteld.
De actieve productiedatabase krijgt geen handmatige neerwaartse Flywaymigratie.
