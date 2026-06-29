# syntax=docker/dockerfile:1
#
# serving.Dockerfile — the read API (topsales-api), the long-running service
# behind the ALB. Build context is the REPO ROOT (`.`) so the Maven reactor can
# resolve sibling modules (topsales-common, -forecast, -insight) via `-am`.
#
# ---- build stage: full reactor, only the api module + its deps -------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# Bring the whole service reactor in (a partial copy would break `-am` resolution).
COPY service/ ./service/

# Build just topsales-api and the modules it depends on. Tests run in CI, not here.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -f service/pom.xml -pl topsales-api -am package -DskipTests

# ---- runtime stage: slim JRE + the boot jar -------------------------------
FROM eclipse-temurin:21-jre
WORKDIR /app

# The version-suffixed *executable* boot jar (the `.jar.original` plain jar is
# excluded by the glob).
COPY --from=build /workspace/service/topsales-api/target/topsales-api-*.jar app.jar

# The serving profile selects the aws-tier impls (DynamoDB / Bedrock / CloudWatch).
ENV SPRING_PROFILES_ACTIVE=aws
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
