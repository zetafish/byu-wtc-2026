# Requirements

What the site must do, for the 2026 Big's Backyard Ultra World Team Championship (WTC). This document is the source of truth for behaviour; `ARCH.md` covers how it's built and `DESIGN.md` covers how it looks.

Where a requirement comes from:
- **[brief]**: the original brief (the old `SPEC.md`).
- **[decided]**: decided by the owner during development.
- **[assumed]**: an assumption that still needs confirming. These are also listed under Open questions.

## 1. Goal and scope

- An **unofficial** live statistics site for the WTC 2026. Every page says it's unofficial and may differ from the official standings. [brief]
- The race starts **17 Oct 2026 at 14:00 CEST (12:00Z)**. The site only needs to be live for about 7 days. [brief]
- Visitors come from all over the world, not just the Netherlands, so the site must handle much more traffic than the earlier `../upfront-live`. Latency is out of scope. [brief]
- The site must stay free or nearly free to host (see §7). [brief]
- **Out of scope:**
  - GPS tracking, and showing where runners are within a loop. There is no GPX data, and every country runs its own loop. [brief]
  - "In danger of DNF" warnings during a loop, for the same reason. [brief]

## 2. Race rules

### Laps and being out
- Standard backyard format: lap *n* starts at start + (n−1) h, and a runner must finish it within that hour. Everyone still in the race starts every lap. [brief]
- A runner is **out** when either:
  - a DNF is reported for a lap; or
  - there is no result for a lap after its deadline plus a grace period (15 min, configurable). This is reason "no result".

  A reported DNF takes precedence over "no result". [decided]
- A lap time over 60 min can't be valid. It's kept as "suspect", and its time isn't shown. [decided]
- A finished lap under 30 min is implausibly fast. It's flagged as "too fast", but it still counts and its time is kept. [decided]

### Team rule
Runners of a country can only continue while at least 2 of them are still in. [brief]

Rule, confirmed by the owner: after each lap, count the country's runners who finished it.
- **2 or more:** the country continues.
- **Exactly 1:** that runner is the country's **winner**. They do not run an extra lap alone. Everyone else is out.
- **0:** the country has **no winner**, and everyone left is DNF.

With two runners left (A and B):

| A | B | Result |
|---|---|---|
| starts, finishes | starts, finishes | continue with 2, no winner yet |
| starts, doesn't finish | starts, doesn't finish | stop: no winner, both DNF |
| starts, finishes | starts, doesn't finish | stop: A winner, B DNF |
| starts, finishes | doesn't start | stop: A winner, B DNF |
| starts, doesn't finish | doesn't start | stop: no winner, both DNF |

- A winner is only declared once the lap has passed its deadline plus the grace period, because a partner's DNF often only becomes known at the deadline. [decided]
- Countries are independent. One country can keep going alone after all the others are out. In 2022 Belgium did this, passed 100 laps, and both runners DNF'd on lap 101. [decided]
- The **race is finished** when nobody is running any more. [decided]
- The **race length** ("Race finished after N laps") is the last lap anyone *completed*. [decided]
  - **With a winner:** this is the winner's last lap.
  - **When the last runners all DNF in the same lap:** it's the lap before. In the Belgium case, lap 101 was started but not finished, so the race lasted 100 laps.
- After the finish, the lap counter and the chart axes stop at the last lap anyone *started* (101 in the Belgium case), so that lap's DNFs stay visible.
- How a country's result is shown: [decided]
  - With a winner: "★ *winner name* · lap *N*", where N is the winner's last lap.
  - Without a winner: "out after lap *N*", where N is the last lap the country completed. Example: the last two finish lap 26 and both DNF on lap 27, so the country is "out after lap 26".

## 3. Rankings

### Countries
- Countries are ranked by **team score**: the total laps of all their runners. This is the official ranking. [decided]
- With equal team scores, the country with more runners still in ranks higher, then alphabetical by code. Every country gets a unique rank. [assumed]

### Seats
- Every team runner has a **seat**, 1–15. All runners in the same seat compete across countries, and each seat's winner is invited to the Individual Big's Backyard Ultra. [decided]
- The seat is the runner's **rank in the official roster**. [assumed]
- **Seat order:**
  - More laps ranks higher.
  - With equal laps, a runner still in ranks ahead of a runner who is out.
  - Otherwise, equal runners share a position. [decided]
- **Seat states:**
  - **open**: 2 or more are still in.
  - **last one in**: exactly 1 is still in.
  - **winner**: the race in this seat is over and one runner is first.
  - **tie**: over, and several runners are joint first.

## 4. Data

- **The live data source is unknown and can't be influenced.** The site works against its own data model, with pluggable adapters per source. It runs on mock data until a real source is known. [decided]
- Assume minimal data: per runner, per lap, a time or a DNF. Some countries may have their own feeds with more detail or better accuracy. Several sources can run side by side, and the source with the higher priority wins per runner and lap. [brief]
- Each source delivers a full snapshot, not changes. Late or corrected data must fix the site on the next refresh. [decided]
- Data is fetched every 60 s. A failed or slow fetch must never take the site down: the last good data stays live, and the site shows a notice that the source has a problem. [brief]
- **Who reports the results:** the official overall feed is fed by each country's race director, who submits their runners' results to the organisation every hour. The organisation then updates the overall data, possibly by aggregating a Google Sheet. Race directors know exactly when a runner DNFs, so in the official feed **a DNF is reported as a DNF, and turn-backs don't occur**. [decided]
- Timing data from other sources, such as live timing or per-country feeds, can be unreliable, as `../upfront-live` showed. Adapters for such sources must normalise these cases: [brief]
  - **Missed registrations.** A later lap shows up without the earlier one, so one recorded time can span hours. Earlier laps are then credited and marked as inferred.
  - **A runner who turns back** shows up as a short "lap". That counts as a DNF.
  - **A finish in the first ~30 min of an hour** belongs to the previous loop.
- Every lap time of every runner is stored durably, in Postgres, for statistics after the race. [brief]
- **Roster:** the official selection behind https://bigsbackyardultra.com/world-team-championship-2026/.
  - 69 countries and 1,330 runners.
  - **"AIN"** (Individual Neutral Athletes) is Russia. The code stays AIN, as in the official data, and it's shown as "Individual Neutral Athletes (Russia)". [decided]
  - Ranks 1–15 are the team (1,033 runners); higher ranks are reserves. Ecuador has only 13 team members.
  - Each runner has a "best" result: the most laps in a backyard race during the qualifying period.
  - Names listed twice in one country keep their best rank. [decided]
- **Mock:** the mock simulates the real roster (team members only). Each runner's lap capacity is drawn around their best result. It can inject missed registrations, unreported DNFs, failed fetches and latency, so the edge cases above get exercised. [decided]

## 5. The site

### On every view
- **Header:**
  - the current lap, runners in the race, and countries in the race;
  - chips for in race, out, ★ country winners, and "X of 69 countries eliminated" ("done" after the finish);
  - the disclaimer, with the time of the last update.
- **Notices:**
  - before the start, the start time in the visitor's local time;
  - a data-source or connection problem, with a note that the last good data is shown.
- **After the finish:** a "Race finished after N laps" banner with a podium of the top 3 countries and their winners.
- A **left menu** to switch views: Countries, Runners and Seats. On a phone it's a tab bar at the top. The view is in the URL (`#runners`), so it survives a reload and can be linked. [decided]
- Usable on a phone. [decided]
- **Viewer preferences are remembered per browser:** the compared countries, starred runners, the leaderboard's country filter and "★ only", and the "show eliminated" toggles. They're stored in browser storage, not cookies, so API requests stay cookie-free for the CDN. [decided]
- **Every control acts on what's next to it:** no choice in one view silently changes another view. [decided]

### Countries view: ranking and comparison charts
- **Top:** a "Compare" row of chips for the compared countries, each with a ✕. Then the charts in two rows: first the two comparison charts, then the two all-country charts.
- **Bottom:** the countries table. [decided]
- **The table:** all countries in ranking order, with rank, country, runners in race / total, team score, and status (in race, winner, or out after lap N).
- **Compare:** a checkbox per country adds it to the comparison charts, up to 8. Ticking only affects the charts. Each compared country keeps its own colour while compared, and the colour square next to the checkbox doubles as its legend key. [decided]
- **Nothing is preselected:** a first visit starts with no countries compared, because preselecting is too opinionated. The comparison charts then say to tick countries in the table. The selection persists across refreshes and reloads, and the auto-refresh never changes it. [decided]
- **Clicking a country row** opens that country's runners in a compact grid below the row: seat, name, laps, and an in/out/winner mark. The grid also has a link to the runner leaderboard filtered to that country. One country is open at a time. [decided]
- **Eliminated countries** are hidden by default, with an opt-in "Show eliminated (n)" toggle. Compared countries stay visible. After the finish, everything is shown. [decided]
- The table always shows every row, without an inner scroll. [decided]
- **Charts** work like Grafana: the backend serves visualisations as standard JSON in a Prometheus-like shape. The frontend draws them generically, so adding a chart is cheap. [brief] The charts are:
  - **comparison:** runners starting each lap, and team score per lap, one line per compared country. Team score has two views:
    - **"Gap to leader"** (the default): laps behind the leading country of all countries at each lap, so the leader is at 0. A cumulative total makes the top countries' lines overlap, and this shows the differences. [decided]
    - **"Total"**: the cumulative team score.
  - **all countries:** runners out per lap, split into "did not finish" and "no result", and a histogram of countries by runners still in. [brief]
- Every chart can switch to a table view.

### Runners view: the individual leaderboard
- A **global leaderboard** of all runners of all countries. Position by laps; with equal laps, a runner still in ranks ahead of one who is out, and otherwise equal runners share a position. [decided]
- **Filter bar:**
  - countries, from a "+ Country" picker, shown as removable chips; none means all;
  - a name search;
  - "★ only", for starred runners;
  - "Show eliminated (n)". [decided]
- **Columns:** position, star, name, country, seat, laps, best (from the roster), status, last lap time. [brief/decided]
- **Sparklines** of the lap times are shown when the list is filtered by country or "★ only". Unfiltered, the ~1,000 sparklines would be too much data per poll. [decided]
- Clicking a row opens a lap-time chart for that runner. [brief]
- **Runners who are out** are hidden by default, behind the "Show eliminated" toggle. Starred runners stay visible. Country winners count as still shown. After the finish, everything is shown. [decided]
- All rows are shown, without an inner scroll. [decided]

### Seats view
- An overview of all 15 seats: the leader (or "N tied"), their country, laps, and seat state. Click a seat, or pick it in the selector, for that seat's full ranking (position, runner, country, laps, best, status). [decided]

## 6. Look

- The race-board style of `../upfront-live`, which the owner prefers. Details are in `DESIGN.md`. [decided]

## 7. Hosting [decided]

- **Backend:** one always-on container on **Caasify**, which is free until 31 Dec 2026, so it covers the race.
- **Database:** **Neon** Postgres.
- **Frontend:** **Vercel**. `/api/*` is rewritten to the backend, so the browser stays same-origin and Vercel's CDN caches the API responses.

## 8. Changes from the original brief

| Brief | Now | Why |
|---|---|---|
| Redis cache to offload the database | No Redis. The API serves from memory, with CDN cache headers. | There's a single backend instance, the data changes at most a few times per hour, and the CDN absorbs the worldwide traffic. Postgres is only for durability and statistics. |
| An external 1-minute ping triggers fetching | An in-process scheduler with timeouts, retries and backoff | More robust, and needs no external cron. |
| "Vercel or maybe others" | Caasify + Neon + Vercel | A free always-on JVM container for the race period. |
| Data from a results page or a Google doc | Pluggable adapters, mock for now | The source is still unknown. |
| Live trackers, position in loop | Dropped | No GPX, and every country has its own loop. |

## 9. Open questions and to do

- [ ] **Real data adapter(s).** The source is still unknown. Candidates: the official results page, a Google doc, or per-country feeds.
- [ ] **Deploy:**
  - set `BACKEND_HOST` in `frontend/vercel.json`;
  - set up Caasify and Neon;
  - make a test run before 17 Oct 12:00Z.
- [ ] **Seat = roster rank** [assumed]: confirm.
- [ ] **Tie-breaks:** how ties for a seat invite are broken (they share a position for now), and how equal team scores rank (for now: runners still in, then country code).
- [ ] **Fewer than 2 runners of a country start lap 1.** The general team rule applies for now.
- [ ] What the roster's `wa` field means. It's unused.
- [ ] Show the "too fast" flag in the site, for example as a marker in the runner's lap-time chart. It's only in the data for now.
- [ ] **Minor:** direct line labels can crowd near the x-axis when several series end at 0.
