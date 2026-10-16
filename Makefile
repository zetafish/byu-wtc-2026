.DEFAULT_GOAL := help
.PHONY: help test run run-mem repl up down logs reset db-up db-down psql uber image clean

PORT ?= 8090
LOCAL_DB := postgres://wtc:wtc@localhost:55432/wtc

# DEMO=1 starts the app clock 30 h into the race at 60x speed, so the mock
# has data before the real start. Override with CLOCK_START / CLOCK_SPEED.
ifdef DEMO
export CLOCK_START ?= 2026-10-18T18:00:00Z
export CLOCK_SPEED ?= 60
endif
export PORT

help: ## List targets
	@grep -E '^[a-z-]+:.*## ' $(MAKEFILE_LIST) | awk -F':.*## ' '{printf "  %-10s %s\n", $$1, $$2}'

test: ## Run tests; T=wtc.race-test or T=wtc.race-test/team-rule for one
ifeq ($(T),)
	clojure -M:test
else ifneq ($(findstring /,$(T)),)
	clojure -M:test -v $(T)
else
	clojure -M:test -n $(T)
endif

run: db-up ## Run the backend on the JVM against the compose Postgres
	DATABASE_URL=$(LOCAL_DB) clojure -M:run

run-mem: ## Run the backend on the JVM with the in-memory store
	clojure -M:run

repl: ## Start nREPL on 7888 (then (user/start))
	clojure -M:dev:nrepl

up: ## Build and run the full stack (Postgres + app) in Docker
	docker compose up --build -d
	@echo "http://localhost:$(PORT)/api/race"

down: ## Stop the stack (keeps the data)
	docker compose down

logs: ## Follow the app logs
	docker compose logs -f app

reset: ## Stop the stack and delete the database volume
	docker compose down -v

db-up: ## Start only Postgres
	docker compose up -d --wait db

db-down: ## Stop Postgres
	docker compose stop db

psql: ## Open psql on the compose Postgres
	docker compose exec db psql -U wtc -d wtc

uber: ## Build target/wtc.jar
	clojure -T:build uber

image: ## Build the production Docker image (tag wtc)
	docker build -t wtc .

clean: ## Remove build output
	rm -rf target .cpcache
