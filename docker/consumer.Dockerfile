# syntax=docker/dockerfile:1
#
# consumer.Dockerfile — DESIGNED-ONLY (the aws-tier Kinesis ingestion consumer).
#
# WHY DESIGNED-ONLY: `topsales-ingestion` is a library module with NO Spring Boot
# main class — locally, ingestion runs in-process inside the api/datagen path
# (filesystem raw log), so there is no standalone consumer entrypoint to package.
# The aws tier swaps that for a Kinesis consumer. Rather than invent a second
# main, this image REUSES the already-repackaged topsales-api boot jar and
# selects a consumer launcher via Spring profile + a launcher property:
#
#     SPRING_PROFILES_ACTIVE=aws,consumer
#
# When the `consumer` profile + Kinesis launcher land (Phase 7+ serving-side
# work, outside this PR), this Dockerfile builds and runs unchanged — it is wired
# now so the image matrix and the design are complete. It is BUILDABLE today
# (the jar exists); it just won't do consumer work until that launcher ships.
#
# Build context is the REPO ROOT (`.`).
#
# ---- build stage -----------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY service/ ./service/

# Reuse the api reactor (it carries the only boot main + the ingestion module on
# its classpath via `-am`).
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -f service/pom.xml -pl topsales-api -am package -DskipTests

# ---- runtime stage ---------------------------------------------------------
FROM eclipse-temurin:21-jre
WORKDIR /app

COPY --from=build /workspace/service/topsales-api/target/topsales-api-*.jar app.jar

# aws tier + the (designed) consumer launcher profile.
ENV SPRING_PROFILES_ACTIVE=aws,consumer

# No EXPOSE: a Kinesis consumer pulls; it does not serve HTTP.
ENTRYPOINT ["java", "-jar", "app.jar"]
