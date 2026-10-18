# Architecture

How the site is built. `REQUIREMENTS.md` describes what it does, and `DESIGN.md` how it looks.

## Overview

```
sources (adapters) ──fetch every 60 s──▶ model/sanitize ──▶ per-source dataset ──▶ memory
                                                               │
                         model/merge-datasets (by priority) ◀──┘
                                       │
                                roster/enrich
                                       │
                                race/derive-state  (pure; all domain rules)
                                       │
                        in-memory derived state ──▶ /api/* (JSON, CDN cache headers)
                                                         │
                       Vercel CDN (rewrite /api/* → Fly.io) ──▶ ClojureScript SPA
```

- **Backend:** Clojure (`deps.edn`), one process, which also runs the scheduler. Libraries:
  - http-kit: the server, and `org.httpkit.client` for adapters' HTTP calls;
  - reitit and ring: routing;
  - jsonista: JSON;
  - malli: model schemas;
  - tools.logging with logback.

  `org.clojure/data.csv` parses CSV (the sheet adapter).
- **Frontend:** ClojureScript, with shadow-cljs 3.5.5, Reagent 2.0.1 and React 18.3.1. The charts are hand-built SVG; there's no chart library.

## Data model (`wtc.model`)

Every adapter must produce this shape. A source returns a full **snapshot**, never a delta.

- **Country:** `{:code "NED" :name "Netherlands"}`.
- **Runner:** `{:id "NED-01" :name … :country "NED" :bib …}`.
  - Ids must be the same across sources, because merging is per `[runner-id lap]`.
  - Using the roster's ids (`<IOC code>-<seat>`) gets roster enrichment for free.
- **Lap:** `{:runner-id :lap :status :finished|:dnf :seconds :stop}`. `:stop` (optional) is why a `:dnf` lap ended the race: `:rtc`, `:inc`, `:over` or `:dns`. Lap n runs from start+(n−1) h to start+n h.

`sanitize` drops invalid rows, never the whole snapshot. `merge-datasets` merges the per-source datasets; the higher `:priority` wins per `[runner-id lap]`.

### What an adapter does and what `wtc.race` does

| Adapter (normalise the source) | `wtc.race` (on every derive) |
|---|---|
| Map country names to IOC codes, and names to roster runner ids | Credit gaps below the last finished lap as `:inferred` |
| Turn timestamps or durations into `{:lap :status :seconds}` | Mark runners with no result for a settled lap as out (`:missing`) |
| A finish in the first ~30 min of an hour → the previous lap | Mark `:seconds` > 3600 as `:suspect` and blank the time |
| A turn-back (an implausibly short "lap") → `:dnf`. Only for timing feeds; the official feed reports DNFs directly | None: a short lap is a lap (real yards go under 30 min, the fastest 25:56 in 2026) |
| | Apply the team rule, rankings and phase |
| No result yet (for example a blank cell) → **no lap row at all**, never a placeholder | |

**Runner ids:**
- The roster ids are `<IOC code>-<seat>`, the seat being the roster rank as two digits (`NED-01`, reserves `NED-16` and up). They follow the seat, so they rely on seat = roster rank.
- An adapter must resolve each runner to its id through the roster EDN, by country and name or bib. The name `slug` function in `scripts/fetch_roster.clj` (babashka, not on the JVM classpath) helps match names; copy it into `wtc.roster` if needed.
- The IOC code map for country names is `codes` in the same script.
- A runner whose id isn't in the roster still works, but gets no `:rank` or `:best-yards`.

## Backend namespaces

- **`wtc.source`:** the `Source` protocol (`fetch [this {:start :now}]`) and the `create` multimethod on `:type`. To add an adapter:
  1. create `wtc.source.<name>`;
  2. add a `defmethod source/create`;
  3. require it in `wtc.main`;
  4. add it to `SOURCES`.

  `SOURCES` *replaces* the default mock, so leave the mock out of it when a real adapter is live; otherwise mock laps merge in wherever the real source has no row. Compose passes `SOURCES` through from the shell or `.env`. Put adapter tests in `test/wtc/source_test.clj`, next to `mock-source`.
- **`wtc.source.official`:** the official site's own results feed, `https://wtc2026.bigsbackyardultra.com/api/results`, which its pages poll every 60 s. It's read from the organisers' master sheet, so it's fresher than the published sheet and has full names, lap times and stop reasons. An internal feed, not a promised API.
  - Nations use ISO codes (`NLD`); they're mapped to the roster's (`NED`) by country name. Runners are matched to roster ids by name within their country (Unicode-aware, so Chinese names match too); an unmatched runner keeps their feed id under our code.
  - `laps[i]` is lap i+1 with that many seconds (`null`: no time recorded). A `stop` (`RTC`, `OVER`, `INC`) or `dns` is a `:dnf` on the next lap, with the reason as `:stop` (`:rtc`, `:over`, `:inc`, `:dns`). A stop counts as soon as it is in the feed, even during the lap: it is final (the official site waits until the yard is complete). Excluded nations and hidden runners are left out.
  - A nation's `reportedThrough`, the last yard it has finished entering, becomes the country's `:reported-through`, so `wtc.race` settles laps per country without waiting for the grace period, and never shows a late country as out.
  - Polls send the last ETag, so an unchanged feed is a 304. In practice the feed is rebuilt every minute with a new `generatedAt`, so its ETag changes every minute too.
  - With `:snapshot "/data/latest.json"`, every new copy of the feed is written there as is (through a temp file and a rename). At boot, `wtc.main` asks each source for `wtc.source/saved` and starts from that before the first fetch, so a restart while the feed is down still shows the race. In prod the file is on a Fly volume. A failed write is logged and never fails the fetch.
  - Run it on its own: `SOURCES='[{:id :official :type :official :priority 10}]'`. Not merged with the sheet: sources merge per runner and lap, so a yard the organisers remove from the feed would live on from a lagging or stale sheet copy. If the feed fails, the ingester keeps its last good data; if it's gone for good, switch `SOURCES` to the sheet by hand.
- **`wtc.source.sheet`:** the official leaderboard, the published Google Sheet behind the official site's "Leaderboard" link, read as CSV from its `Lite2` tab (yards per country and seat). A manual fallback for `wtc.source.official`, not run next to it. Config: `SOURCES='[{:id :sheet :type :sheet :priority 10}]'`.
  - Seats are the roster's ranks, so ids are `<code>-<seat>`; names come from the roster. Withdrawn countries (`#` = 0) and seats without a roster runner or yards are skipped.
  - Only yards: n yards become finished laps 1..n without `:seconds`. No DNFs (they're cell colours, lost in CSV), so `wtc.race` marks a runner out once a lap settles without a result. A country that reports late shows as out until it reports; raise `GRACE_SECONDS` to soften that.
  - Not the Leaderboard tab: on race day it lagged, its seat-4 column was always `00:00:00`, and its surname lookup showed `#N/A` for running runners. Lite2 matched the official site's feed to the yard.
  - Google serves different cached copies of a published tab per request, so a copy can be older than the previous one. A copy with fewer yards in total than the last accepted one is rejected as stale (the ingester retries and keeps the last good data).
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
    - A *settled* lap is one whose country has finished reporting it: up to the country's `:reported-through` when its source gives one (the official feed's `reportedThrough`), but never the lap in progress; otherwise, once its deadline + `GRACE_SECONDS` has passed. A runner with no result for a settled lap is out with `:missing`, and `:dnf` takes precedence. The team rule uses the same per-country settled lap. `:settled-lap` in `/api/race` stays the grace-period one.
    - Lap times over 3600 s become `:suspect` with `:seconds nil`.
  - **The team rule (`team-result`):** the first settled lap with fewer than 2 finishers ends the country.
    - 1 finisher gives `{:after lap :winner id}`, and that runner gets `:status :finished :reason :winner`.
    - 0 finishers give `{:after (dec lap) :winner nil}`.
  - **Countries:** each gets `:active`, `:runners`, `:total-laps`, `:farthest` (the most laps of one runner), `:position` (the order: total laps, then farthest, then active runners, then code), `:rank` (the official place: shared, with `:tied true`, only when total laps and farthest are both equal), `:eliminated-after` and `:winner` `{:id :name :laps}`.
  - **Runners:** each gets `:status` (`:running`, `:out` or `:finished`), `:reason` (`:dnf`, `:missing` or `:winner`), `:stop` when the source gave one, `:laps`, `:started` and `:history`. History is a vector of `{:lap :status :seconds :stop}`, optionally with `:inferred true` or `:suspect true`; `:seconds` is nil for inferred or suspect laps.
    - `:started` (laps started) drives the active-runner counts. For a running runner it's max(laps, current lap); for a finished runner it's laps; for a runner who is out it's laps + 1; for a DNS it's 0 (they never started a lap).
  - **Phase:** `:pre`, `:live` or `:finished`. When finished, `:final-lap` is the last lap anyone started, and `:current-lap` and `:settled-lap` are capped there, so the chart axes stop growing. `:race-laps` is the race length: the last lap anyone completed, which is `:final-lap` with a winner and one less when the last runners all DNF.
- **`wtc.ingest`:** a single-thread scheduler.
  - Each source is isolated, with a timeout, 3 attempts and exponential backoff. A failed fetch keeps the last good dataset live.
  - The state atom holds `:datasets` (live) and `:persisted`. The DB write is retried every tick until they're equal.
  - The state lives in memory only. On boot, each source's dataset starts from its own saved snapshot (`wtc.source/saved`; only the official source has one, with `:snapshot`), else empty until the first fetch. The official feed holds the whole race, so nothing else is kept.
  - `refresh!` merges, enriches and derives.
  - `make-clock` provides real time, or the fixed moment of a demo race: state `:sim` `{at end-lap}`, there only when `CLOCK_START` is set. A demo race stands still until `control-sim` moves it (jump to a lap, or step k laps), always to a moment with the previous lap settled. `sim-end-lap` works out the demo race's last lap once at boot (the sources fetched as if a month had passed), for the slider. In a demo every API response is `Cache-Control: no-store`. The position lives in memory only, so an app restart puts the race back at `CLOCK_START`.
- **`wtc.team`:** the team page's data for one country, from the derived state (pure). The chase rests on one fact: each lap a country gains as many yards as it has runners still in, so a rival's `:gap` (theirs minus ours) changes by `:gain` per lap: our pace minus theirs, where the pace is the runners still in, but 0 for a country that is out or down to its last runner (by the team rule that lap is its last), and `:laps-to-pass` is when one becomes strictly ahead, if nobody drops. The chase has every country level, the 3 nearest ahead and behind, and every other country with an overtake at the current runner counts, however far off (a finished country's score is frozen, so passing it only takes 2 runners still going). Each rival carries its gap per lap as `:series`. `:place` per lap uses the official tie-break.
- **`wtc.metrics`:** visualisation queries, in a Prometheus-like shape: `{:status "success" :data {:resultType "matrix" :xAxis "lap" :result [{:metric {...} :values [[x y]…]}]}}`. **To add a visualisation, add an entry to `registry`.** The current entries:
  - `runners_started`
  - `team_score`
  - `team_score_gap`: team score minus the leader's at each lap; the leader is taken over all countries, so a line doesn't move when others are compared
  - `attrition`: runners out at the bell of each lap, per stop code (rtc, inc, over) or reason (dnf, missing); an RTC counts at the lap they finished, DNS not at all
  - `team_rank`: each country's place after every settled lap, by the official ranking
  - `countries_running`: countries still in the race after each settled lap
  - `lap_time_seconds` (with `lap=`, only from that lap on: the Runners view loads all lap times once, then each reload only from the last lap it has, ~8 KB instead of ~100 KB gzipped)
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

All routes are `GET`, served from the in-memory derived state. Responses carry `Cache-Control: public, max-age=15, s-maxage=20, stale-while-revalidate=300`; `health` and errors are `no-store`. This replaces the Redis layer from the original brief.

| Route | Returns |
|---|---|
| `/api/health` | `{:ok :fetches}` |
| `/api/race` | `start now phase current-lap settled-lap final-lap race-laps runners running fetches simulation` (`simulation` = `{mock-data, controls, end-lap}`, only with a demo race or the mock source; the header then shows the simulation panel, with controls for a demo race, and the brand badge reads "Sim") |
| `POST /api/sim` | demo race only (404 otherwise). JSON body `{"action":"jump","lap":n}` (1–500, clamped to the last lap) or `{"action":"step","by":k}` (−24..24). Re-ingests before it responds. |
| `/api/countries` | countries sorted by `:position` |
| `/api/runners?country=NED&history=true` | runners by laps; `history` adds the per-lap history |
| `/api/runners/:id` | one runner, with history |
| `/api/team/:code` | the team page: `country`, `tied-with` (the countries sharing its place, from the same snapshot), `runners` (with `seat-place`/`seat-runners`, last/avg lap, rest margin, history), `over-time` (`runners-in`, `team-score`, `place` per lap) and `chase` (`ahead`, `level`, `behind`: rivals with `gap`, `gain`, `laps-to-pass`, `series` (the gap per lap, up to the last lap both reported: each country's own settled lap, not the race's `settled-lap`, so the chart keeps up with the gaps) and `finishers` (runners who finished each lap, up to its last reported lap); this team's own `finishers`; and `field-active`), and `one-drop` (`wtc.team/one-drop`: `ends?` and the `passes` `[{code, lap}]` one runner less now would add, within 6 laps; null once out or on the last runner); 404 for an unknown code |
| `/api/overtakes` | the coming passes across the field, if nobody drops (`wtc.team/overtakes`): `[{passer, passed, lap, gap, gain}]`, soonest first, from the lap being run now to 6 laps after the last bell; each pair compared at the last lap both reported (an out country at any lap); a country sharing a place that pulls ahead counts as a pass |
| `/api/metrics` | the metric registry, with docs |
| `/api/query?metric=…&by=country&countries=A,B&runners=x,y&lap=n` | one metric result |

## Frontend (`frontend/`)

- **`wtc.app`:** a single Reagent `db` atom. It polls `/api/race`, `/api/countries` and `/api/runners`, then the panel queries: every 20 s from 40 minutes into a lap (runners start finishing) and after the bell for as long as a country still in the race hasn't reported the last lap (its `reported-through`; results can come in late), every 60 s otherwise, and not while the tab is hidden (it reloads as soon as the tab is shown). A demo race doesn't move by itself, so it isn't polled. While it plays (in the browser, not on the server), each tick POSTs a one-lap step and reloads, at the interval picked in the panel (2 s to 60 s, default 10 s, kept in prefs). It stops at the last lap. Each tab steps on its own, so two playing tabs step twice as fast.
  - The views are in `views`; the URL hash selects the view.
  - The seat ranking and the leaderboard positions are computed client-side from `/api/runners` (`ranked`).
  - `/api/runners` rows carry `:last-seconds`, `:fastest-seconds`, `:avg-seconds` and `:total-seconds` (the last and fastest timed laps, their rounded mean, and their sum) instead of the history. Sparkline data comes from one unfiltered `lap_time_seconds` query (all runners), or, with a country filter on the leaderboard, queries for those countries plus the starred runners. A row's detail chart fetches `/api/runners/:id`.
  - `chart/line-chart` and `bar-chart` take `:size :normal|:wide|:big` (viewBox sizes); a panel picks one with `:size`. There is no chart zoom.
  - Each round of chart queries gets a sequence number (`fetch-seq`), and responses from an older round are dropped, so quick clicks can't leave stale charts. The panel queries only run on the Charts view (and when switching to it), so other views cost nothing for them.
  - Preferences are kept in localStorage under `wtc-prefs`, with every access in try/catch.
  - **New version:** a release build runs the shadow-cljs hook `wtc.build/write-version`, which writes `public/version.json` (a hash of `index.html`, `style.css` and `main.js`; git-ignored). The page fetches it at start (`no-store`), checks again every 5 minutes while visible and whenever the tab comes back, and shows a notice with a Reload button when it differs. In hot reload there is no such file, so it never checks.
- **`wtc.panels`:** the list of charts. **Adding a chart means adding a backend metric to `wtc.metrics/registry`, then a map here.** The panel keys:
  - `:id`, `:title`, `:subtitle`, `:metric`, `:kind` (`:line` or `:bar`), `:x-label`, `:y-label`;
  - either `:per-country? true` (one series per selected country, queried with `by=country`) or `:series-label :k` (one series per value of label `:k`, with `:series-order` and `:series-names`);
  - optionally `:y-format :mmss` (seconds as m:ss) and `:y-zero? false`, `:y-invert? true` (a place axis), `:y-field :place|:count` (the y range is the whole field), `:per-yard? true` (x is the yard, so "Last 12" caps it);
  - optionally `:variants [{:label :metric :subtitle :y-label} …]`: switchable views of one panel, shown as a segmented control. The chosen variant's keys override the panel's, the first is the default, and the choice is remembered per browser. Results are cached per `[panel-id metric]`, so switching back is instant.

  `:y-zero?` (default true) keeps 0 on the y-axis, from below for counts or from above for negative values like gaps.

  A new formatter goes in `y-formats` in `wtc.app`. All charts render above the countries table, with the `:per-country?` charts first. Those stay empty until countries are added in the Compare row.
- **`wtc.chart`:** generic `line-chart`, `bar-chart`, `data-table` and `sparkline`. All of them take `[{:key :label :color :values [[x y]]}]`.
- **Analytics:** Vercel Web Analytics, enabled in the Vercel project, loaded by a script tag in `index.html` (`/_vercel/insights/script.js`). No cookies. Its own tracking would count every hash view as `/`, so it's off (`data-disable-auto-track`) and `wtc.app/track-view!` sends `va('pageview', {path, route})` on load and on every change of view: `/race`, `/charts`, `/runners`, `/seats`, `/team/BEL` (route `/team/[code]`). Referrers, countries and devices work as usual.
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
  - Ids are `<IOC code>-<two-digit rank>`. Duplicate people in one country are found by name slug (CJK names keep their characters).
  - `display-names` overrides the shown name of a country; for now only AIN → "Individual Neutral Athletes (Russia)".
  - Duplicate names in a country keep the best rank, and the others go to `:dropped-duplicates`.
  - `:fetched-at` is the newest raw file's modification time.

## Configuration (env vars, `wtc.main/config`)

| Var | Default | |
|---|---|---|
| `PORT` | 8080 (8090 via the Makefile) | |
| `RACE_START` | `2026-10-17T12:00:00Z` | |
| `POLL_SECONDS` | 60 | 5 in prod (`fly.prod.toml`): a new copy of the official feed (rebuilt every minute) waits for our next poll, so its age is up to 60 s plus this. Unchanged polls are a 304 (ETag), so polling often is cheap |
| `GRACE_SECONDS` | 900 | |
| `FETCH_TIMEOUT_MS` | 20000 | per fetch attempt |
| `CLOCK_START` | real time | a demo race standing at this moment until the simulation panel moves it |
| `SOURCES` | `[{:id :mock :type :mock :priority 0}]` | an EDN vector of source configs |

## Local development

- **`docker-compose.yml`** has three services:
  - `app`: the backend on :8090.
  - `web`: the nginx-served release build on :8091. It uses `resolver 127.0.0.11` with a variable upstream, so a backend restart doesn't leave a stale IP.
  - `web-dev`: `shadow-cljs watch` with hot reload on :8092, and the shadow-cljs UI on :9630.
- **Ports** avoid 8080 and 8787, which local `bb` servers often use.
- **`DEMO=1`** writes `CLOCK_START` (default `2026-10-18T18:00:00Z`, lap 31) to `.env`, which compose reads, so a plain `docker compose up` is a demo too. A restart puts the race back at `CLOCK_START`.
- **REPL:** `make repl` starts nREPL on 7888; then run `(user/start)` or `(user/restart)` from `dev/user.clj`.
- **Tests** are in `test/wtc/`. `race_test.clj` encodes the confirmed team-rule scenarios, including the Belgium case.

## Deployment

There is one deployment, prod (a demo site ran before the race and was removed on 18 Oct; the demo race lives on locally with `DEMO=1`): the Fly.io app `bigs-live` (`fly.prod.toml`, real sources, real time) and the Vercel project `bigs-live` (bigs-live.vercel.app).

- **Backend:** the `Dockerfile` image on Fly.io, in `fra`, deployed with `make fly-deploy`. Fly's remote builder builds the image. Port 8080, health check `GET /api/health`, 1 GB shared CPU. There are no secrets; config is in `[env]` of `fly.prod.toml` (`SOURCES`, `GRACE_SECONDS`). **Run exactly one machine, always on** (`--ha=false`, `auto_stop_machines = "off"`), because the scheduler and the derived state are in-process.
- **Volume:** `wtc_data`, 1 GB in `fra`, mounted at `/data` for the official feed's `latest.json`. Create it once before the first deploy with the mount: `fly volumes create wtc_data --region fra --size 1 -a bigs-live`. `fly deploy` won't create it for an existing app, even with `initial_size` in `[mounts]`; it stops with "needs volumes with name 'wtc_data'". It survives deploys and restarts, but isn't replicated; losing it only means starting from the first fetch.
- **Frontend:** Vercel's build image has no JVM, so the frontend is built locally and deployed prebuilt with `make web-deploy`. That stages `frontend/public` in `frontend/.deploy/prod`, with `BACKEND_HOST` in `frontend/vercel.json` replaced by `PROD_BACKEND_HOST` from `.env`, and deploys it to the Vercel project `bigs-live`, linked on the first deploy.
