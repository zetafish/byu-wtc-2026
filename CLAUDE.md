# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Live site for the **2026 Big's Backyard Ultra World Team Championship**, which starts **17 Oct 2026 at 14:00 CEST (12:00Z)**. The site only needs to be live for about 7 days. `SPEC.md` holds the original requirements.

## Commands

All common commands are in the `Makefile`. Run `make` to list them.

```sh
make test                          # all tests
make test T=wtc.race-test          # one namespace
make test T=wtc.race-test/team-rule  # one test
make up DEMO=1                     # Postgres + app + web + web-dev in Docker: site :8091, hot reload :8092, API :8090, shadow-cljs UI :9630
make web-dev                       # hot reload on the host instead (needs a backend on :8090: make run DEMO=1)
make web-build | web-deploy        # frontend release build | deploy prebuilt to Vercel
make run DEMO=1                    # app on the JVM against the compose Postgres (port 8090)
make run-mem                       # app on the JVM, in-memory store, no Docker
make repl                          # nREPL on 7888, then (user/start) / (user/restart)
make psql | logs | down | reset    # reset also deletes the DB volume
make uber | image                  # target/wtc.jar | production image `wtc`
make roster                        # build resources/roster/wtc-2026.edn from roster/*.json; fetches only uncached countries
make roster-refresh                # re-fetch every roster/*.json from the official endpoint (~2 min), then build
```

Before the race starts, a real-time clock shows nothing. `DEMO=1` starts the app clock 30 h into the race at 60× speed. `user/start` in `dev/user.clj` does the same, but at normal speed. The local default port is 8090, because 8080 and 8787 are often taken by other local `bb` servers. Compose Postgres is on `localhost:55432` (user, password and database are all `wtc`). Without `DATABASE_URL`, the app uses an in-memory store.

## Config (env vars, read in `wtc.main/config`)

- `DATABASE_URL`: Neon `postgres://…` or `jdbc:` URL.
- `RACE_START`
- `POLL_SECONDS` (60)
- `GRACE_SECONDS` (900)
- `FETCH_TIMEOUT_MS`
- `CLOCK_START` / `CLOCK_SPEED`
- `SOURCES`: an EDN vector, e.g. `[{:id :mock :type :mock :priority 0 :failure-rate 0.2}]`.

## Architecture

Data flows: **sources → `model/sanitize` → per-source dataset (memory + Postgres) → `model/merge-datasets` by priority → `roster/enrich` → `race/derive-state` → API**.

- **`wtc.model`**: the data model every adapter must produce. A source returns a full *snapshot* (countries, runners, laps), never a delta. A lap is `{:runner-id :lap :status :finished|:dnf :seconds}`, and lap n runs from start+(n-1)h to start+n h. Invalid rows are dropped, never the whole snapshot. Runner ids must match across sources, because merging is per `[runner-id lap]` and the higher `:priority` wins.
- **`wtc.source`**: the `Source` protocol (`fetch [this {:start :now}]`) and the `create` multimethod on `:type`. To add an adapter: create `wtc.source.<name>`, add a `defmethod source/create`, require it in `wtc.main`, and add it to `SOURCES`. `wtc.source.mock` is a deterministic simulation per seed of the real roster: the team members (rank ≤ 15, 1,033 runners) from `resources/roster/wtc-2026.edn`, each with a lap capacity drawn around their `:best-yards` (±~25%). `:countries ["NED" …]` limits it to some countries. The mock applies the team rule itself, and it can inject missed registrations, unreported DNFs, failed fetches and latency. The generated race is cached per start time.
- **`wtc.roster`**: static reference data from `resources/roster/wtc-2026.edn` (`:best-yards`, `:rank`). `ingest/refresh!` merges it into the merged dataset's runners by id before deriving; source fields win. Any adapter that uses the roster's ids gets it for free.
- **`wtc.race`**: pure functions, with all the domain rules. Who is in or out is computed on read, so late or corrected data fixes itself on the next derive. Don't store derived status.
  - Laps are credited up to the last finished lap. Gaps below it are missed registrations, marked `:inferred`.
  - A runner with no result for a *settled* lap (deadline + `GRACE_SECONDS` passed) is out with `:missing`.
  - `:dnf` takes precedence. Then the **team rule**: after each settled lap, count the country's runners who finished it.
    - 2 or more: the country continues.
    - Exactly 1: that runner is the country's winner (`:status :finished`, `:reason :winner`, listed in the country's `:winner`). They don't run an extra solo lap.
    - 0: everyone left is a DNF, and the country has no winner.

    A winner is only declared once the lap has settled, because a partner's DNF is often only known at the deadline. Countries are independent: one country can keep going alone after all others are out (Belgium in 2022), and it ends without a winner if its last two DNF in the same lap.
  - Lap times > 3600 s become `:suspect` with `:seconds nil`.
  - `:started` (laps started) drives the active-runner counts.
  - When no one is running any more, `:phase` is `:finished` and `:final-lap` is the last lap anyone started. `:current-lap` and `:settled-lap` freeze there, so the chart axes stop growing.
  - Countries rank by total laps of all their runners (team score). This is the official ranking.
- **`wtc.ingest`**: a single-thread scheduler. Each source is isolated, with a timeout, 3 attempts and exponential backoff. If a fetch fails, the last good dataset stays live. The DB write is retried every tick until the persisted copy equals the live one (`:persisted` vs `:datasets` in the state atom). On boot, the datasets are reloaded from the store.
- **`wtc.store`**: the `Store` protocol, plus `MemoryStore`. `wtc.store.postgres` creates its tables on start; rows are keyed by `source`, and every fetch is logged in `fetches`. Postgres exists for durability and later statistics. The API never reads from it.
- **`wtc.metrics`**: visualisation queries in a Prometheus-like shape: `{:status "success" :data {:resultType "matrix" :xAxis "lap" :result [{:metric {...} :values [[x y]…]}]}}`. **To add a visualisation, add an entry to `registry`.** The frontend renders panels generically from this shape.
- **`wtc.http`**: reitit routes under `/api` (`health race countries runners runners/:id metrics query`). Responses come from the in-memory derived state and carry CDN `Cache-Control` headers. That caching replaces the Redis layer from `SPEC.md`.

## Roster (`resources/roster/wtc-2026.edn`)

`scripts/fetch_roster.clj` (babashka) builds this file from the raw JSON responses in `roster/`: `_.json` holds the country list, plus one `<Country>.json` per country.
- `roster/` is a read-through cache: `make roster` fetches only countries without a cached file, so it makes no requests once everything is cached.
- `make roster-refresh` re-downloads everything from the endpoint behind the official roster page, `wp-json/byu-wtt/v1/selection?cycle=2024_2026&country=…`. That endpoint takes about 3–4 s per country and handles requests mostly one at a time.
- `:fetched-at` is the newest raw file's modification time.
- Ranks 1–15 are `:role :team`; 16 and up are `:reserve`.
- Runner ids are `<IOC code>-<slugged name>`. CJK names keep their characters.
- Names the official roster lists twice in one country keep the best rank; the others go in `:dropped-duplicates`.

## Frontend (`frontend/`)

ClojureScript built with shadow-cljs, Reagent and React 18. There is no chart library; the charts are hand-built SVG.
- **`wtc.panels`**: the dashboard's panel list. **Adding a chart means adding a backend metric to `wtc.metrics/registry`, then a map here**: `:metric`, `:kind` (`:line` or `:bar`), `:per-country?` (one series per selected country) or `:series-label` (series from a metric label).
- **`wtc.chart`**: generic `line-chart`, `bar-chart` (stacks the series in the order given), `data-table` and `sparkline`. All of them take `[{:key :label :color :values [[x y]]}]`. Charts come with a legend for 2+ series, direct labels for up to 4, a crosshair or per-bar tooltip, and a table toggle.
- **`wtc.app`**: one `db` atom, which polls `/api/*` every 60 s.
  - **Views:** a left menu (a top tab bar below 760 px) switches between Countries, Charts, Runners and Seats (`views`). The view lives in the URL hash (`#charts`), so it survives a reload and can be linked. The header (stats, chips, notices, the finished banner) shows above every view. The Charts view starts with a "Comparing" row of the selected countries; selection itself happens in the Countries view.
  - **Colours:** they follow the country, not its rank. A selected country takes the lowest free slot of `--series-1..8`. At most 8 countries can be selected, because that's how many hues the palette has. Survivors keep their colour when one is deselected.
  - **Stored per browser:** the selection and the starred runners, in localStorage.
- **Seat ranking** (the `seats-card` in `wtc.app`): every runner's seat is their roster `:rank`, 1–15, which `wtc.roster` merges into runners. All runners with the same seat compete across countries, and each seat's winner is invited to the Individual Big's Backyard Ultra. It's computed in the frontend from `/api/runners`. Runners are ordered by laps, and a runner still in is ahead of one who is out with the same laps; equal positions share a rank. Seat states: `open` (2+ still in), `last one in`, `winner`, `tie`.
- **Look:** a race-board style after `../upfront-live`: Big Shoulders Display (uppercase titles and big numbers) and Barlow (body text) from Google Fonts, a sea-mist page with white panels, an orange `--accent` (lap number, focus, row tints), and tinted status pills. The tokens live in `public/css/style.css`, light and dark. Chart series use `--series-1..8`, the validated dataviz palette, re-checked against these surfaces. Don't use the accent or new hues for series.
- **The `web-dev` compose service** always starts with the stack. It runs `shadow-cljs watch` in the Dockerfile's `deps` stage, with `src`, `public` and `shadow-cljs.edn` bind-mounted. `API_URL` points its proxy at `app:8080`. `wtc.dev/proxy-api?` (JVM side) limits the proxy to `/api/*`, so `/` serves `index.html`.
- **API calls** are always same-origin `/api/*`. Locally, nginx (compose `web`) or the shadow-cljs dev server proxies them. In production, the Vercel rewrites in `frontend/vercel.json` do. The browser never makes a cross-origin call, so there's no CORS issue.
- **Vercel's build image has no JVM**, so the frontend is built locally and deployed prebuilt (`make web-deploy`). Set `BACKEND_HOST` in `frontend/vercel.json` to the Caasify host first.

## Deployment

- **Backend:** the Docker image, run as a single always-on container on Caasify. Run exactly one instance, because the scheduler runs in-process.
- **Database:** Neon Postgres.
- **Frontend:** static files on Vercel. `/api/*` is rewritten to the Caasify URL, so the Vercel CDN caches the API responses.

## Domain lessons from `../upfront-live` (an earlier BYU site; its CLAUDE.md is in Dutch)

Timing data is unreliable:
- registrations get missed, so one recorded time can span hours;
- a runner who turns back shows up as a short "lap", which should count as a DNF;
- a finish in the first ~30 min of an hour belongs to the previous loop.

Real adapters must normalise these into the model above before handing data on.
