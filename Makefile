.PHONY: setup lint test run-order bench audit ci

setup:
	mvn -B -q -DskipTests install

lint:
	mvn -B -q -DskipTests compile

# All three services against a real PostgreSQL started from embedded binaries (no Docker).
test:
	mvn -B -q verify

bench:
	@echo "M3: fault-injection matrix (Toxiproxy) showing every failure ends in a consistent state"

# Java dependencies are watched by Dependabot security alerts (see .github/dependabot.yml).
audit:
	@echo "Java dependencies: Dependabot alerts and weekly update PRs; no extra local scanner in M1"

ci: setup lint test
