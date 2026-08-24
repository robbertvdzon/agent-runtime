FROM eclipse-temurin:21-jdk-noble

ENV DEBIAN_FRONTEND=noninteractive \
    NPM_CONFIG_UPDATE_NOTIFIER=false \
    PLAYWRIGHT_BROWSERS_PATH=/ms-playwright

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl git gh jq nodejs npm postgresql-client maven openssh-client unzip xz-utils \
    && npm install -g @openai/codex @anthropic-ai/claude-code playwright \
    && npx playwright install --with-deps chromium \
    && arch="$(dpkg --print-architecture)" \
    && case "$arch" in amd64) oc_arch=amd64 ;; arm64) oc_arch=arm64 ;; *) exit 1 ;; esac \
    && curl -fsSL "https://mirror.openshift.com/pub/openshift-v4/clients/ocp/stable/openshift-client-linux-${oc_arch}.tar.gz" -o /tmp/oc.tar.gz \
    && tar -xzf /tmp/oc.tar.gz -C /usr/local/bin oc kubectl \
    && rm -f /tmp/oc.tar.gz \
    && rm -rf /var/lib/apt/lists/*

RUN useradd --create-home --shell /bin/bash agent \
    && install -d -o agent -g agent /work /runtime /home/agent/.codex /home/agent/.claude

COPY run-agent.sh /usr/local/bin/run-agent
RUN chmod 0755 /usr/local/bin/run-agent

USER agent
WORKDIR /work
ENTRYPOINT ["/usr/local/bin/run-agent"]
