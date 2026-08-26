FROM node:22-bookworm-slim AS node-runtime

FROM eclipse-temurin:21-jdk-noble

ARG TARGETARCH

ENV DEBIAN_FRONTEND=noninteractive \
    NPM_CONFIG_UPDATE_NOTIFIER=false \
    PLAYWRIGHT_BROWSERS_PATH=/ms-playwright

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl git gh jq postgresql-client maven openssh-client unzip xz-utils \
    && rm -rf /var/lib/apt/lists/*

COPY --from=node-runtime /usr/local/ /usr/local/

RUN node --version \
    && npm --version \
    && npm install -g @openai/codex @anthropic-ai/claude-code playwright \
    && npx playwright install --with-deps chromium \
    && case "${TARGETARCH:-$(dpkg --print-architecture)}" in amd64) oc_suffix="" ;; arm64) oc_suffix="-arm64" ;; *) exit 64 ;; esac \
    && curl -fsSL "https://mirror.openshift.com/pub/openshift-v4/clients/ocp/stable/openshift-client-linux${oc_suffix}.tar.gz" -o /tmp/oc.tar.gz \
    && tar -xzf /tmp/oc.tar.gz -C /usr/local/bin oc kubectl \
    && rm -f /tmp/oc.tar.gz

RUN useradd --create-home --shell /bin/bash agent \
    && install -d -o agent -g agent /work /job/input /job/secrets /job/docs /job/output/artifacts /home/agent/.codex /home/agent/.claude

COPY run-agent.sh /usr/local/bin/run-agent
RUN chmod 0755 /usr/local/bin/run-agent

USER agent
WORKDIR /work
ENTRYPOINT ["/usr/local/bin/run-agent"]
