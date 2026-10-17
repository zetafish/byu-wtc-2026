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

- **Backend:** Clojure (`deps.edn`), one process, which also runs the scheduler. Libraries:
  - http-kit: the server, and `org.httpkit.client` for adapters' HTTP calls;
  - reitit and ring: routing;
  - jsonista: JSON;
  - malli: model schemas;
  - next.jdbc, HikariCP and postgresql: the store;
  - tools.logging with logback.

  There's no CSV library. Add one to `deps.edn` if an adapter needs it.
- **Frontend:** ClojureScript, with shadow-cljs 3.5.5, Reagent 2.0.1 and React 18.3.1. The charts are hand-built SVG; there's no chart library.

## Data model (`wtc.model`)

Every adapter must produce this shape. A source returns a full **snapshot**, never a delta.

- **Country:** `{:code "NED" :name "Netherlands"}`.
- **Runner:** `{:id "NED-jan-jansen" :name … :country "NED" :bib …}`.
  - Ids must be the same across sources, because merging is per `[runner-id lap]`.
  - Using the roster's ids (`<IOC code>-<slug>`) gets roster enrichment for free.
- **Lap:** `{:runner-id :lap :status :finished|:dnf :seconds}`. Lap n runs from start+(n−1) h to start+n h.

`sanitize` drops invalid rows, never the whole snapshot. `merge-datasets` merges the per-source datasets; the higher `:priority` wins per `[runner-id lap]`.

### What an adapter does and what `wtc.race` does

| Adapter (normalise the source) | `wtc.race` (on every derive) |
|---|---|
| Map country names to IOC codes, and names to roster runner ids | Credit gaps below the last finished lap as `:inferred` |
| Turn timestamps or durations into `{:lap :status :seconds}` | Mark runners with no result for a settled lap as out (`:missing`) |
| A finish in the first ~30 min of an hour → the previous lap | Mark `:seconds` > 3600 as `:suspect` and blank the time |
| A turn-back (an implausibly short "lap") → `:dnf`. Only for timing feeds; the official feed reports DNFs directly | Flag finished laps under 1800 s as `:too-fast`, keeping the time and the credit |
| | Apply the team rule, rankings and phase |
| No result yet (for example a blank cell) → **no lap row at all**, never a placeholder | |

**Runner ids:**
- The roster ids are `<IOC code>-<slug of the name>`. The slug function lives in `scripts/fetch_roster.clj`, which is babashka, so it isn't on the JVM classpath.
- An adapter should resolve names against the roster EDN rather than re-slugging. Copy `slug` into `wtc.roster` if it needs it.
- The IOC code map for country names is `codes` in the same script.
- A runner whose id isn't in the roster still works, but gets no `:rank` or `:best-yards`.

## Backend namespaces

- **`wtc.source`:** the `Source` protocol (`fetch [this {:start :now}]`) and the `create` multimethod on `:type`. To add an adapter:
  1. create `wtc.source.<name>`;
  2. add a `defmethod source/create`;
  3. require it in `wtc.main`;
  4. add it to `SOURCES`.

  `SOURCES` *replaces* the default mock, so leave the mock out of it when a real adapter is live; otherwise mock laps merge in wherever the real source has no row. Compose passes `SOURCES` through from the shell or `.env`. Put adapter tests in `test/wtc/source_test.clj`, next to `mock-source`.
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
    - Finished laps under 1800 s (`too-fast-seconds`) get `:too-fast true`. They still count, and their time is kept.
  - **The team rule (`team-result`):** the first settled lap with fewer than 2 finishers ends the country.
    - 1 finisher gives `{:after lap :winner id}`, and that runner gets `:status :finished :reason :winner`.
    - 0 finishers give `{:after (dec lap) :winner nil}`.
  - **Countries:** each gets `:active`, `:runners`, `:total-laps`, `:rank` (by total laps, then active runners, then code), `:eliminated-after` and `:winner` `{:id :name :laps}`.
  - **Runners:** each gets `:status` (`:running`, `:out` or `:finished`), `:reason` (`:dnf`, `:missing` or `:winner`), `:laps`, `:started` and `:history`. History is a vector of `{:lap :status :seconds}`, optionally with `:inferred true`, `:suspect true` or `:too-fast true`; `:seconds` is nil for inferred or suspect laps.
    - `:started` (laps started) drives the active-runner counts. For a running runner it's max(laps, current lap); for a finished runner it's laps; for a runner who is out it's laps + 1.
  - **Phase:** `:pre`, `:live` or `:finished`. When finished, `:final-lap` is the last lap anyone started, and `:current-lap` and `:settled-lap` are capped there, so the chart axes stop growing. `:race-laps` is the race length: the last lap anyone completed, which is `:final-lap` with a winner and one less when the last runners all DNF.
- **`wtc.ingest`:** a single-thread scheduler.
  - Each source is isolated, with a timeout, 3 attempts and exponential backoff. A failed fetch keeps the last good dataset live.
  - The state atom holds `:datasets` (live) and `:persisted`. The DB write is retried every tick until they're equal.
  - On boot, the datasets are reloaded from the store.
  - `refresh!` merges, enriches and derives.
  - `make-clock` provides real time, or the fixed moment of a demo race: state `:sim` `{at end-lap}`, there only when `CLOCK_START` is set. A demo race stands still until `control-sim` moves it (jump to a lap, or step k laps), always to a moment with the previous lap settled. `sim-end-lap` works out the demo race's last lap once at boot (the sources fetched as if a month had passed), for the slider. In a demo every API response is `Cache-Control: no-store`. The position lives in memory only, so an app restart puts the race back at `CLOCK_START`.
- **`wtc.store`:** the `Store` protocol, plus `MemoryStore`. **`wtc.store.postgres`** creates its tables on start:
  - `countries`, `runners` and `laps`, all keyed by `source`;
  - `fetches`, a log of every fetch with its duration, ok/error, laps and rejected rows.

  The API never reads from Postgres.
- **`wtc.metrics`:** visualisation queries, in a Prometheus-like shape: `{:status "success" :data {:resultType "matrix" :xAxis "lap" :result [{:metric {...} :values [[x y]…]}]}}`. **To add a visualisation, add an entry to `registry`.** The current entries:
  - `runners_started`
  - `team_score`
  - `team_score_gap`: team score minus the leader's at each lap; the leader is taken over all countries, so a line doesn't move when others are compared
  - `attrition`
  - `lap_time_seconds`
  - `lap_time_median_seconds`: the field's median lap time per lap (the runner dialog's dashed line)
  - `countries_by_active_runners`

  **The shape of an entry:** `"name" {:doc "…" :x "lap" :fn (fn [derived params] …)}`.
  - `:fn` gets the derived state (see `wtc.race` above) and `params` `{:by "country"|nil :countries #{codes} :runners #{ids} :lap n}`. Empty sets mean "all".
  - It returns a seq of `{:metric {:__name__ … labels…} :values [[x y] …]}`.
  - The private helpers `select` (filter runners by params) and `grouped` (one series per country when `by=country`) do most of the work.
  - There's no metrics test file yet. `query-shape` in `test/wtc/source_test.clj` checks the response shape, and new metric tests can go there.
- **`wtc.http`:** the API.
- **`wtc.main`:** reads the config from env vars, wires the system and starts the server and the scheduler.

## API

All routes are `GET`, served from the in-memory derived state. Responses carry `Cache-Control: public, max-age=15, s-maxage=60, stale-while-revalidate=300`; `health` and errors are `no-store`. This replaces the Redis layer from the original brief.

| Route | Returns |
|---|---|
| `/api/health` | `{:ok :fetches}` |
| `/api/race` | `start now phase current-lap settled-lap final-lap race-laps runners running fetches simulation` (`simulation` = `{mock-data, controls, end-lap}`, only with a demo race or the mock source; the header then shows the simulation panel, with controls for a demo race, and the brand badge reads "Sim") |
| `POST /api/sim` | demo race only (404 otherwise). JSON body `{"action":"jump","lap":n}` (1–500, clamped to the last lap) or `{"action":"step","by":k}` (−24..24). Re-ingests before it responds. |
| `/api/countries` | countries sorted by rank |
| `/api/runners?country=NED&history=true` | runners by laps; `history` adds the per-lap history |
| `/api/runners/:id` | one runner, with history |
| `/api/metrics` | the metric registry, with docs |
| `/api/query?metric=…&by=country&countries=A,B&runners=x,y&lap=n` | one metric result |

## Frontend (`frontend/`)

- **`wtc.app`:** a single Reagent `db` atom. It polls `/api/race`, `/api/countries` and `/api/runners` every 60 s, then the panel queries. A demo race doesn't move by itself, so it isn't polled. While it plays (in the browser, not on the server), each tick POSTs a one-lap step and reloads, at the interval picked in the panel (2 s to 60 s, default 10 s, kept in prefs). It stops at the last lap. Each tab steps on its own, so two playing tabs step twice as fast.
  - The views are in `views`; the URL hash selects the view.
  - The seat ranking and the leaderboard positions are computed client-side from `/api/runners` (`ranked`).
  - `/api/runners` rows carry `:last-seconds`, `:fastest-seconds` and `:total-seconds` (the last and fastest timed laps, and their sum) instead of the history. Sparkline data comes from one unfiltered `lap_time_seconds` query (all runners), or, with a country filter on the leaderboard, queries for those countries plus the starred runners. A row's detail chart fetches `/api/runners/:id`.
  - `:zoom` holds the panel id shown big. `zoom-dialog` renders `[panel p true]` in a modal `<dialog>`; `chart/line-chart` and `bar-chart` take `:size :normal|:big`.
  - Each round of chart queries gets a sequence number (`fetch-seq`), and responses from an older round are dropped, so quick clicks can't leave stale charts.
  - Preferences are kept in localStorage under `wtc-prefs`, with every access in try/catch.
- **`wtc.panels`:** the list of charts. **Adding a chart means adding a backend metric to `wtc.metrics/registry`, then a map here.** The panel keys:
  - `:id`, `:title`, `:subtitle`, `:metric`, `:kind` (`:line` or `:bar`), `:x-label`, `:y-label`;
  - either `:per-country? true` (one series per selected country, queried with `by=country`) or `:series-label :k` (one series per value of label `:k`, with `:series-order` and `:series-names`);
  - optionally `:y-format :mmss` (seconds as m:ss) and `:y-zero? false`;
  - optionally `:variants [{:label :metric :subtitle :y-label} …]`: switchable views of one panel, shown as a segmented control. The chosen variant's keys override the panel's, the first is the default, and the choice is remembered per browser. Results are cached per `[panel-id metric]`, so switching back is instant.

  `:y-zero?` (default true) keeps 0 on the y-axis, from below for counts or from above for negative values like gaps.

  A new formatter goes in `y-formats` in `wtc.app`. All charts render above the countries table, with the `:per-country?` charts first. Those stay empty until countries are added in the Compare row.
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
| `CLOCK_START` | real time | a demo race standing at this moment until the simulation panel moves it |
| `SOURCES` | `[{:id :mock :type :mock :priority 0}]` | an EDN vector of source configs |

## Local development

- **`docker-compose.yml`** has three services, and no database:
  - `app`: the backend on :8090.
  - `web`: the nginx-served release build on :8091. It uses `resolver 127.0.0.11` with a variable upstream, so a backend restart doesn't leave a stale IP.
  - `web-dev`: `shadow-cljs watch` with hot reload on :8092, and the shadow-cljs UI on :9630.
- **Database:** always Neon, also locally. `DATABASE_URL` in `.env` points at a Neon branch (e.g. `demo`, never the production branch); compose and `make run` refuse to start without it. "Reset from parent" in the Neon console resets the branch. `make run-mem` uses the in-memory store.
- **Ports** avoid 8080 and 8787, which local `bb` servers often use.
- **`DEMO=1`** writes `CLOCK_START` (default `2026-10-18T18:00:00Z`, lap 31) to `.env`, which compose reads, so a plain `docker compose up` is a demo too. A restart puts the race back at `CLOCK_START`.
- **REPL:** `make repl` starts nREPL on 7888; then run `(user/start)` or `(user/restart)` from `dev/user.clj`.
- **Tests** are in `test/wtc/`. `race_test.clj` encodes the confirmed team-rule scenarios, including the Belgium case.

## Deployment

- **Backend:** the `Dockerfile` image, run as a single always-on container on Caasify. **Run exactly one instance**, because the scheduler and the derived state are in-process.
- **Database:** Neon Postgres, via `DATABASE_URL`.
- **Frontend:** Vercel's build image has no JVM, so the frontend is built locally and deployed prebuilt (`make web-deploy`). Set `BACKEND_HOST` in `frontend/vercel.json` first.
