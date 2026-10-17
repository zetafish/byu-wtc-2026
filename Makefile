.DEFAULT_GOAL := help
.PHONY: help test run demo-reset demo-off run-mem repl up down logs db-reset .db-up psql uber image clean .web-deps web-dev web-build web-deploy roster roster-refresh

PORT ?= 8090
LOCAL_DB := postgres://wtc:wtc@localhost:55432/wtc

# DEMO=1 runs a demo race: it stands at CLOCK_START (default lap 31) until
# the site's simulation panel moves it (step, jump, play). It's saved in
# .env, so plain `docker compose up` and `make run` are demos too. An app
# restart puts the race back at CLOCK_START. `make demo-off` goes back to
# real time.
-include .env
ifdef DEMO
CLOCK_START ?= 2026-10-18T18:00:00Z
endif
export PORT CLOCK_START

help: ## List targets
	@grep -hE '^[a-z-]+:.*## ' $(MAKEFILE_LIST) | awk -F':.*## ' '{printf "  %-14s %s\n", $$1, $$2}' 

test: ## Run tests; T=wtc.race-test or T=wtc.race-test/team-rule for one
ifeq ($(T),)
	clojure -M:test
else ifneq ($(findstring /,$(T)),)
	clojure -M:test -v $(T)
else
	clojure -M:test -n $(T)
endif

run: .db-up ## Run the backend on the JVM against the compose Postgres
	DATABASE_URL=$(LOCAL_DB) clojure -M:run

run-mem: ## Run the backend on the JVM with the in-memory store
	clojure -M:run

repl: ## Start nREPL on 7888 (then (user/start))
	clojure -M:dev:nrepl

up: ## Build and run the full stack in Docker (Postgres, app, web, web-dev)
ifdef DEMO
	@printf 'CLOCK_START=%s\n' $(CLOCK_START) > .env
endif
	docker compose up --build -d
	@echo "site: http://localhost:8091   hot reload: http://localhost:8092   api: http://localhost:$(PORT)/api/race"

down: ## Stop the stack (keeps the data)
	docker compose down

demo-reset: ## Put the demo race back at CLOCK_START (restarts the app)
	docker compose up -d --force-recreate app

demo-off: ## Back to the real-time clock
	rm -f .env
	CLOCK_START= docker compose up -d app

logs: ## Follow the app and web logs
	docker compose logs -f app web

.db-up: # start only Postgres, for `run`
	docker compose up -d --wait db

db-reset: ## Delete the database volume and bring the stack back up on an empty DB
	docker compose down -v
	docker compose up -d

psql: ## Open psql on the compose Postgres
	docker compose exec db psql -U wtc -d wtc

uber: ## Build target/wtc.jar
	clojure -T:build uber

image: ## Build the production Docker image (tag wtc)
	docker build -t wtc .

.web-deps:
	cd frontend && npm install

web-dev: .web-deps ## Frontend hot reload on the host (:8092), proxies /api to :8090
	cd frontend && npx shadow-cljs watch app

web-build: .web-deps ## Release build of the frontend into frontend/public
	cd frontend && npx shadow-cljs release app

web-deploy: web-build ## Deploy the prebuilt frontend to Vercel (set BACKEND_HOST in vercel.json first)
	cd frontend && npx vercel deploy --prod

roster: ## Build resources/roster/wtc-2026.edn from roster/*.json
	bb scripts/fetch_roster.clj resources/roster/wtc-2026.edn

roster-refresh: ## Re-fetch + build the whole roster (~2min)
	bb scripts/fetch_roster.clj --refresh resources/roster/wtc-2026.edn

clean: ## Remove build output
	rm -rf target .cpcache frontend/public/js frontend/.shadow-cljs
