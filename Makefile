# OSV-Scanner (https://google.github.io/osv-scanner/) checks every resolved Maven dependency.
OSV ?= osv-scanner

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

# Known vulnerabilities in every resolved Maven dependency, test scope included.
audit:
	mvn -B -q org.cyclonedx:cyclonedx-maven-plugin:2.9.3:makeAggregateBom -DoutputFormat=json -DoutputName=bom -DincludeTestScope=true
	$(OSV) scan source -L target/bom.json

ci: setup lint test
