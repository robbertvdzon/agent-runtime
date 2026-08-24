FROM maven:3.9.10-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY agent-runtime-contracts/pom.xml agent-runtime-contracts/pom.xml
COPY agent-runtime-server/pom.xml agent-runtime-server/pom.xml
COPY agent-runtime-worker/pom.xml agent-runtime-worker/pom.xml
RUN mvn -B --no-transfer-progress -pl agent-runtime-server -am dependency:go-offline
COPY agent-runtime-contracts/src agent-runtime-contracts/src
COPY agent-runtime-server/src agent-runtime-server/src
RUN mvn -B --no-transfer-progress -DskipTests -pl agent-runtime-server -am package

FROM eclipse-temurin:21-jre-noble
RUN useradd --uid 1001 --create-home --shell /usr/sbin/nologin runtime
WORKDIR /app
COPY --from=build /build/agent-runtime-server/target/agent-runtime-server-*.jar /app/agent-runtime.jar
USER 1001
EXPOSE 8080
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-Djava.security.egd=file:/dev/urandom","-jar","/app/agent-runtime.jar"]
