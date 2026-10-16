.DEFAULT_GOAL := help
.PHONY: help test run demo-reset demo-off run-mem repl up down logs reset db-up db-down psql uber image clean web-deps web-dev web-build web-deploy roster roster-refresh

PORT ?= 8090
LOCAL_DB := postgres://wtc:wtc@localhost:55432/wtc

# DEMO=1 starts the app clock 30 h into the race at 60x speed, so the mock
# has data before the real start. Override with CLOCK_START / CLOCK_SPEED.
# The demo is saved in .env with a fixed CLOCK_ANCHOR (the wall time at which
# the clock read CLOCK_START), so restarts and plain `docker compose up`
# continue the same simulated race. `make demo-reset` starts it over,
# `make demo-off` goes back to real time.
-include .env
ifdef DEMO
CLOCK_START ?= 2026-10-18T18:00:00Z
CLOCK_SPEED ?= 60
CLOCK_ANCHOR ?= $(shell date -u +%Y-%m-%dT%H:%M:%SZ)
endif
export PORT CLOCK_START CLOCK_SPEED CLOCK_ANCHOR

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

up: ## Build and run the full stack in Docker (Postgres, app, web, web-dev)
ifdef DEMO
	@printf 'CLOCK_START=%s\nCLOCK_SPEED=%s\nCLOCK_ANCHOR=%s\n' $(CLOCK_START) $(CLOCK_SPEED) $(CLOCK_ANCHOR) > .env
endif
	docker compose up --build -d
	@echo "site: http://localhost:8091   hot reload: http://localhost:8092   api: http://localhost:$(PORT)/api/race"

demo-reset: ## Restart the demo race from 30 h in (new CLOCK_ANCHOR)
	rm -f .env
	$(MAKE) up DEMO=1 CLOCK_ANCHOR=$$(date -u +%Y-%m-%dT%H:%M:%SZ)

demo-off: ## Back to the real-time clock
	rm -f .env
	CLOCK_START= CLOCK_SPEED=1 CLOCK_ANCHOR= docker compose up -d app

down: ## Stop the stack (keeps the data)
	docker compose down

logs: ## Follow the app and web logs
	docker compose logs -f app web

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

web-deps:
	cd frontend && npm install

web-dev: web-deps ## Frontend hot reload on the host (:8092), proxies /api to :8090
	cd frontend && npx shadow-cljs watch app

web-build: web-deps ## Release build of the frontend into frontend/public
	cd frontend && npx shadow-cljs release app

web-deploy: web-build ## Deploy the prebuilt frontend to Vercel (set BACKEND_HOST in vercel.json first)
	cd frontend && npx vercel deploy --prod

roster: ## Build resources/roster/wtc-2026.edn from roster/*.json, fetching only uncached countries
	bb scripts/fetch_roster.clj resources/roster/wtc-2026.edn

roster-refresh: ## Re-fetch the whole roster into roster/*.json (~2 min, slow endpoint), then build
	bb scripts/fetch_roster.clj --refresh resources/roster/wtc-2026.edn

clean: ## Remove build output
	rm -rf target .cpcache frontend/public/js frontend/.shadow-cljs
