# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Unofficial live site for the **2026 Big's Backyard Ultra World Team Championship**, which starts **17 Oct 2026 at 14:00 CEST (12:00Z)**. The site only needs to be live for about 7 days.

## Docs: read these first

- **`REQUIREMENTS.md`**: what the app must do. Race and team rules, rankings, seats, data, views, hosting, decisions and open questions. This is the source of truth for behaviour.
- **`ARCH.md`**: how it's built. Data flow, namespaces, API, config, local stack and deployment.
- **`DESIGN.md`**: how it looks. Visual language, layout and views, components, chart rules and accessibility.

**Keep them current.** When the owner makes or changes a decision, update `REQUIREMENTS.md` in the same change, marked [decided]; mark guesses [assumed] and list them under Open questions. Architecture changes go in `ARCH.md`, look-and-feel changes in `DESIGN.md`.

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

Before the race starts, a real-time clock shows nothing. `DEMO=1` starts the app clock 30 h into the race at 60× speed. `make up DEMO=1` writes these settings to `.env`, with a fixed `CLOCK_ANCHOR` (the wall time at which the clock read `CLOCK_START`). Compose reads `.env`, so restarts and a plain `docker compose up` continue the same simulated race instead of starting it over. At 60× the mock race ends about 2 h of wall time after the anchor and then stays finished. `make demo-reset` starts it over; `make demo-off` returns to real time. `user/start` in `dev/user.clj` does the same, but at normal speed. The local default port is 8090, because 8080 and 8787 are often taken by other local `bb` servers. Compose Postgres is on `localhost:55432` (user, password and database are all `wtc`). Without `DATABASE_URL`, the app uses an in-memory store.

## Checking your work

- **Backend:** `make test`, then `curl localhost:8090/api/race` (look at `phase` and `current-lap`) and `curl 'localhost:8090/api/query?metric=…'`. `docker compose up -d --build app` rebuilds it.
- **Frontend:** there are no frontend tests, so look at it.
  - `web-dev` hot-reloads `frontend/src` at http://localhost:8092. `docker compose logs --tail 5 web-dev` shows compile errors and warnings.
  - Open a view by hash (`/#countries`, `/#runners`, `/#seats`). The comparison charts need countries ticked in the Countries table; nothing is ticked on a first visit. Sparklines on the leaderboard only show when it's filtered.
  - For screenshots, use headless Chrome: `"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" --headless=new --hide-scrollbars --virtual-time-budget=8000 --window-size=1300,900 --screenshot=<scratchpad>/x.png 'http://localhost:8092/#countries'`. Then read the PNG. Phone widths under ~500 px get clipped.
  - The nginx site on :8091 serves the *release* build, so it only changes after `docker compose up -d --build web`.
- **The race state of the local stack:**
  - `make up DEMO=1` writes `.env`, and the Makefile `-include`s it. From then on `make run` and `make run-mem` run the demo clock too, until `make demo-off`.
  - At 60× the demo race finishes about 2 h after its anchor, so a fresh session often finds `phase` `finished`. `make demo-reset` brings it back to mid-race (lap 31).
  - To freeze at a chosen moment, use `CLOCK_START=2026-10-19T12:00:00Z CLOCK_SPEED=1 CLOCK_ANCHOR= docker compose up -d app`. That's lap 49, with real-time speed from there.
  - `CLOCK_START=2026-10-24T14:00:00Z` shows the end of the race.

## Conventions that bite

- **`wtc.race` is pure and holds every domain rule.** Status is derived on read; never store it. The team-rule scenarios in `test/wtc/race_test.clj` are confirmed by the owner, so don't change their expectations without asking.
- **Adapters** normalise the unreliable timing data into `wtc.model` and use the roster's runner ids (see `ARCH.md`).
- **Adding a chart** means adding a metric to `wtc.metrics/registry`, then a map in `frontend/src/wtc/panels.cljs`. `ARCH.md` lists the panel keys; a new axis format also needs an entry in `y-formats` in `wtc.app`.
- **Chart colours** are `--series-1..8` and follow the country, not its rank. Never use the accent or a new hue for a series (see `DESIGN.md`).
- **Run exactly one backend instance.** The scheduler and the state are in-process.
- **Rebuilding one compose service can recreate `app`.** The demo clock survives this because of `.env` and `CLOCK_ANCHOR`.
