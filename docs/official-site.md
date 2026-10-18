# The official live site: review

A review of the official live site, https://wtc2026.bigsbackyardultra.com/#race, made on 18 Oct 2026 during yard 22.

**Sources:**

- the full JS/CSS bundle (prettified), the HTML, and the live `/api/results` JSON fetched at 09:10Z;
- headless Chromium screenshots of the live site at 09:21Z (yard 22): Race at desktop and phone width, `#grid`, `#team/USA`, Classes, Runners and Stopped. See [Seen rendered](#seen-rendered-0921z-yard-22).

The section on the live screenshots describes what was seen. The rest comes from the code and is checked against the screenshots where they overlap.

## Seen rendered (09:21Z, yard 22)

- **Header:**
  - "On yard 22", "Elapsed 21:21:55", "Race time Sun 18 Oct, 09:21 UTC · 4:21 am CDT";
  - the status pill read **"Delayed"**, 21 minutes after the bell, while nations were still entering results;
  - the newsline: "Willie David Christopher wins the Malaysia race on yard 21. 66 nations still running."
- **Field size:** **67** nations and **990** starters, not 69 and 1,033. MUS and SGP are excluded (`config.excluded`), and DNS runners don't count.
- **Hero:** "66 of 67 nations still running after yard 21". Then "21 nations share the lead on 315 yards. 1 nation out; the most recent was Malaysia. *6 nations still entering results, so standings are provisional.*" The provisional part is in italics.
  - The 21 tied leaders collapse into one chip, "21 nations level 315 yards", with a dropdown.
  - A nation still entering results gets a hatched chip with its name in italics (Ireland, 314).
- **Facts:** "727 / 990, 73% of starters"; "19,113 yards, 128,164 km"; "21 nations level, 315 yards each".
- **Recap:**
  - "Yard 22 in progress · 0 of 727 runners finished so far · team yards and places update in the 10 minutes after the bell";
  - "Made the bell 710 / 726, 98% … 6 nations still entering results"; "Fastest lap 32:23"; "Closest to the hour 59:47"; "Field average 48:57, 5 s faster than yard 20";
  - "Out at this bell": 27 runners, all "RTC did not start yard 22";
  - "Moments": Malaysia's winner, its assist "on 20", and "Tajikistan pass their qualifying score of 182 yards".
- **Main chart:** this early, the Gap view is one flat line with ten stacked "Level (15 left)" labels. It tells you nothing until the field splits.
- **Phone (400 px):**
  - the header wraps to two rows, and the stream button becomes "Live stream";
  - the five tabs fit on one row;
  - the time machine takes a full card;
  - nation chips cap at 8 plus "+37 more";
  - the facts stack vertically.

  It reads well, but the Race page gets very long.
- **`#grid`:**
  - nations by yards, with shared ranks (=1, =22, =26, =28);
  - columns #, Nation, Yards, Left, Avg lap, then 15 cells "21 Surname…", mostly cut short;
  - a pending cell is hatched, with italics ("20 T…" for Ireland);
  - stopped cells are grey without an outline ("20 Maluh", "16 Bomo").
- **`#team/USA`:**
  - "Super Six · ON YARD 22 · 04:21 local · night · Bell Buckle, Tennessee";
  - the stats as described below, and "27% of prediction";
  - "2024: 969 yards, #3 of 63; farthest runner 88 yards"; "Team manager: Dawn Stone"; Gary's quote;
  - the heatmap and the runner list, as described below.

Screenshots: `/tmp/claude-1000/-home-endymion-code-byu-wtc-2026/3d044741-c917-4f00-8252-f6361210557e/scratchpad/shots/` (temporary).

## Stack

- A hand-written vanilla JS single-page app, built with Vite, with d3 bundled.
- Title: "WTC 2026 Live · Big's Backyard Ultra World Team Championship".

## Navigation

- **Header, on every view:**
  - "On yard" N, "Elapsed" (hh:mm:ss, then dd:hh:mm:ss), and "Race time" in UTC and CDT;
  - a status pill: "Live", "Delayed" (no fresh data for 3 min), "Pre-race", "Final" or "Simulation".
- **Newsline:** "Latest" plus a generated sentence, e.g. "Ghiles Khati wins the Algeria race on yard 38. 42 nations still running." It's followed by links to the official Leaderboard (Google Sheet), Facebook and Instagram, and a "Live stream" button when one is configured.
- **Tabs:** Race `#race`, Classes `#classes`, Team `#team`, Runners `#runners`, Rosters `#rosters`.
- **Tools:**
  - search, "Find a runner or nation", with keyboard suggestions (up to 4 nations, then runners);
  - a theme button that cycles System / Light / Dark;
  - a "Broadcast" toggle.
- **Deep links:**
  - `#team/USA` and `#team/USA/<runner-id>`;
  - `#classes/AAA`, `#runners/drops`, `#rosters/NLD`;
  - `#grid` (the every-runner grid alone) and `#how` (the "How this works" dialog).
- **Query parameters:**
  - `?broadcast=1&views=race,grid,classes&every=14&speed=45`: a TV mode that auto-scrolls and cycles views, and pauses on touch or key;
  - `?source=replay&yard=38`: simulation.
- **Time machine:** a "Yards completed" slider shows the whole dashboard as it was after any yard, with a "Back to live" button.
- **Static pages:** `/guide` (an illustrated manual) and `/story` ("It takes fifteen").
- **Phone:**
  - the same tab row, with search and the theme button wrapping below it;
  - breakpoints at 1000, 900, 680, 400 and 360 px;
  - under 640 px, the race chart uses nation codes and the last 12 yards;
  - nation chips cap at 8 instead of 18.

## Views

### Race (front page)

- **Hero:**
  - a headline, "N of 69 nations still running after yard N", the leader and their margin, and "N nations out; the most recent was X";
  - chips for the running nations with their yards: a sole leader is gold, more than 3 tied leaders collapse into one "N nations level" chip, and the rest show as "+24 more".
- **Facts row:**
  - "Runners still in": still in / starters, and the %;
  - "Yards run, all nations", with km;
  - "Leading nation".
- **"Follow your nations":** up to 5 nations, kept in localStorage. Each row has:
  - place, yards, and still in / team size;
  - local time and whether it's night there;
  - the last yard ("2 out at yard 38", "9 of 15 finished yard 39");
  - the gap to the nation ahead.
- **"Yard N in review"** (◀ ▶ moves between yards):
  - "Made the bell" x/y, "Fastest lap", "Closest to the hour", and "Field average" with the change from the previous yard;
  - "Out at this bell": who went out, with reason (RTC / INC / OVER);
  - "Moments": winners, lead changes, passing the qualifying score, 100-mile marks, "Upset watch".
  - While a yard is under way, a live line: "Yard 22 in progress · x of y runners finished so far · fastest 41:09".
- **"The race, yard by yard":** a line chart.
  - Switches: "Gap" / "Yards" / "Rank"; "So far" / "Last 12 yards" / "Whole race"; "Top 10" / "Super Six" / "AAA" / "AA" / "A" / "All".
  - Direct labels, e.g. "Australia −3 (12 left)", and event markers along the x-axis.
  - "Replay this chart" animates it.
  - A nation still entering results is drawn only up to its `reportedThrough`, ending in an amber dot labelled "awaiting results".
- **Small charts:**
  - "Nations still running": % of nations, 2026 against 2024;
  - "Drops by yard": bars against a 2024 backdrop scaled to this field; clicking a bar lists who stopped and why;
  - "National winners": a log of "Yard N · winner · nation · class · team yards", or "No winner · the last 3 runners all stopped on yard N".
- **"Standings"**, switched between "Teams | Runners" and "All 69 | Still running 42":
  - **Teams:** rank (=3 for ties), flag, name, runners left, class, 15 pips, and a bar with yards plus "Out · Y27" or "Awaiting Y22".
  - **Runners** (`#grid`): one row per nation (#, Yards, Left, Avg lap) and 15 cells with each runner's yards and surname. Cell states: running (gold outline), stopped (grey), winner (W), assist (A), DNS or no runner (hatched), "?" for not entered yet, and ✓ for finished the current yard. Sorted by "Seed" or "Sort: Yards".
- **"The world, live":** a world map shaded by team yards, crimson when out, with a night shadow. Clicking a country opens its team.

### Classes

- **Class tabs:** Super Six / AAA / AA / A, each with "x of y running", the leader and a progress bar.
- **Selected class:**
  - a headline ("AAA: Poland lead GB by 3 yards") and Gary Cantrell's quote;
  - per team: gap to the class leader, yards, runners left, and a bar with a tick at the team's qualifying score ("Gary's prediction") and the % reached;
  - "Gary's pick" and "Dark horse" tags.
- **Spotlight:** follows the class nearest its decisive stage (expected yard: A 24, AA 48, AAA 72, SUPER 87). Race control can pin one.

### Team (`#team/CODE`)

- **Header:**
  - a "Switch nation" picker and the class;
  - a status line: "Awaiting 1 runner's results (Name, yard 38)", "Race over · won by X on yard N", or "Out · no winner, last yard completed N";
  - local time with day/night, and the site as a Google Maps link.
- **Stats:**
  - Team yards, Overall n/69, Class n/18;
  - Still in n/15, with "15 entered · 3 did not start";
  - "% Of prediction".
- **Extras:** "Follow this nation", "Watch <nation> live", the 2024 result, team managers, Gary's note, and a race-director comment.
- **"Every yard, every runner":** a heatmap of runners × yards:
  - lap time in 5 shades, from under 46 to 57+ min;
  - red for the yard they stopped, a gold outline for the yard on course, "?" for not entered.
- **Runners list:**
  - seed and status ("Finished yard 39 in 54:17", "Did not start yard 30 · RTC", Assist);
  - how they qualified, with badges (Silver Ticket / W / A / F);
  - yards, and the % of their qualifying yards.

### Runners

- **"Leaderboard":**
  - headline facts: "Most yards", "Leading woman", "Furthest beyond qualifying";
  - filters: Everyone / Women / Men, class, Any entry / Silver Ticket / Qualifying winners / Assists, "Still running", "My nations", and search;
  - columns: # (=1 when shared), Runner (seed and badges), Nation (class), Yards, Qualified, Share, Status;
  - paged at 100, with "Show 200 more".
- **"Stopped"** (`#runners/drops`):
  - filters by reason (RTC / INC / OVER), class and gender, plus "My nations";
  - a "Sort" select: Newest first / By yard / By nation;
  - columns: Runner, Nation, Yards, Reason, Stopped (UTC plus local time and night), Qualified, Share;
  - "Download CSV", and a "Did not start" list below.

### Rosters

- Tabs: Full teams / Short teams / Silver Tickets / Qualifying winners.
- Nation cards per class, with the qualifying score and 2024 rank.
- Per nation: the runners with how and where each qualified, and a collapsed list of reserves.

## Domain rules in their code

- **Out:**
  - RTC is out at yard y, i.e. they didn't start y+1;
  - INC and OVER are out at y+1;
  - DNS runners are left out of the team size entirely.
- **Winner:** the only runner who never stopped, with more yards than everyone else. The runner who stopped last is the **assist**.
- **Ranking:**
  - yards, then the **farthest single runner** (the official "race went farther" tie-break), then runners left, then the qualifying score;
  - the place is shared only when yards and farthest runner are both equal.
- **Pending:**
  - a nation is "awaiting results" while `reportedThrough < yard`;
  - "A nation is never shown as out because its results are late";
  - "Made the bell" counts only nations that have reported.
- **Seats:** there's no cross-nation seat competition. "#3" is only the seed: the runner's place in the team by best qualifying yards (`pos` in `roster-2026.json`).

## Ease of use

**Good:**

- headlines in plain language throughout;
- ties shown honestly ("872 runners share the lead");
- late reporting never counted as a drop;
- an excellent explainer dialog, guide and glossary;
- deep links everywhere;
- follow-a-nation, the time machine and broadcast mode;
- a "View as table" twin behind each chart;
- local time per nation.

**Weak or confusing:**

- The Race page is very long: hero, follow card, recap, main chart, 3 small charts, standings, map.
- The grid cuts names short ("38 Ca…"), and its details are in hover/tap tooltips only.
- Chart colour is mostly gold against grey, so nations are told apart only by hover or labels.
- The guide still says "Sixty-six nations".
- Gender filters depend on what race directors enter.

**Accessibility:**

- Done well: a skip link, `aria-current` on tabs, `aria-pressed` on segmented buttons, sr-only text in grid cells, a gold focus ring, `prefers-reduced-motion`, light and dark themes, muted text at about 4.7:1 contrast.
- Gaps: grid cells and heatmap rects aren't focusable, and the search listbox has `aria-selected` but no `aria-activedescendant`.

## Performance

| Asset | Raw | Gzipped |
|---|---|---|
| JS (main + ui) | 163 KB | ~52 KB |
| CSS | 63 KB | ~13 KB |
| `/api/results` (on every change) | 344 KB | ~81 KB |
| `world.json` (lazy-loaded on scroll) | 741 KB | ~244 KB |
| `roster-2026.json` | 231 KB | ~31 KB |
| `replay.json` (simulation only) | 409 KB | |

- **Polling:** every **60 s**, only while the tab is visible, with `cache: no-cache`. ETag / `If-None-Match` returns 304 (verified).
- Built assets are served as `immutable`.

## Data

**Endpoints:**

- `GET /api/results`: `cache-control: no-cache`, an ETag, and an `x-checked-at` header;
- static files: `/data/roster-2026.json`, `/data/2024.json`, `/data/2024-curve.json`, `/data/2024-drops.json`, `/data/world.json`, `/data/replay.json`;
- flags at `/flags/<iso2>.svg`.

**Shape of `/api/results`:** `{generatedAt, raceStart, mode: "live", nations[69], runners[1032], config}`.

- **Nation:**
  - `code` (ISO3), `name`, `iso2`;
  - `division`: SUPER (6 nations), AAA (18), AA (20) or A (25);
  - `qualScore`, `site`, `utcOffset`, `managers[]`;
  - **`reportedThrough`** and `issues[]`;
  - optional `comment` and `raceName`.
- **Runner:**
  - `id`, `nation`, `name`, `gender`, `age`, `dns`;
  - `qual{yards, race, date, status: "At Large" | "Silver Ticket", wa: "W" | "A" | null}`: `W` won their qualifying race, `A` was the assist in it;
  - `laps[]`: seconds per completed yard;
  - `stop`: `null`, `"RTC"`, `"INC"` or `"OVER"`.
- **config:**
  - `announcement{level, text}`, `captions[{yard, text}]`;
  - `mainStream` and `streams{CODE: url}` (20 nations);
  - `spotlight`, `finalClasses`, `hiddenRunners`, `raceStart`;
  - `excluded{MUS, SGP: reason}`.

**At 09:10Z:**

- 42 DNS, 260 stopped, 730 still in.
- The data was under 1 minute old: `generatedAt` 09:09:20Z, `x-checked-at` 09:09:19Z.
- 10 minutes after the 09:00 bell:
  - 10 nations were still at `reportedThrough` 20: BRA, IRL, IND, MAR, GTM, BGR, BRN, ZMB, TJK, CRI;
  - ISL was at 19;
  - the rest were at 21.
- The site says team yards settle "in the 10 minutes after each bell".

## Gaps in our adapter (`wtc.source.official`)

1. **`reportedThrough` is ignored.** It's the authoritative signal that a nation hasn't entered a yard yet. We use the `GRACE_SECONDS` heuristic instead, so a late nation can show as out, or real drops show late.
2. **Stop reasons are lost.** RTC, INC and OVER all become a plain `:dnf`.
3. **DNS becomes a `:dnf` on lap 1.** That inflates lap-1 drops and the "out" counts. Officially DNS runners aren't starters and don't count in team size.
4. **Fields we don't carry:**
   - `division`, `qualScore`, `site`, `utcOffset`, `managers`;
   - gender, age, and `qual` (`status`, `wa`);
   - `config.streams` / `mainStream`, `announcement`, `captions`.
5. **The docstring says the feed polls every 30 s.** The official page polls every 60 s.

## Comparison with our site

**They have, we don't:**

- classes, and standings against the qualifying score;
- the "awaiting results" state;
- stop reasons, the assist, and correct handling of DNS;
- a per-yard recap and moments, a live "yard in progress" line, generated headlines;
- a time machine over the real race (ours is demo only);
- follow-a-nation on the front page;
- the every-runner grid and per-team lap-time heatmap;
- live-stream links, local time per site, 2024 comparison curves, a world map, CSV export, broadcast mode, and a guide and glossary.

**We have, they don't:**

- **Seats:** the cross-nation competition by roster rank for the individual invites;
- comparison charts with a distinct colour per country (up to 8), and gap-to-leader for a selected set;
- per-runner lap-time figures: fastest / average / total, sparklines, and the runner dialog with field median, rest and margin;
- a durable lap store, a source-agnostic model, and small CDN-cached API responses instead of one 344 KB blob;
- favourite countries and starred runners.

## Suggestions, ranked

1. **Use `reportedThrough`.** Show "awaiting results", never "out", while a country's `reportedThrough < lap`. Keep the grace period only as a fallback.
2. **Keep stop reasons and handle DNS.** Show RTC / INC / OVER in the leaderboard, runner dialog, country grid and runners-out chart. Leave DNS runners out of starters and team size, and list them separately.
3. **Adopt the official tie-break.** Equal team scores are split by the farthest single runner, and the place is shared ("=3") only when both are equal. This replaces the [assumed] rule in `REQUIREMENTS.md`. Also add the assist to country results.
4. **Add class/division.** Carry `division` and `qualScore`, and add a class filter or column and a class rank to the Countries table.
5. **Add a recap of the last lap** to the Countries view: made the bell x/y, fastest lap, who went out and why, countries finished.
6. **Small wins:**
   - a "Watch live" link per country, from `config.streams`;
   - local time at each site, from `utcOffset`;
   - `config.announcement` as a notice;
   - fix the "30 s" in `official.clj`;
   - resolve the `wa` open question in `REQUIREMENTS.md`.
