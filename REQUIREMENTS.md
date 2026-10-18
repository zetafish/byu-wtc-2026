# Requirements

What the site must do, for the 2026 Big's Backyard Ultra World Team Championship (WTC). This document is the source of truth for behaviour; `ARCH.md` covers how it's built and `DESIGN.md` covers how it looks.

Where a requirement comes from:
- **[brief]**: the original brief (the old `SPEC.md`).
- **[decided]**: decided by the owner during development.
- **[assumed]**: an assumption that still needs confirming. These are also listed under Open questions.
- **[proposed]**: a design idea from a brainstorm with the owner, not yet confirmed or built (see §5, Redesign).

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
  - there is no result for a lap its country has finished reporting. This is reason "no result". When the source says how far each country has reported (the official feed's `reportedThrough`), a lap counts as reported up to there, and a missing result after it never puts a runner out. Otherwise, a lap counts as reported after its deadline plus a grace period (15 min, configurable). [decided]

  A reported DNF takes precedence over "no result". [decided]
- A lap time over 60 min can't be valid. It's kept as "suspect", and its time isn't shown. [decided]
- There is no lower limit on a lap time: real yards go under 30 min (the fastest 25:56 in the 2026 race), so a fast lap is never flagged. [decided]

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
- With equal team scores, the country whose race went farther (its farthest single runner) ranks higher. That's the official tie-break. With both equal, the countries share the place, shown as the same number without "=" (1, 1, 1, then 4; the tooltip says "shared place"), and are listed by runners still in, then by code. [decided]

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

- **The live data source can't be influenced.** The site works against its own data model, with pluggable adapters per source. [decided]
- **The real sources** [decided]:
  - the official site's results feed (`/api/results`, adapter `wtc.source.official`): full names, lap times, stop reasons, read from the organisers' master sheet. It's the feed the official site's own pages poll, not a promised API.
  - not merged with anything: a second source would overrule the organisers' corrections (a removed yard lives on in the other source).
  - manual fallback, only if the feed is gone for good: the published leaderboard Google Sheet (tab `Lite2`, adapter `wtc.source.sheet`): yards only, no times, no DNFs, and Google serves stale copies.
- Assume minimal data: per runner, per lap, finished or DNF. **A lap time is optional:** Big's Backyard Ultra has never required one, and a contending team may publish only finished/not finished to keep its rivals guessing. Expect the times to be missing most often at the front, late in the race. Everything that ranks or decides (yards, team rule, seats) must work without times; lap-time views show "time not recorded" for a finished lap without one, and never treat it as 0. [decided]
- Some countries may have their own feeds with more detail or better accuracy. Several sources can run side by side, and the source with the higher priority wins per runner and lap. [brief]
- Each source delivers a full snapshot, not changes. Late or corrected data must fix the site on the next refresh. [decided]
- **Stops count at once:** a stop (RTC, INC, OVER) counts as soon as the feed has it, also during the lap: it is final. The official site waits until the yard is complete, so for a while we can show fewer runners in than it does. The reason is shown as the official code, explained in tooltips; DNS is "did not start". [decided]
- Data is fetched every 60 s. A failed or slow fetch must never take the site down: the last good data stays live, and the site shows a notice that the source has a problem. [brief]
- **Who reports the results:** the official overall feed is fed by each country's race director, who submits their runners' results to the organisation every hour. The organisation then updates the overall data, possibly by aggregating a Google Sheet. Race directors know exactly when a runner DNFs, so in the official feed **a DNF is reported as a DNF, and turn-backs don't occur**. [decided]
- Timing data from other sources, such as live timing or per-country feeds, can be unreliable, as `../upfront-live` showed. Adapters for such sources must normalise these cases: [brief]
  - **Missed registrations.** A later lap shows up without the earlier one, so one recorded time can span hours. Earlier laps are then credited and marked as inferred.
  - **A runner who turns back** shows up as a short "lap". That counts as a DNF.
  - **A finish in the first ~30 min of an hour** belongs to the previous loop.
- **No database.** The latest copy of the official feed is kept on disk (a Fly volume in prod) and loaded at boot, so a restart while the feed is down still shows the race. The feed holds every lap of every runner, so this one file is also enough for statistics after the race; no history of copies is kept. [decided] The brief asked for every lap time stored durably in Postgres; the feed made that redundant.
- **Roster:** the official selection behind https://bigsbackyardultra.com/world-team-championship-2026/.
  - 69 countries and 1,330 runners.
  - **"AIN"** (Individual Neutral Athletes) is Russia. The code stays AIN, as in the official data, and it's shown as "Individual Neutral Athletes (Russia)". [decided]
  - Ranks 1–15 are the team (1,033 runners); higher ranks are reserves. Ecuador has only 13 team members.
  - Each runner has a "best" result: the most laps in a backyard race during the qualifying period.
  - Names listed twice in one country keep their best rank. [decided]
- **Mock:** the mock simulates the real roster (team members only). Each runner's lap capacity is drawn around their best result. It can inject missed registrations, unreported DNFs, failed fetches and latency, so the edge cases above get exercised. [decided]
- **Simulation mode:** with a demo race (`CLOCK_START`) or mock data, the site says so in a simulation panel, and the brand badge reads "Sim" instead of "Live". A demo race has no clock. It stands at one lap until someone moves it, using video-player controls that anyone can use: play/pause (playing steps one lap per tick, paused polls nothing), ⏮/⏭ (one lap back or on, then pause), a slider over the whole race (jump there; playing or paused stays as it was), and the tick interval (2 s to 60 s, default 10 s). It opens paused. The real race has no controls. [decided]

## 5. The site

### On every view
- **The site says yard, not lap:** every text a visitor sees uses yard (BYU's word for a loop). Code, API and docs keep "lap". [decided]
- **Header:**
  - the current lap, runners in the race, and countries in the race;
  - chips for in race, out, ★ country winners, and "X of 69 countries eliminated" ("done" after the finish);
  - the disclaimer, with the time of the last update.
- **Notices:**
  - before the start, the start time in the visitor's local time;
  - a data-source or connection problem, with a note that the last good data is shown.
- **After the finish:** a "Race finished after N laps" banner with a podium of the top 3 countries and their winners.
- A **left menu** to switch views: Race (the countries table; renamed from Countries, `#countries` still works) [decided], Charts [decided], Team, Runners and Seats. On a phone it's a tab bar at the top. The view is in the URL (`#runners`), so it survives a reload and can be linked. [decided]
- Usable on a phone. [decided]
- **Fresh around the bell:** our own share of the delay stays near a minute when it matters. The backend reads the feed every 5 s (a new copy waits for our next poll, so this adds straight to its age; unchanged polls cost a 304), the CDN caches for 20 s, and the page reloads every 20 s from 40 minutes into a lap and after the bell until every country still in the race has reported the last lap (results can come in late), every 60 s otherwise, and not in a hidden tab. The official feed itself lags about 3 minutes; we can't change that. [decided]
- **Visitor analytics:** Vercel Web Analytics shows where traffic comes from (referrers), countries and devices, and which views are looked at (each view as its own page, each team page too); cookie-free. [decided]
- **Theme:** light, dark or system (the default), switched in the menu and remembered. [decided]
- **New version notice:** a page left open during a deploy says a new version is out and offers a Reload button; it never reloads by itself. [decided]
- **Viewer preferences are remembered per browser:** the compared countries, favourite countries, starred runners, the leaderboard's country filter, the runner leaderboard's "★ only" toggle, and the runner leaderboard's "Show all" toggle. They're stored in browser storage, not cookies, so API requests stay cookie-free for the CDN. [decided]
- **Every control acts on what's next to it:** no choice in one view silently changes another view. [decided]

### Race view (was: Countries): ranking and comparison charts

**The charts live on their own Charts page** (`#charts`), not above the standings, where they took attention from the ranking. [decided] It has two sections, so each control sits by the charts it drives: "Compare countries" (the group buttons and the per-country charts) and "The whole field" (no selection). [decided] The Race page has the out-at-the-bell line and the standings.
- **Top:** a "Compare" row with all the selection controls, then a chip per compared country, each with a ✕. Then the charts in two rows: first the two comparison charts, then the two all-country charts.
- **Bottom:** the countries table. [decided]
- **Out at this bell** [decided]: one line above the table sums up who went out at the last completed yard ("Out at yard 27: 20 runners · 11 RTC · 7 INC · 2 OVER"); "Show" lists them by country, with the stop reason (RTC / INC / OVER); arrows step through earlier yards. An RTC counts at the yard they finished (they didn't start the next), an INC or OVER at the yard they didn't finish, as on the official site.
- **Coming overtakes** [decided]: one line under "Out at yard N" with the passes coming across the field if nobody drops, from the same chase rule as the team page: the soonest yard's first two ("Coming overtakes: after yard 32 LTU passes FIN, ARG passes LAT · All 39 ▾ · if nobody drops"), and "All" lists every pass within 6 yards of the last bell, by yard, ★ countries' passes first and marked. Each pair is compared at the last yard both have reported, so a country still entering results doesn't look behind; a country that is out (score final) compares at any yard. A country that is out or down to 1 runner passes nobody but can be passed. Pulling ahead of a country it shares a place with (same score and farthest runner) counts as a pass. Only passes still to come are listed, from the yard being run now on: a country whose report lags (its `reportedThrough` behind its results, as GBR's at yard 34) would otherwise give passes after yards already over.
- **Cost of one drop** [decided]: on the team page, under the chase lines, "If one drops now": the countries that would pass this team within 6 yards (or sooner than they will anyway) if it lost one runner this yard and nobody else dropped, by yard ("AUS HUN JPN USA pass after yard 33"); "its race ends" when it's down to 2; "no place lost in the next 6 yards" when nobody would. Not shown once the team is out or down to its last runner.
- **Teams | Runners** [decided]: a switch on the standings. Runners shows per country its rank, yards, runners in, the team's average yard, and its 15 seats as cells of yards and surname, coloured in the race / result not in yet / out (plus winner and DNS); hovering shows the full name and state. Like the official site's runner grid.
- **The table:** all countries in ranking order, with rank, country, runners in race (as pips: a dot per runner, filled while in, then the count) [decided], team score, gap (laps behind the leader's team score, "–" for the leader), and status (in race, winner, or out after lap N). [decided]
- **Compare:** countries are added to the comparison charts, up to 8, only from the Compare row; the table has no checkboxes. Comparing only affects the charts. Each compared country keeps its own colour while compared, and the colour square on its chip is its legend key. [decided]
  - A search box with suggestions adds one country. It matches the name or the code, lists favourites first and leaves out countries already compared. It's disabled at 8. [decided]
  - "Top 8" replaces the comparison with the 8 best-ranked countries, eliminated ones included. [decided]
  - "★ Favourites" replaces it with the favourite countries, the 8 best-ranked if there are more. It's hidden when there are no favourites. [decided]
  - "Clear" empties it. [decided]
  - Countries that stay compared after "Top 8" or "★ Favourites" keep their colour. [decided]
- **Favourite countries:** a ☆ toggle on each row of the Countries table. Favourites feed the search box and the "★ Favourites" button. [decided]
  - **All | Still running** [decided]: a switch in the table head (remembered). Still running hides the countries whose race is over; places stay the official ones, so the numbers skip where an eliminated country ranks.
  - No "★ only" filter on the standings (removed): the table always shows every country. Favourite countries still come first in the pickers and the "Out at yard N" list. [decided]
- **The Charts page starts on All countries and All yards** on a first visit; after that the group and the yard range are remembered. [decided]
- **Clicking a country row** opens its team page (anywhere on the row but the star). The expanded runner grid below the row is gone: the team page shows the runners. [decided]
- **Eliminated countries always show**, in their place by team score, greyed out with a grey flag: their score still counts in the ranking. [decided]
- The table always shows every row, without an inner scroll. [decided]
- **Charts** work like Grafana: the backend serves visualisations as standard JSON in a Prometheus-like shape. The frontend draws them generically, so adding a chart is cheap. [brief] The charts are:
  - **comparison:** runners starting each lap, and team score per lap, one line per compared country. Team score has two views:
    - **"Gap to leader"** (the default): laps behind the leading country of all countries at each lap, so the leader is at 0. A cumulative total makes the top countries' lines overlap, and this shows the differences. [decided]
    - **"Total"**: the cumulative team score.
  - **all countries:** countries still running and runners still running per yard (a DNS never started a yard, so it isn't counted); drops by yard, split by stop code (RTC, INC, OVER, else did not finish / no result), counted at the bell as the official site does; and a histogram of countries by runners still in. [decided]
  - **Groups instead of a country picker** [decided]: one button group, Top 10, Super Six, AAA, AA, A and All (All on a first visit, then remembered). A group shows its best-ranked 10 countries in colour (8 hues, so places 9 and 10 reuse the first two, dashed) and the rest of it as grey context lines. No country chips: each chart has its own legend.
  - **Lines named on hover** [decided], on the race yard by yard (all four views): all of the group's countries in grey, its leader in black; hovering a line names that country, or every country on the same point where lines overlap: "After yard n", flag, country and the value (yards behind, yards or place).
  - **The race yard by yard** has four views: Gap to leader, Yards (team score), Rank (place per yard, 1 at the top, the whole field) and Runners (runners starting each yard, which was its own chart). [decided] "All yards | Last 12", in the Compare countries section, caps the yard axis of its charts; the whole-field charts always show every yard. [decided]
- Every chart can switch to a table view.
- **No chart zoom** (removed): the comparison charts are full width and large instead. [decided]

### Runners view: the individual leaderboard
- A **global leaderboard** of all runners of all countries. Position by laps; with equal laps, a runner still in ranks ahead of one who is out, and otherwise equal runners share a position. [decided]
- **Filter bar:**
  - countries, from a "+ Country" picker, shown as removable chips; none means all;
  - a name search;
  - "★ only", for starred runners;
  - "Show all (+n stopped)". [decided]
- **Columns:** position, name (with its star), country, seat, laps, best (from the roster), status, last lap time, fastest lap time, average lap time (of the timed laps), total time (the sum of the timed laps), lap-time sparkline. The columns and their widths are fixed, so filtering never moves them. [decided]
- **Table | Every yard** [decided]: a switch in the leaderboard's head (remembered, Table by default) shows the same runners, in the same filter and order, as a heatmap: position, name, country, then a cell per yard shaded by its time, as the team page's "Every yard, every runner" grid (stopped, no time, running now). Hover a cell for its time; click a name for the runner dialog.
- **Sorting** [decided]: by position (default), or by last yard, average, fastest or total time, in both directions: click the column header (first quickest first, again slowest first; "Pos" goes back). Runners without a time go last either way; ties keep position order. The choice is remembered.
- **Sparklines** of the lap times are always shown. All runners' lap times load once on the Runners view (~100 KB gzipped late in the race); each reload then fetches only the newest yards (~8 KB). [decided]
- **Clicking a row opens a runner dialog** (modelled on the one Upfront showed): [decided]
  - the name, country, seat, bib and status;
  - figures: laps; distance (laps × 6.7056 km); fastest lap and which lap; average lap and its pace per km; last lap; margin (rest per lap, average of the last 3); total rest (between finishing a lap and the next start); position on the leaderboard;
  - a lap-time chart with the runner's laps as dots, the field's median lap time dashed, the 60:00 limit, and the fastest lap marked and labelled;
  - "All laps", a collapsed table of every lap: time, rest, field median and the difference to it.
  - Position is the leaderboard position, not the position within the lap. [assumed]
- **Runners who have stopped** (out, or a country winner) are hidden by default, behind the "Show all (+n stopped)" toggle: the list is who is still running. Starred runners stay visible. After the finish, everything is shown. [decided]
- All rows are shown, without an inner scroll. [decided]

### Seats view
- An overview of all 15 seats: the leader (or "N tied"), their country, laps, and seat state. Click a seat, or pick it in the selector, for that seat's full ranking (position, runner, country, laps, best, status). [decided]

### Redesign: pages [proposed]

Goal: beat the official site (reviewed in `docs/official-site.md`) on focused information. The official site shows everything on one long page. A fan who opens ours at 3 am wants three answers in seconds: **what changed, who is in danger, and how are my countries doing.** Every page answers its question above the fold, and is designed for a phone (400 px) first.

Three zoom levels plus one side quest, each with its own route, so it can be linked and the back button works:

| Route | Page | Question it answers |
|---|---|---|
| `#` | Home (replaces the Countries view) [decided] | What's happening in the race right now? |
| `#team/NED` | Team | How is my country doing, and who's near us? |
| `#runner/NED-01`, `#compare/NED-01,BEL-03` | Runner, compare | How is this runner doing, against others? |
| `#seats`, `#seats/3` | Seats | Who's winning each seat's invite? |

#### Home: the race at a glance [proposed]
No selection needed. **Home replaces the Countries view.** [decided]
- **Clock:** the current lap, time to the next bell, and how many countries are still reporting the last lap.
- **The last lap in one sentence:** e.g. "Lap 23: 12 out, Cuba eliminated, Malaysia's winner decided."
- **Danger:** countries down to 1 runner, countries about to be overtaken (see the chase panel), and seat races down to 2–3 runners. The official site has none of this.
- **Compact standings:** the top 10, favourites pinned, ties folded ("20 countries level on 345").
- **Your team:** if the viewer has picked a team, one line with a link to its page.
- **The comparison charts move to home.** [decided] Quick selectors pick the compared countries by class: Super Six, AAA, AA, A (next to today's "Top 8" and the country picker). [decided] This needs the feed's `division` (see Data).
- **Left out on purpose:** the map, 2024 comparison curves, broadcast mode and the time machine. They add length, not answers.

#### Team page [decided, first version built]
Built: the switcher (a picker with favourites on top), the header (place, team score and gap, still in, current lap, class, site, local time, reporting status, team-rule pill), the chase panel with its gap chart, the runners table with seat race, the lap grid, and runners-in and place per lap. Not yet: a "vs rival" comparison, drifting-runner hints in the lap grid.
Where most viewers will spend their time: people follow their own country and know its runners.
- **Reached by clicking a country** anywhere (standings, danger list, chips, legends), and from a **menu item** that opens the viewer's team, or the picker if none is chosen yet. [decided] It's its own route, `#team/CODE`. [decided]
- **Switching teams** must be effortless: a picker with favourites on top (the ← → neighbour arrows and the ☆ were dropped as not useful [decided]), and the site remembers the viewer's team (home card, menu).
- **Header:** flag, name, place ("1 / 67", "tied with N"), team score, gap to the country ahead and behind, runners still in out of the starters, reporting status ("reporting lap 23…"), and local time and night at the team's site.
- **Team-rule state in few words:** "9 in", "2 in" (green: two can go on for a long time), "1 left", "none left"; the rule is in the tooltip. [decided]
- **Chase panel:** the race seen from this country's position (below).
- **Runners table:** seat, laps, last lap, average lap, rest margin, status, and **seat rank** ("2nd of 41 in seat 3"), linking to the seat. That's our unique angle.
- **Lap grid:** runners × laps, shaded by lap time, with what the official one lacks: slowest laps, a runner whose times drift up, the margin to the bell.
- **The team over time:** runners still in per lap, place per lap.

#### Chase panel [decided, built without the projection]
"Are we being chased, where is the danger, are we catching the country ahead?"
- **Out or on the last runner:** a country that is out, or down to its last runner (by the team rule that yard is its last), adds no more yards: it can still be passed but can never catch anyone. On an out country's own page nothing is a danger: rivals "Will pass this team" or "Stay behind", and countries ahead are left out: only being overtaken can change. [decided]
- **The arithmetic:** each lap, a country gains exactly as many yards as it has runners still in. So the gap between two countries changes by the difference in their runners still in, and "laps to pass" is gap ÷ that difference. Shown in words:
  - catching up: "Belgium is 12 ahead with 6 in; you have 9. You gain 3 per lap and pass them in 4 laps if nobody drops."
  - chased: "Japan is 5 behind with 11 in. They gain 2 per lap and pass you in 3 laps."
  - out of reach: "Spain is 40 behind with 3 in. They can't catch you while you have more runners."
- **A country whose race is over** has a frozen score: a fixed target ("you pass Malaysia in 2 laps, as long as you keep 2 runners").
- **Neighbours:** the 2–3 countries just ahead and just behind (ties folded), each with gap, runners in, closing speed and laps to pass, or "can't catch you".
- **Gap chart:** this country as the zero line, the neighbours' gaps over the laps; lines heading for zero are closing in. The viewer can add any other country as a rival line.
- **Honest:** "if nobody drops" is always explicit; it updates every lap and never pretends to predict.
- **Early in the race** everyone is level, so the panel shows attrition instead ("15 in; the field averages 12") until the gaps open up.
- **Later, maybe:** a clearly labelled projection of the final team score from the runners' best qualifying results (the official site's "% of prediction"). Speculative, so after the arithmetic version.

#### Runner page and comparison [proposed]
- **The runner dialog gets a URL** (`#runner/NED-01`), so it can be shared, and links to the runner's team and seat.
- **Compare 2–4 runners from any country:** a "compare" tick on any runner (team page, leaderboard, seats); a tray shows "Compare (3)". The view overlays their lap times (one series colour each), rest margin per lap, and the key figures side by side. Capped at 4, beyond that the chart is spaghetti.
- **Shortcuts:** "compare with the seat leader", "with the team's other runners".

#### Seats [proposed]
The side quest; we have it, the official site doesn't.
- The overview of 15 seats (leader, runners left, decided), and a race per seat, linked from team and runner pages.
- Maybe a "closest seat race" item on home.

#### Other ideas [proposed]
- **Timeline:** a running log of events: drops with their reason, eliminations, winners, lead changes. The home sentence comes from it, and afterwards it tells the race's story.
- **After the race:** records (longest team, seat winners, fastest laps) and a final summary.
- **Shareable links with a preview** ("Netherlands =1 after lap 30").
- **Probably not:** the map, broadcast mode, notifications.

#### Data this needs [proposed]
From the official feed, which the adapter doesn't carry yet:
- stop reasons (RTC / INC / OVER) and DNS as its own state: done for the team page and status pills; still to do for a timeline;
- `utcOffset` and `site` (local time at the team's site);
- `division`, for the class selectors on home [decided]; `gender` (optional).

## 6. Look

- The race-board style of `../upfront-live`, which the owner prefers. Details are in `DESIGN.md`. [decided]

## 7. Hosting [decided]

- **Backend:** one always-on machine on **Fly.io** per deployment. [decided] Caasify, the earlier choice, built too slowly.
- **Two deployments**, each with its own backend and site: **prod** (the real race) and **demo** (a demo race on mock data, with the simulation panel). [decided]
- **Database:** none (see Data). Neon Postgres was used until 18 Oct. [decided]
- **Frontend:** **Vercel**. `/api/*` is rewritten to the backend, so the browser stays same-origin and Vercel's CDN caches the API responses.

## 8. Changes from the original brief

| Brief | Now | Why |
|---|---|---|
| Redis cache to offload the database | No Redis, and no database. The API serves from memory, with CDN cache headers. | There's a single backend instance, and the CDN absorbs the worldwide traffic. The official feed holds the whole race, so its latest copy on disk replaces a database. |
| Every lap time stored durably in Postgres | The latest copy of the official feed on a Fly volume | The feed holds every lap of every runner. |
| An external 1-minute ping triggers fetching | An in-process scheduler with timeouts, retries and backoff | More robust, and needs no external cron. |
| "Vercel or maybe others" | Fly.io + Vercel | An always-on JVM container, cheap for the race week. |
| Data from a results page or a Google doc | Pluggable adapters, mock for now | The source is still unknown. |
| Live trackers, position in loop | Dropped | No GPX, and every country has its own loop. |

## 9. Open questions and to do

- [ ] **Redesign (§5, [proposed]):** decided so far: home replaces the Countries view; the comparison charts go to home, with class quick selectors; the team page is its own route, reached by clicking a country and from the menu. Still open:
  - Which page first? Proposed: the team page with the chase panel.
  - Does home keep a full standings table below the compact one, or does "all countries" become a toggle?

- [x] **Real data adapters:** `wtc.source.official` (the one in use) and `wtc.source.sheet` (manual fallback).
- [x] **Late countries:** a country that reports late is never shown as out meanwhile. The official feed's `reportedThrough` says how far each country has reported. Only for a source without it does a runner without a result count as out 45 min after the lap's bell (`GRACE_SECONDS = 2700` in `fly.prod.toml`; the default is 15 min). [decided]
- [ ] **Deploy:**
  - set up the Fly.io app `bigs-live` and `PROD_BACKEND_HOST` in `.env` (see `ARCH.md`, Deployment); the demo site is no longer needed and was removed on 18 Oct [decided];
  - make a test run before 17 Oct 12:00Z.
- [ ] **Seat = roster rank** [assumed]: confirm.
- [ ] **Tie-breaks:** how ties for a seat invite are broken (they share a position for now). Equal team scores follow the official tie-break (see Countries). [decided]
- [ ] **Fewer than 2 runners of a country start lap 1.** The general team rule applies for now.
- [ ] What the roster's `wa` field means. It's unused.
- [ ] **Runner dialog position** [assumed]: the leaderboard position for now. Should it be the position within the current lap (by lap time), as Upfront shows?
- [ ] Show the "too fast" flag in the site, for example as a marker in the runner's lap-time chart. It's only in the data for now.
- [ ] **Minor:** direct line labels can crowd near the x-axis when several series end at 0.
