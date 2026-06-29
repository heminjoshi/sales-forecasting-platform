# syntax=docker/dockerfile:1
#
# forecaster.Dockerfile — the batch forecaster (topsales-forecast /
# BatchApplication). RUN-TO-COMPLETION: this is not a server; it computes the
# versioned serving rows + WAPE backtest and exits. Scheduled as an ECS task /
# cron, not a Fargate service. Build context is the REPO ROOT (`.`).
#
# ---- build stage -----------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY service/ ./service/

# Build just topsales-forecast and its upstream modules.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -f service/pom.xml -pl topsales-forecast -am package -DskipTests

# ---- runtime stage ---------------------------------------------------------
FROM eclipse-temurin:21-jre
WORKDIR /app

# The boot-repackaged BatchApplication jar (executable; `.jar.original` excluded).
COPY --from=build /workspace/service/topsales-forecast/target/topsales-forecast-*.jar app.jar

ENV SPRING_PROFILES_ACTIVE=aws

# No EXPOSE: a batch job listens on nothing. The container exits when the run
# completes (ECS scheduled task / RunTask), so there is no health port.
ENTRYPOINT ["java", "-jar", "app.jar"]
