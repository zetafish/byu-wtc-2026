# Architecture

How the site is built. `REQUIREMENTS.md` describes what it does, and `DESIGN.md` how it looks.

## Overview

```
sources (adapters) ──fetch every 60 s──▶ model/sanitize ──▶ per-source dataset ──▶ memory + Postgres
                                                               │
                         model/merge-datasets (by priority) ◀──┘
                                       │
                                roster/enrich
                                       │
                                race/derive-state  (pure; all domain rules)
                                       │
                        in-memory derived state ──▶ /api/* (JSON, CDN cache headers)
                                                         │
                       Vercel CDN (rewrite /api/* → Caasify) ──▶ ClojureScript SPA
```

- **Backend:** Clojure, built with `deps.edn` and run by http-kit, with reitit for routing and jsonista for JSON. It's a single process, and it runs the scheduler itself.
- **Frontend:** ClojureScript, with shadow-cljs 3.5.5, Reagent 2.0.1 and React 18.3.1. The charts are hand-built SVG; there's no chart library.

## Data model (`wtc.model`)

Every adapter must produce this shape. A source returns a full **snapshot**, never a delta.

- **Country:** `{:code "NED" :name "Netherlands"}`.
- **Runner:** `{:id "NED-jan-jansen" :name … :country "NED" :bib …}`.
  - Ids must be the same across sources, because merging is per `[runner-id lap]`.
  - Using the roster's ids (`<IOC code>-<slug>`) gets roster enrichment for free.
- **Lap:** `{:runner-id :lap :status :finished|:dnf :seconds}`. Lap n runs from start+(n−1) h to start+n h.

`sanitize` drops invalid rows, never the whole snapshot. `merge-datasets` merges the per-source datasets; the higher `:priority` wins per `[runner-id lap]`.

## Backend namespaces

- **`wtc.source`:** the `Source` protocol (`fetch [this {:start :now}]`) and the `create` multimethod on `:type`. To add an adapter:
  1. create `wtc.source.<name>`;
  2. add a `defmethod source/create`;
  3. require it in `wtc.main`;
  4. add it to `SOURCES`.
- **`wtc.source.mock`:** a deterministic simulation per `:seed` of the real roster: the team members (rank ≤ 15) from `resources/roster/wtc-2026.edn`.
  - Each runner gets a lap capacity drawn around their `:best-yards` (±~25%), and the mock applies the team rule itself.
  - A fetch reveals only what has happened by `:now`, and the generated race is cached per start time.
  - Options:
    - `:countries ["NED" …]`
    - `:missing-rate` (0.01): laps never registered
    - `:silent-rate` (0.3): DNFs never reported
    - `:failure-rate`: fetches that throw
    - `:latency-ms`
    - `:delay-minutes`
- **`wtc.roster`:** static reference data (`:best-yards`, `:rank`) from the roster EDN. `enrich` merges it into the runners by id, and source fields win.
- **`wtc.race`:** `derive-state`, which is pure and holds all the domain rules. Who is in or out is computed on read and never stored, so late or corrected data fixes itself on the next derive.
  - **Lap state:**
    - Laps are credited up to the last finished lap. Gaps below it are missed registrations, marked `:inferred`.
    - A *settled* lap is one whose deadline + `GRACE_SECONDS` has passed. A runner with no result for a settled lap is out with `:missing`, and `:dnf` takes precedence.
    - Lap times over 3600 s become `:suspect` with `:seconds nil`.
  - **The team rule (`team-result`):** the first settled lap with fewer than 2 finishers ends the country.
    - 1 finisher gives `{:after lap :winner id}`, and that runner gets `:status :finished :reason :winner`.
    - 0 finishers give `{:after (dec lap) :winner nil}`.
  - **Countries:** each gets `:active`, `:runners`, `:total-laps`, `:rank` (by total laps, then active runners, then code), `:eliminated-after` and `:winner` `{:id :name :laps}`.
  - **Runners:** each gets `:status` (`:running`, `:out` or `:finished`), `:reason`, `:laps`, `:started` and `:history`.
    - `:started` (laps started) drives the active-runner counts. For a running runner it's max(laps, current lap); for a finished runner it's laps; for a runner who is out it's laps + 1.
  - **Phase:** `:pre`, `:live` or `:finished`. When finished, `:final-lap` is the last lap anyone started, and `:current-lap` and `:settled-lap` are capped there, so the chart axes stop growing. `:race-laps` is the race length: the last lap anyone completed, which is `:final-lap` with a winner and one less when the last runners all DNF.
- **`wtc.ingest`:** a single-thread scheduler.
  - Each source is isolated, with a timeout, 3 attempts and exponential backoff. A failed fetch keeps the last good dataset live.
  - The state atom holds `:datasets` (live) and `:persisted`. The DB write is retried every tick until they're equal.
  - On boot, the datasets are reloaded from the store.
  - `refresh!` merges, enriches and derives.
  - `make-clock` provides real time, or a demo clock (`CLOCK_START`, `CLOCK_SPEED`, `CLOCK_ANCHOR`).
- **`wtc.store`:** the `Store` protocol, plus `MemoryStore`. **`wtc.store.postgres`** creates its tables on start:
  - `countries`, `runners` and `laps`, all keyed by `source`;
  - `fetches`, a log of every fetch with its duration, ok/error, laps and rejected rows.

  The API never reads from Postgres.
- **`wtc.metrics`:** visualisation queries, in a Prometheus-like shape: `{:status "success" :data {:resultType "matrix" :xAxis "lap" :result [{:metric {...} :values [[x y]…]}]}}`. **To add a visualisation, add an entry to `registry`.** The current entries:
  - `runners_started`
  - `team_score`
  - `attrition`
  - `lap_time_seconds`
  - `countries_by_active_runners`
- **`wtc.http`:** the API.
- **`wtc.main`:** reads the config from env vars, wires the system and starts the server and the scheduler.

## API

All routes are `GET`, served from the in-memory derived state. Responses carry `Cache-Control: public, max-age=15, s-maxage=60, stale-while-revalidate=300`; `health` and errors are `no-store`. This replaces the Redis layer from the original brief.

| Route | Returns |
|---|---|
| `/api/health` | `{:ok :fetches}` |
| `/api/race` | `start now phase current-lap settled-lap final-lap race-laps runners running fetches` |
| `/api/countries` | countries sorted by rank |
| `/api/runners?country=NED&history=true` | runners by laps; `history` adds the per-lap history |
| `/api/runners/:id` | one runner, with history |
| `/api/metrics` | the metric registry, with docs |
| `/api/query?metric=…&by=country&countries=A,B&runners=x,y&lap=n` | one metric result |

## Frontend (`frontend/`)

- **`wtc.app`:** a single Reagent `db` atom. It polls `/api/race`, `/api/countries` and `/api/runners` every 60 s, then the panel queries.
  - The views are in `views`; the URL hash selects the view.
  - The seat ranking is computed client-side from `/api/runners`.
  - Preferences are kept in localStorage under `wtc-prefs`, with every access in try/catch.
- **`wtc.panels`:** the list of charts. **Adding a chart means adding a backend metric to `wtc.metrics/registry`, then a map here** with:
  - `:metric`
  - `:kind` (`:line` or `:bar`)
  - either `:per-country?` (one series per selected country) or `:series-label` (series from a metric label)
- **`wtc.chart`:** generic `line-chart`, `bar-chart`, `data-table` and `sparkline`. All of them take `[{:key :label :color :values [[x y]]}]`.
- **API calls are always same-origin `/api/*`.**
  - Locally, nginx (compose `web`) or the shadow-cljs dev server proxies them. `wtc.dev/proxy-api?` limits the dev proxy to `/api/*`.
  - In production, the Vercel rewrite does.

  There is no CORS.

## Roster pipeline

`scripts/fetch_roster.clj` (babashka) turns the raw JSON in `roster/` into `resources/roster/wtc-2026.edn`.
- **Source:** the endpoint behind the official roster page, `https://bigsbackyardultra.com/wp-json/byu-wtt/v1/selection?cycle=2024_2026&country=…`. It takes about 3–4 s per country and mostly handles requests one at a time; the script runs 6 in parallel.
- **Cache:** `roster/` is a read-through cache, with `_.json` for the country list and one `<Country>.json` per country. `make roster` fetches only missing countries; `make roster-refresh` re-fetches all of them (~2 min).
- **Output:**
  - Ranks 1–15 are `:role :team`, 16 and up `:reserve`.
  - Ids are `<IOC code>-<slug>`; CJK names keep their characters.
  - `display-names` overrides the shown name of a country; for now only AIN → "Individual Neutral Athletes (Russia)".
  - Duplicate names in a country keep the best rank, and the others go to `:dropped-duplicates`.
  - `:fetched-at` is the newest raw file's modification time.

## Configuration (env vars, `wtc.main/config`)

| Var | Default | |
|---|---|---|
| `PORT` | 8080 (8090 via the Makefile) | |
| `DATABASE_URL` | none (in-memory store) | Neon `postgres://…` or a `jdbc:` URL |
| `RACE_START` | `2026-10-17T12:00:00Z` | |
| `POLL_SECONDS` | 60 | |
| `GRACE_SECONDS` | 900 | |
| `FETCH_TIMEOUT_MS` | 20000 | per fetch attempt |
| `CLOCK_START` / `CLOCK_SPEED` / `CLOCK_ANCHOR` | real time | demo clock: reads `CLOCK_START` at wall time `CLOCK_ANCHOR` (default: boot), running `CLOCK_SPEED`× |
| `SOURCES` | `[{:id :mock :type :mock :priority 0}]` | an EDN vector of source configs |

## Local development

- **`docker-compose.yml`** has four services:
  - `db`: postgres:17-alpine on `localhost:55432`; user, password and database are all `wtc`.
  - `app`: the backend on :8090.
  - `web`: the nginx-served release build on :8091. It uses `resolver 127.0.0.11` with a variable upstream, so a backend restart doesn't leave a stale IP.
  - `web-dev`: `shadow-cljs watch` with hot reload on :8092, and the shadow-cljs UI on :9630.
- **Ports** avoid 8080 and 8787, which local `bb` servers often use.
- **`DEMO=1`** writes `CLOCK_START`, `CLOCK_SPEED` and `CLOCK_ANCHOR` to `.env`, which compose reads, so restarts continue the same simulated race. At 60× the mock race ends about 2 h of wall time after the anchor.
- **REPL:** `make repl` starts nREPL on 7888; then run `(user/start)` or `(user/restart)` from `dev/user.clj`.
- **Tests** are in `test/wtc/`. `race_test.clj` encodes the confirmed team-rule scenarios, including the Belgium case.

## Deployment

- **Backend:** the `Dockerfile` image, run as a single always-on container on Caasify. **Run exactly one instance**, because the scheduler and the derived state are in-process.
- **Database:** Neon Postgres, via `DATABASE_URL`.
- **Frontend:** Vercel's build image has no JVM, so the frontend is built locally and deployed prebuilt (`make web-deploy`). Set `BACKEND_HOST` in `frontend/vercel.json` first.
