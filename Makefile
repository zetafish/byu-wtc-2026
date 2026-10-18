.DEFAULT_GOAL := help
.PHONY: help test run demo-reset demo-off repl up down logs uber image clean .web-deps web-dev web-build web-deploy roster roster-refresh fly-deploy

PORT ?= 8090

# DEMO=1 runs a demo race: it stands at CLOCK_START (default lap 31) until
# the site's simulation panel moves it (step, jump, play). It's saved in
# .env, so plain `docker compose up` and `make run` are demos too. An app
# restart puts the race back at CLOCK_START. `make demo-off` goes back to
# real time.
-include .env
ifdef DEMO
CLOCK_START ?= 2026-10-18T18:00:00Z
endif
export PORT CLOCK_START SOURCES GRACE_SECONDS

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

run: ## Run the backend on the JVM
	clojure -M:run

repl: ## Start nREPL on 7888 (then (user/start))
	clojure -M:dev:nrepl

up: ## Build and run the stack in Docker (app, web, web-dev)
ifdef DEMO
	@{ grep -v '^CLOCK_START=' .env 2>/dev/null; printf 'CLOCK_START=%s\n' $(CLOCK_START); } > .env.tmp && mv .env.tmp .env
endif
	docker compose up --build -d
	@echo "site: http://localhost:8091   hot reload: http://localhost:8092   api: http://localhost:$(PORT)/api/race"

down: ## Stop the stack
	docker compose down

demo-reset: ## Put the demo race back at CLOCK_START (restarts the app)
	docker compose up -d --force-recreate app

demo-off: ## Back to the real-time clock
	@grep -v '^CLOCK_START=' .env > .env.tmp 2>/dev/null; mv .env.tmp .env
	CLOCK_START= docker compose up -d app

logs: ## Follow the app and web logs
	docker compose logs -f app web

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

# One site: the Vercel project bigs-live (bigs-live.vercel.app), with /api
# proxied to the backend PROD_BACKEND_HOST (its Fly.io domain, set in .env).
# It's staged in frontend/.deploy/prod, which keeps its Vercel link.
web-deploy: web-build ## Deploy the frontend to Vercel
	@test -n "$(PROD_BACKEND_HOST)" || { echo "Set PROD_BACKEND_HOST in .env"; exit 1; }
	mkdir -p frontend/.deploy/prod
	rsync -a --delete --exclude js/cljs-runtime --exclude js/manifest.edn frontend/public/ frontend/.deploy/prod/public/
	sed 's/BACKEND_HOST/$(PROD_BACKEND_HOST)/' frontend/vercel.json > frontend/.deploy/prod/vercel.json
	cd frontend/.deploy/prod && { test -d .vercel || npx vercel link --yes --project bigs-live; } && npx vercel deploy --prod

roster: ## Build resources/roster/wtc-2026.edn from roster/*.json
	bb scripts/fetch_roster.clj resources/roster/wtc-2026.edn

roster-refresh: ## Re-fetch + build the whole roster (~2min)
	bb scripts/fetch_roster.clj --refresh resources/roster/wtc-2026.edn

clean: ## Remove build output
	rm -rf target .cpcache frontend/public/js frontend/.shadow-cljs

# The backend on Fly.io, the app bigs-live, configured in fly.prod.toml.
# Fly's remote builder builds the Dockerfile. --ha=false keeps it at one
# machine: the scheduler and the state are in-process.
fly-deploy: ## Deploy the backend to Fly.io
	fly deploy -c fly.prod.toml --ha=false
