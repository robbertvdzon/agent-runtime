# Deployment en operatie

## Omgevingen

| Omgeving | Namespace | Database | Route |
|---|---|---|---|
| Acceptatie | `agent-runtime-acceptance` | H2 in-memory | `agent-runtime-acceptance.vdzonsoftware.nl` |
| Productie | `agent-runtime` | PostgreSQL op 5 GiB PVC | `agent-runtime.vdzonsoftware.nl` |

Acceptatie is resetbaar en staat `MOCKED` toe. Productie is duurzaam en weigert mockuitvoering.
Cloudflare beëindigt publieke HTTPS en bereikt de OpenShift-route via HTTP. De routes gebruiken
daarom, net als Software Factory, `insecureEdgeTerminationPolicy: Allow`; `Redirect` zou achter
deze proxy een redirectlus maken.
Beide omgevingen gebruiken dezelfde serverartifact en hetzelfde externe contract.

## Secrets voorbereiden

```bash
./deploy/initialize-secrets.sh
./deploy/seal-secrets.sh
```

De eerste opdracht maakt alleen wanneer nodig het genegeerde `secrets.env`. Hij leest bestaande
Factory-secretbronnen zonder waarden te tonen. De tweede opdracht maakt twee namespacegebonden,
versleutelde manifests. Verwijder of commit `secrets.env` nooit.

## Bouwen en publiceren

De CI verifieert Maven, Modulith, contracttests, Flutter, Docker en beide Kustomize-overlays. Na een groene
mainbuild publiceert de imageworkflow:

- `ghcr.io/robbertvdzon/agent-runtime-server:main`;
- `ghcr.io/robbertvdzon/agent-runtime-execution:main`.

Een release gebruikt een immutable `sha-...`-tag. De serveroverlay en lokale workerdefault worden
samen op dezelfde gevalideerde release-SHA vastgezet. Voor een eerste handmatige rollout:

```bash
oc apply -k deploy/acceptance
oc rollout status deployment/agent-runtime-server -n agent-runtime-acceptance --timeout=180s
oc apply -k deploy/production
oc rollout status deployment/postgres -n agent-runtime --timeout=180s
oc rollout status deployment/agent-runtime-server -n agent-runtime --timeout=180s
```

Na de eerste gevalideerde rollout beheren de twee Applications onder `deploy/argocd` beide
overlays automatisch met prune en self-heal. Zij mogen pas worden toegepast nadat de in Git
vastgezette immutable image daadwerkelijk in GHCR bestaat.

## Probes en metrics

- liveness: `/actuator/health/liveness`;
- readiness: `/actuator/health/readiness`;
- eenvoudige routecheck: `/healthz`;
- Prometheus: `/actuator/prometheus`;
- monitor: `/`.

De server draait non-root, zonder Linux-capabilities, met begrensde CPU en geheugen en graceful
shutdown. PostgreSQL heeft een eigen serviceaccount en duurzaam volume.

## Databasebackup

Productie maakt iedere nacht om 02:17 UTC een custom-format dump. De job controleert de dump met
`pg_restore --list`, schrijft een SHA-256-bestand en publiceert via een atomische rename. Bestanden
ouder dan veertien dagen verdwijnen. Een backup telt pas als bewezen wanneer periodiek op een lege
database is teruggezet en de Flyway- en functionele smokechecks slagen.

## Rollback

Deze release verwijdert bewust ongebruikte contractkolommen. Een oude serverimage is na migratie
niet compatibel met het nieuwe schema.

1. Maak vóór productie een verifieerbare databasebackup en noteer de immutable server-, worker- en
   execution-imagetags.
2. Rol acceptatie uit, oefen de migratie en herstel de backup naar een aparte database.
3. Bij een applicatiefout die geen schemaherstel vereist: herstel vooruit met een nieuwe image.
4. Alleen wanneer terugkeer naar de oude server echt noodzakelijk is: stop writers, herstel de
   pre-releasebackup naar een nieuwe database en koppel de oude immutable image daaraan.
5. Draai nooit handmatig een neerwaartse Flywaymigratie op de actieve database.
