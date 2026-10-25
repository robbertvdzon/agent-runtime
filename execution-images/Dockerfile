FROM node:22-bookworm-slim AS node-runtime

FROM eclipse-temurin:21-jdk-noble

ARG TARGETARCH
ARG FLUTTER_VERSION=3.44.6
ARG CODEX_VERSION=0.164.0
ARG CLAUDE_VERSION=2.1.310
ARG PLAYWRIGHT_VERSION=1.64.1
ARG OPENSHIFT_VERSION=4.20.0

ENV DEBIAN_FRONTEND=noninteractive \
    NPM_CONFIG_UPDATE_NOTIFIER=false \
    PLAYWRIGHT_BROWSERS_PATH=/ms-playwright

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl git gh jq openjdk-17-jdk-headless postgresql-client maven openssh-client python3 python3-pip python3-venv unzip xz-utils \
    && rm -rf /var/lib/apt/lists/*

COPY --from=node-runtime /usr/local/ /usr/local/

RUN node --version \
    && npm --version \
    && npm install -g "@openai/codex@$CODEX_VERSION" "@anthropic-ai/claude-code@$CLAUDE_VERSION" "playwright@$PLAYWRIGHT_VERSION" \
    && npx playwright install --with-deps chromium \
    && case "${TARGETARCH:-$(dpkg --print-architecture)}" in amd64) oc_suffix="" ;; arm64) oc_suffix="-arm64" ;; *) exit 64 ;; esac \
    && curl -fsSL "https://mirror.openshift.com/pub/openshift-v4/clients/ocp/${OPENSHIFT_VERSION}/openshift-client-linux${oc_suffix}.tar.gz" -o /tmp/oc.tar.gz \
    && tar -xzf /tmp/oc.tar.gz -C /usr/local/bin oc kubectl \
    && rm -f /tmp/oc.tar.gz

# Pin Vibe: the adapter reads the tested unified-session journal format.
RUN python3 -m venv /opt/vibe \
    && /opt/vibe/bin/pip install --no-cache-dir mistral-vibe==3.0.2 jsonschema==4.26.0 \
    && ln -s /opt/vibe/bin/vibe /usr/local/bin/vibe

RUN useradd --create-home --shell /bin/bash agent \
    && install -d -o agent -g agent /work /job/input /job/secrets /job/docs /job/output/artifacts /home/agent/.codex /home/agent/.claude

# Eén brede, gedeelde toolchain voor agentwerk én repositoryverificatie. Flutter is bewust gepind
# op dezelfde versie als de Software Factory-CI: verschillende Flutter-SDK's pinnen verschillende
# flutter_test-transitives en zouden daardoor dezelfde pubspec.lock herschrijven. De cache is van
# de niet-geprivilegieerde execution-user zodat projectbuilds hem veilig kunnen gebruiken.
RUN git clone --depth 1 --branch "$FLUTTER_VERSION" https://github.com/flutter/flutter.git /opt/flutter \
    && chown -R agent:agent /opt/flutter
ENV PATH="/opt/flutter/bin:${PATH}" \
    JAVA_HOME_17_X64=/usr/lib/jvm/java-17-openjdk-amd64 \
    JAVA_HOME_17_ARM64=/usr/lib/jvm/java-17-openjdk-arm64

USER agent
RUN flutter config --no-analytics \
    && flutter precache --web
USER root
# Ubuntu's Maven 3.8 does not satisfy the repositories' Maven >=3.9 requirement.
# Keep this pinned tool layer separate from the expensive Flutter/browser layers.
ARG MAVEN_VERSION=3.9.10
RUN curl -fsSL "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/${MAVEN_VERSION}/apache-maven-${MAVEN_VERSION}-bin.tar.gz" -o /tmp/maven.tar.gz \
    && tar -xzf /tmp/maven.tar.gz -C /opt \
    && ln -sf "/opt/apache-maven-${MAVEN_VERSION}/bin/mvn" /usr/local/bin/mvn \
    && rm /tmp/maven.tar.gz
COPY ollama-model-catalog.py /usr/local/lib/agent-runtime/ollama-model-catalog.py
COPY ollama-agent.py /usr/local/lib/agent-runtime/ollama-agent.py
COPY mistral-agent.py /usr/local/lib/agent-runtime/mistral-agent.py
COPY run-agent.sh /usr/local/bin/run-agent
RUN chmod 0755 /usr/local/bin/run-agent

USER agent
WORKDIR /work
ENTRYPOINT ["/usr/local/bin/run-agent"]
