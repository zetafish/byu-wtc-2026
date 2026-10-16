# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Live site for the **2026 Big's Backyard Ultra World Team Championship**, which starts **17 Oct 2026 at 14:00 CEST (12:00Z)**. The site only needs to be live for about 7 days. `SPEC.md` holds the original requirements.

## Commands

All common commands are in the `Makefile`. Run `make` to list them.

```sh
make test                          # all tests
make test T=wtc.race-test          # one namespace
make test T=wtc.race-test/team-rule  # one test
make up DEMO=1                     # Postgres + app in Docker, http://localhost:8090/api/race
make run DEMO=1                    # app on the JVM against the compose Postgres (port 8090)
make run-mem                       # app on the JVM, in-memory store, no Docker
make repl                          # nREPL on 7888, then (user/start) / (user/restart)
make psql | logs | down | reset    # reset also deletes the DB volume
make uber | image                  # target/wtc.jar | production image `wtc`
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

Data flows: **sources → `model/sanitize` → per-source dataset (memory + Postgres) → `model/merge-datasets` by priority → `race/derive-state` → API**.

- **`wtc.model`**: the data model every adapter must produce. A source returns a full *snapshot* (countries, runners, laps), never a delta. A lap is `{:runner-id :lap :status :finished|:dnf :seconds}`, and lap n runs from start+(n-1)h to start+n h. Invalid rows are dropped, never the whole snapshot. Runner ids must match across sources, because merging is per `[runner-id lap]` and the higher `:priority` wins.
- **`wtc.source`**: the `Source` protocol (`fetch [this {:start :now}]`) and the `create` multimethod on `:type`. To add an adapter: create `wtc.source.<name>`, add a `defmethod source/create`, require it in `wtc.main`, and add it to `SOURCES`. `wtc.source.mock` is a deterministic simulation per seed. It applies the team rule itself, and it can inject missed registrations, unreported DNFs, failed fetches and latency.
- **`wtc.race`**: pure functions, with all the domain rules. Who is in or out is computed on read, so late or corrected data fixes itself on the next derive. Don't store derived status.
  - Laps are credited up to the last finished lap. Gaps below it are missed registrations, marked `:inferred`.
  - A runner with no result for a *settled* lap (deadline + `GRACE_SECONDS` passed) is out with `:missing`.
  - `:dnf` takes precedence. Then the **team rule**: once fewer than 2 runners of a country finish lap L, that country's remaining runner is out (`:team`), even if their later laps also show as missing.
  - Lap times > 3600 s become `:suspect` with `:seconds nil`.
  - `:started` (laps started) drives the active-runner counts.
  - Countries rank by total laps (team score).
- **`wtc.ingest`**: a single-thread scheduler. Each source is isolated, with a timeout, 3 attempts and exponential backoff. If a fetch fails, the last good dataset stays live. The DB write is retried every tick until the persisted copy equals the live one (`:persisted` vs `:datasets` in the state atom). On boot, the datasets are reloaded from the store.
- **`wtc.store`**: the `Store` protocol, plus `MemoryStore`. `wtc.store.postgres` creates its tables on start; rows are keyed by `source`, and every fetch is logged in `fetches`. Postgres exists for durability and later statistics. The API never reads from it.
- **`wtc.metrics`**: visualisation queries in a Prometheus-like shape: `{:status "success" :data {:resultType "matrix" :xAxis "lap" :result [{:metric {...} :values [[x y]…]}]}}`. **To add a visualisation, add an entry to `registry`.** The frontend renders panels generically from this shape.
- **`wtc.http`**: reitit routes under `/api` (`health race countries runners runners/:id metrics query`). Responses come from the in-memory derived state and carry CDN `Cache-Control` headers. That caching replaces the Redis layer from `SPEC.md`.

## Deployment

- **Backend:** the Docker image, run as a single always-on container on Caasify. Run exactly one instance, because the scheduler runs in-process.
- **Database:** Neon Postgres.
- **Frontend:** the ClojureScript frontend (not built yet) goes on Vercel as static files, with `/api/*` rewritten to the Caasify URL so the Vercel CDN caches API responses.

## Domain lessons from `../upfront-live` (an earlier BYU site; its CLAUDE.md is in Dutch)

Timing data is unreliable:
- registrations get missed, so one recorded time can span hours;
- a runner who turns back shows up as a short "lap", which should count as a DNF;
- a finish in the first ~30 min of an hour belongs to the previous loop.

Real adapters must normalise these into the model above before handing data on.
