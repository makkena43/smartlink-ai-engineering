# syntax=docker/dockerfile:1

# ---------------------------------------------------------------------------
# Stage 1 — build
#
# Dependencies resolve in their own layer so that a source-only change does not
# re-download the world on every rebuild.
# ---------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
# Tests run in CI against real containers, not here. A Docker build that runs
# Testcontainers would need a Docker daemon inside the build, which is a worse
# trade than running the suite where it already runs.
RUN mvn -B -q clean package -DskipTests

# ---------------------------------------------------------------------------
# Stage 2 — runtime
#
# JRE only, non-root, no build tooling in the shipped image.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine AS runtime

RUN addgroup -S smartlink && adduser -S -G smartlink smartlink

WORKDIR /app
COPY --from=build --chown=smartlink:smartlink /build/target/smartlink-*.jar app.jar

USER smartlink
EXPOSE 8080

# Container memory, not host memory, decides the heap.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

# Readiness, not liveness: the orchestrator should not route traffic here until
# the database is actually reachable (AC-6.2).
HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health/readiness | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
