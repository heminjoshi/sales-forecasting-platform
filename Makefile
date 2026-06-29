# Thin wrappers over the real commands. See README for prerequisites.
.PHONY: up down run test verify seed demo synth

# Bring up the local stack (Postgres + Redis).
up:
	docker compose -f local/docker-compose.yml up -d

# Tear down the local stack.
down:
	docker compose -f local/docker-compose.yml down

# Run the API locally (boots an empty server until Phase 2 adds endpoints).
# Install deps to the local repo first, then run the goal scoped to topsales-api only
# (running spring-boot:run across the reactor fails on the parent/library modules).
run:
	mvn -f service/pom.xml -pl topsales-api -am -DskipTests install
	mvn -f service/pom.xml -pl topsales-api spring-boot:run

# Fast unit tests across the reactor (no Docker). Runs everywhere.
test:
	mvn -f service/pom.xml test

# Full build incl. Testcontainers *IT integration tests (needs a Docker daemon with
# API >= the docker-java client default; this is what CI runs). See docs/runbook.md.
verify:
	mvn -f service/pom.xml verify

# Load sample data — wired in Phase 8 (data/seed).
seed:
	@echo "seed: not yet implemented — wired in Phase 8 (data/seed)"

# Run the demo (Postman/newman sequence) — wired in Phase 8.
demo:
	@echo "demo: not yet implemented — wired in Phase 8 (postman/)"

# Validate the CDK — wired in Phase 7 (becomes: cd infra && npm ci && npx cdk synth).
synth:
	@echo "synth: not yet implemented — CDK lands in Phase 7 (infra/)"
