.DEFAULT_GOAL := help
.PHONY: help test run demo-reset demo-off run-mem repl up down logs psql db-reset uber image clean .web-deps web-dev web-build web-deploy roster roster-refresh fly-deploy

PORT ?= 8090
# Local development and `make db-reset` only touch the Neon project bigs-live-demo,
# never the production project bigs-live.
NEON_PROJECT_ID ?= winter-leaf-48380451
NEON_BRANCH ?= local

# DEMO=1 runs a demo race: it stands at CLOCK_START (default lap 31) until
# the site's simulation panel moves it (step, jump, play). It's saved in
# .env, so plain `docker compose up` and `make run` are demos too. An app
# restart puts the race back at CLOCK_START. `make demo-off` goes back to
# real time.
#
# Without DATABASE_URL the backend uses its in-memory store. To use Postgres,
# put DATABASE_URL (the branch `local` of the Neon project bigs-live-demo,
# never the production project bigs-live) in .env.
-include .env
ifdef DEMO
CLOCK_START ?= 2026-10-18T18:00:00Z
endif
export PORT CLOCK_START
export DATABASE_URL
need-db = $(if $(DATABASE_URL),,$(error Set DATABASE_URL in .env to a Neon branch))

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

run: ## Run the backend on the JVM (Neon if DATABASE_URL is set, else in memory)
	clojure -M:run

run-mem: ## Run the backend on the JVM in memory, even if DATABASE_URL is set
	DATABASE_URL= clojure -M:run

repl: ## Start nREPL on 7888 (then (user/start))
	clojure -M:dev:nrepl

up: ## Build and run the stack in Docker (app, web, web-dev); Neon if DATABASE_URL is set
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

db-reset: ## Reset the Neon branch NEON_BRANCH (default local) to its parent, then restart the app
	npx -y neonctl branches reset $(NEON_BRANCH) --parent --project-id $(NEON_PROJECT_ID)
	-docker compose restart app

psql: ## Open psql on DATABASE_URL (Neon); needs a local psql
	$(need-db)psql "$$DATABASE_URL"

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

# Two sites: prod is the Vercel project bigs-live (bigs-live.vercel.app), demo
# is bigs-live-demo (bigs-live-demo.vercel.app). Each has /api
# proxied to its own backend, PROD_BACKEND_HOST or DEMO_BACKEND_HOST (the
# Fly.io domains, set in .env). Each site is staged in frontend/.deploy/<SITE>,
# which keeps its Vercel link.
SITE ?= demo
vercel-project = $(if $(filter prod,$(SITE)),bigs-live,bigs-live-demo)
backend-host = $(if $(filter prod,$(SITE)),$(PROD_BACKEND_HOST),$(if $(filter demo,$(SITE)),$(DEMO_BACKEND_HOST),$(error SITE must be demo or prod)))
web-deploy: web-build ## Deploy the frontend to Vercel: SITE=demo (default) or SITE=prod
	@test -n "$(backend-host)" || { echo "Set $(if $(filter prod,$(SITE)),PROD,DEMO)_BACKEND_HOST in .env"; exit 1; }
	mkdir -p frontend/.deploy/$(SITE)
	rsync -a --delete --exclude js/cljs-runtime --exclude js/manifest.edn frontend/public/ frontend/.deploy/$(SITE)/public/
	sed 's/BACKEND_HOST/$(backend-host)/' frontend/vercel.json > frontend/.deploy/$(SITE)/vercel.json
	cd frontend/.deploy/$(SITE) && { test -d .vercel || npx vercel link --yes --project $(vercel-project); } && npx vercel deploy --prod

roster: ## Build resources/roster/wtc-2026.edn from roster/*.json
	bb scripts/fetch_roster.clj resources/roster/wtc-2026.edn

roster-refresh: ## Re-fetch + build the whole roster (~2min)
	bb scripts/fetch_roster.clj --refresh resources/roster/wtc-2026.edn

clean: ## Remove build output
	rm -rf target .cpcache frontend/public/js frontend/.shadow-cljs

# Two backends on Fly.io, bigs-live-demo and bigs-live, configured in
# fly.<SITE>.toml. Fly's remote builder builds the Dockerfile. --ha=false keeps
# it at one machine: the scheduler and the state are in-process.
fly-deploy: ## Deploy the backend to Fly.io: SITE=demo (default) or SITE=prod
	@test -f fly.$(SITE).toml || { echo "SITE must be demo or prod"; exit 1; }
	fly deploy -c fly.$(SITE).toml --ha=false
