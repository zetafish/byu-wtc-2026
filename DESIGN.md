# Design

How the site looks and behaves. `REQUIREMENTS.md` describes what it shows, and `ARCH.md` how it's built. The styles live in `frontend/public/css/style.css`; the markup is in `frontend/src/wtc/app.cljs` and `chart.cljs`.

## Purpose: interpretation, prediction, excitement

We have the same data as the official site, the organisers' feed, to the yard. The [official dashboard](https://knnaresh.com/blog/wtc2026-live-dashboard/) is the record: "Facts are verified, never guessed", a runner is out only once a stop code is in, and "stale but correct beats fresh but wrong". That's the right job for the record, and we don't compete with it.

This site is about what the numbers mean and what happens next:

- **Interpretation:** turn the numbers into what matters at a glance (who is under pressure, which team is in trouble), not another table of lap times.
- **Prediction:** show what is likely before it happens (the margin and the slowdown mark before a drop, a rival's laps to pass), and say how often it's right (see Why Margin).
- **Excitement:** follow the race as a story: the chase, a team down to 2, the next drop, and time travel to replay it.

A view that only repeats what the official site shows needs another reason to exist.

## Visual language

The look is a race board, after `../upfront-live`, which the owner preferred to the first version of this site.

- **Type:** the fonts come from Google Fonts.
  - **Big Shoulders Display** (600/800): uppercase titles, menu items, big numbers and the podium.
  - **Barlow** (400–600): body text, tables and labels.
  - **Noto Sans TC** (400, 600): the fallback after both, only for what they lack: Chinese runner names (TPE). Loaded with `display=swap` in unicode-range slices, so only the characters on the page download, and it never holds up the Latin text. It also draws ★, which Barlow lacks.
- **Surfaces:** a sea-mist page (`--page #eef1f2`), white panels (`--surface-1`) with a 1 px `--line` border, and a 10 px radius.
- **Accent:** orange `--accent #e8590c`. It's used for:
  - the lap number;
  - focus rings;
  - the active menu item;
  - selected and hovered row tints;
  - the "finished" banner;
  - winner pills;
  - links.

  The accent is never used as a chart series colour.
- **Status:**
  - `--good` for in race;
  - `--critical` for the ✕ on out;
  - `--accent` for winner;
  - `--text-muted` for out rows.

  Status is always shown with an icon or text, never by colour alone.
- **Dark mode:** each light token has its own dark value. They follow `prefers-color-scheme`, unless `data-theme="light"` or `"dark"` is set on `<html>`. A tri-state toggle under the menu (☀ light, ☾ dark, ◐ system; at the right of the top bar on phones) sets it and is remembered; an inline script in `index.html` applies the saved theme before first paint, so a dark page never flashes light.

## Layout

- A **left menu** (200 px, sticky, full height) holds the brand and the views Race, Charts, Team, Runners and Seeds. The items are just names: no counts and no team code.
  - The active item has an orange left bar and a tint.
  - Below **760 px**, the menu becomes a sticky top tab bar with an orange underline, and the brand is hidden.
- **Content** is at most 1240 px wide. The header (title, stats, chips, notices, finished banner) is on every view, and the chosen view goes below it.
- **Time travel:** closed, a chip-shaped button "⏪ Time travel" at the end of the header chips (muted text, like a ghost button). Open, a notice under the chips with an info-coloured (`--series-1`) left border, in the demo player's layout: "Time travel. The race as it stood after yard 45, during yard 46. Live: yard 51 done.", ⏮ ⏭, the slider (applied 300 ms after dragging stops), "After yard N", and "Back to live" ("Close" before a yard is picked). The header's figures and every view show that moment.
- **Cards** sit in a 2-column grid (1 column below 760 px). Tables are `.wide` and span both columns.
- **Tables** show all rows (`.table-wrap.full`) without an inner scroll. They scroll horizontally on narrow screens.

## Components

- **Team page** (`#team/CODE`): a "Switch team" picker right next to the name in the header card (wraps under it when narrow), empty so the team isn't named twice (no ← → or ☆; favourites are starred in the Race standings), then cards: header (56 px flag, name and code, team-rule pill, a line of class · site · local time · reporting, then four figures), the chase (bucket lines by what a rival means for you: "⚠ Threats from behind" in `--critical`, "✓ Targets ahead" in `--good`, then muted "Staying level", "Stays behind", "Stays ahead", each with [flag] code chips and "in N yards" for overtakes (direction words, so the two overtake groups never read alike); a table with gap, runners "theirs vs yours", "This team gains / lap" (green ▲ / red ▼, always from this team's side) and the overtake, which always says something ("⚠ threat · in 3 yards", "✓ target · in 3 yards", muted "stays behind / ahead / staying level"), with a highlighted "this team" row at 0; under the bucket lines, after a thin rule, an "If one drops now" line in the same layout: [flag] code chips, each with a muted "in N yards", or muted "no place lost in the next 6 yards" / "its race ends…"; more than 4 level countries fold into one table row (the "Staying level" line still lists every one); or, switched with "Numbers | Gap over time | Runners in" (remembered), the gap chart with this country as a neutral dashed zero line and a note explaining it, or the runners who finished each lap (this country a muted dashed line); both charts have "All laps | Last 12" (remembered) and at most 8 rivals (one per series colour: those with an overtake first, soonest first, then the nearest). Above and below follow the place, tie-break included: a level country that ranks higher sits above "this team" and counts as ahead. Once this country is out, the lines are muted and read "Will pass this team", "Level" and "Stays behind", and countries ahead aren't shown at all (no danger: its score is final, only being overtaken can change). Countries that have stopped and stay behind aren't shown either, for any team (they change nothing); a team that is out with nobody left to pass it shows only its final score, without the switch or table. The chase is told once: no neighbour lines in the header. It never says "you": the viewer may follow a country that isn't theirs, so it's "this team", and the country's name in the subtitle), runners, the lap grid (cells shaded in 6 steps of `--series-1` by lap time (under 40, 40–46, 46–49, 49–53, 53–57, 57+ min; the lightest outlined so it stands apart from an empty cell), `--critical` for a DNF lap, striped for no time, an open `--good` box (inset 2 px) for the lap being run now; a DNS runner gets a muted italic "did not start" across the row; the subtitle doesn't explain the colours, the legend does), and two small charts. The team-rule pill is short: green "N in" (2 or more: two runners can go on for a long time), `--critical` "1 left" and "none left" (while the lap isn't settled yet), "★ name · lap N" for a winner and "✕ out after lap N"; the rule itself is in the tooltip.

- **Stat:** an uppercase label over a big display number. The lap stat is in the accent.
- **Chip:** a rounded pill with a border. Used for summary counts, for compared countries (with a ✕ and a colour key), and for leaderboard country filters (with a ✕).
- **Country label:** a country is always shown as [flag] name code (`country-label` in `wtc.app`): a 20×15 flag with a hairline border, the name, and the IOC code in muted text. The name truncates with an ellipsis, never the code. Flags are SVGs in `frontend/public/flags` (flag-icons 7.5.0, MIT), mapped from IOC codes in `wtc.flags`; AIN has no flag and gets an empty frame of the same size. Chart legends can't hold images, so they show name and code only. Countries are picked with `country-select`, not a native `<select>` (whose options can't show flags): a button with the current country, and a popup with a search box, favourites first, ↑ ↓ / Enter / Esc. The team switcher, the leaderboard's "+ Country" filter and the compare picker all use it. The runner table's Country column is 190 px for it.
- **Country picker** (`country-select`, everywhere a country is picked): a search box, then sections with small uppercase muted headings (`.csel-group`): "★ Favourites", "Super Six", "AAA", "AA", "A", names A–Z within each. A favourite shows once, in Favourites; while searching, the matches stay in their class sections.
- **Favourite star:** ☆/★ in the first column of the Race standings. The table shows no series colour.
- **Countries table rows** open the team page: a click anywhere on the row except the star; the name is a real link for the keyboard and new tabs. There is no expanded row any more.
- **Filter bar** (`.filters`): a picker, chips, a search input and toggles in one wrapping row above the leaderboard table.
- **Pills:**
  - `in` (green dot, "in race");
  - `win` ("★ name · lap N" or "★ country winner");
  - `out` ("✕ out · RTC / INC / OVER / did not start", the meaning in the tooltip; "did not finish / no result" without a stop code; or "out after lap N");
  - for seeds: "open · N still in", "last one in", "★ winner" and "tie · N".
- **Toggles:** "Show all (+n stopped)" (runner leaderboard only; stopped = out or a country winner), a checkbox in the card head. It's opt-in and hidden after the finish, when everything is shown.
- **Race view** (`#race`; it was called Countries): the out-at-the-bell line, then the standings. Rows get no selected tint.
- **Charts view** (`#charts`): two sections with a display heading and a one-line purpose each. **Compare countries** ("Pick a group of countries. Hover a line to see which country it is.") holds one button group "Top 10 | Super Six | AAA | AA | A | All" and, at the right, "All yards | Last 12" (it caps only this section's charts); both are remembered, and a first visit starts on All and All yards, no chips: each chart has its own legend of the coloured countries, right above the one per-country chart, the race yard by yard, full width and large (the big viewBox, 1100×520), since many lines and a grey class must stay readable; runners starting each yard is its fourth view. Panels opt in with `:size`. **The comparison chart** (`chart/pick-chart`, panel `:pick? true`) draws every country of the group as a muted line and the group's leader (its best in the standings) in `--text-primary`; there's no legend. Hovering picks the line nearest the pointer at that yard, plus any on the same value (overlapping lines), highlights it in `--accent` and names it: "After yard 21" over "[flag] Norway NOR · 26th place" ("12 yards behind" or "the leader" on the gap view, "430 yards" on the yards view; "Yard 19" over "[flag] Brunei BRU · 7 runners" on the Runners view), up to 8 rows. Its "Gap to leader" view keeps 0 (the leader) at the top and always reaches at least −10, so a group level with the leader shows flat lines at the top rather than a 0-to-1 axis (`:y-max 0 :y-reach -10`). **The whole field** ("All 67 countries, no selection needed.") holds the rest, with no controls. The panels: the race yard by yard (Gap to leader | Yards | Rank | Runners), countries still running, runners still running (the whole field, 0 to the number of runners), drops by yard (stacked by stop code), countries by runners still in. A single-line chart has no direct label. The group picks the countries: its best-ranked 10 (by the standings when picked) in colour (places 9 and 10 in the colours of 1 and 2, dashed), the rest of the group as thin muted context lines (`:context? true`: `--axis`, 1 px, no legend, label or tooltip row). There is no country picker.
- **"Out at yard N"** (`drops-card`, one quiet line above the Race standings, so it doesn't compete with the ranking): "← Out at yard 27: 20 runners · 11 RTC · 7 INC · 2 OVER → Show ▾". "Show" opens who went out at that bell, a row per country (★ first, then ranking), all of them, with each runner's stop code in `--critical` and its meaning in the tooltip; ← → through the yards, starting at the last completed one. An RTC belongs to the yard they finished, an INC or OVER to the yard they failed.
- **Margin** (`margin-cell`, the same on Runners, Seeds and the team page): m:ss and ▲ / – / ▼; a shrinking margin (over a minute slower than the runner's average) is `--critical`, semibold; – for runners who have stopped.
- **Seed detail** (`seed-detail`, Seeds with a seed chosen): a summary line ("8 of 67 still in · tied on 45 yards. Team in: …"), then grid rows like the Runners heatmap: Pos (muted), name (button), [flag] code, Team in, Margin with ▲ / – / ▼, Yards, the `.lg` yard cells, and the shared legend. "⚠ 2" and a shrinking margin are `--critical`, semibold. Stopped runners are greyed (grey flag) and folded behind "N stopped · Show ▾" while anyone in the seed is still in; once nobody is, they show without the fold.
- **Footer** (`site-footer`, every view, under a hairline rule, muted 13 px): "Made by Endymion Kasanardjo · Source on GitHub", the data source (the official WTC 2026 results, entered hourly by each country's race director; ours may lag a few minutes), and "An unofficial fan site, not affiliated with Big's Backyard Ultra." No email.
- **"Coming overtakes"** (`overtakes-card`, a second quiet line under "Out at yard N", same style): "Coming overtakes: after yard 32 [flag] LTU passes [flag] FIN [flag] ARG passes [flag] LAT · All 39 ▾ · if nobody drops" (muted). Countries as `short-country`: an 18 px flag and the code, the name on hover, linking to the team page. "All" opens the list in the drops box, one row per yard ("After yard 33"), ★ countries' passes first with a ★. Hovering a pass gives the gap and the gain per yard.
- **Standings filter, All | Still running** (segmented, next to Teams | Runners, remembered): Still running leaves out eliminated countries in both views; the places keep their official numbers.
- **Race view, Teams | Runners | Margin** (one segmented control in the standings' card head, with a title on each button; Runners and Margin are the runner grid with yards or margins in the cells, so there's no second control; Runners by default above 760 px, Teams at or below; an explicit pick is remembered, under the prefs key `standings-view`): Runners swaps the table for `race-runners-grid`: ☆ (as in Teams), #, country, yards, gap (muted, as in Teams), runners in (the count only, 0 once the country is done; "N of M" in the tooltip; the winner shows in their own cell), the team's average yard over all timed yards, then one cell per seed (`.slot`) with yards over surname. Cells, in our brand colours (green for still racing, orange only for the winner), with the official site's split of running and done: a solid `--good` border while on course, a `--good` tint once the yard on course is finished, a dashed `--good` border and italic text while the last completed yard's result isn't in, `--grid` grey without a border when out, solid `--accent` with white text for the country winner, hatched for DNS; the tooltip has seed, full name, yards and state (with the stop code). A legend sits below. Rows open the team page.
  - **Margin** (the third button): shows where the pressure is. A runner still in gets their margin (rest per yard over the last 3 timed yards) over the surname, on a fill by level, in one hue by lightness only (the darker, the more pressure; no green/amber/red, which needs hue): six 2-minute steps of `--critical`: no fill with a `--line` border at 10:00 or more, then 12%, 26% and 44% tints for 8–10, 6–8 and 4–6, 70% with white text for 2–4, and solid with white semibold text under 2:00; hatched without times. The steps are tokens (`--m-1..5`, `--m-strong-text`): in dark mode the tints are wider apart (18%, 36%, 56%, 78%), because thin tints on a dark ground blur together, and the two strongest steps take dark text on the bright red. Runners who stopped are `--grid` grey with muted "out", as in Yards (no strike-through), the winner "★". The level is by margin only: late in the race most runners have ▼ (103 of 153 at yard 51), so the margin trend isn't shown here. **Slowing** is: a runner whose last 2 timed yards were 3+ minutes slower than the 6 before (`slowdown`) gets a 2.5 px outline in `--text-primary` on top of any shade, "⇣" before the margin, and the slowdown in the tooltip. It's against their own pace, so it catches a fast runner slowing to a margin that still looks fine (Pál, HUN: 8:20 after yard 41, OVER after 42). A backtest over yards 10–51 on 19 Oct: it came before 47% of next-yard drops, a margin under 4:00 before 18%, either before 55%. The cells are sorted per row: runners still in by margin, smallest first, then those without times, then the ones who stopped (each by seed; the seed is in the tooltip), and the header reads "Runners by margin, smallest first". A team's pressure is how far the dark cells reach from the left. The In column shows "⚠ 2" in `--critical` for a team down to 2. Its own legend explains the levels and marks.
- **Pips** (`.pips`, Race table "In race" column): one 6 px dot per runner, `--good` filled for still in, an `--axis` outline for out, then the count; a team with fewer starters keeps hidden slots so the counts line up; grey on an out row; "N of M still in" as tooltip and label.
- **Eliminated countries** always show in the Countries table: the row is muted and the flag greyed (`filter: grayscale(1)`, 55% opacity).
- **Dialog** (`dialog.zoom`): a native modal `<dialog>`, up to 1200 px wide, over a dark backdrop. Only the runner dialog uses it; charts have no zoom (the comparison charts are full width instead). [decided]
- **Segmented control** (`.segmented`): the views of one chart, for example Gap to leader / Total, next to the Table toggle in the card head. The active view has `aria-pressed` and a tint.
- **Star:** ☆/★ in gold `--star` (`#b98900` light, `#ffc61a` dark), not the accent, so it reads as a star. It doesn't open the row.

## Why Margin

How the Margin view came about (17–19 Oct, during the race). What it looks like is under Components (Race view, Runner tables).

The official site has nothing like it: it shows yards and lap times, and leaves it to the reader to work out who is in trouble. The margin view is the one thing this site adds that the official one can't show at a glance: where the pressure is, per team and per runner, before the drop.

- **From average yard time to margin.** It began as each seed's average yard time, colour-coded. That doesn't show pressure: a 50-minute yard is comfortable for a steady runner and a collapse for a fast one. What shows pressure is the **margin**, the rest before the next bell (60 minutes minus the yard time). 2 minutes leaves no slack; 12 leave plenty.
- **What we settled on:**
  - Averaged over the last 3 timed yards, so one bad yard doesn't dominate.
  - Shaded by lightness of one hue, not by colour, so it reads without hue (the owner is colour-blind).
  - Sorted smallest margin first, so a team's pressure is how far the dark cells reach from the left.
  - Six fixed 2-minute steps. An adaptive scale around the median was considered and dropped: a fixed one means the same in every yard and every country.
  - The level is by margin only. A first version also marked a shrinking margin (▼), but late in the race most runners have one (103 of 153 at yard 51), so everything turned red.
- **Checked with time travel.** Time travel (`?at=N`) was built to test whether the margin predicts drops by replaying the race. It did for Leskinen (FIN) around yard 30, and for most NED drops; Oevermans kept everyone in suspense with a thin margin.
- **The slowdown mark covers the drops margin misses.** Overes (NED) and Pál (HUN) stopped with margins that still looked fine. The pattern: a fast runner whose margin falls from 12+ to about 8 minutes feels it as the end of the world. So a runner whose last 2 yards were 3+ minutes slower than the 6 before is marked as slowing. It's measured against their own pace, not the clock.
- **The backtest** (yards 10–51, 19 Oct; a warning, then a drop in the next yard):

  | Signal | Drops it came before | Hit rate |
  |---|---|---|
  | Margin under 4:00 | 18% | 23% |
  | Slowing | 47% | |
  | Either | 55% | |

  In NED, 9 of 12 drops had a warning in their last 3 yards. Steady runners near the bell, like Pereira (POR), sit in the red for many yards without dropping, which is why the hit rate is low.
- **Then everywhere.** The same shading went to the Runners view's heatmap and the team page, with a hollow cell for an RTC so a stop without a time still shows. It turned out more revealing than yard time, so Margin is the default in the yard grids and one of the Race view's three buttons.

## Charts

Charts follow the dataviz method: pick the form first, colour last, and validate the palette.

- **Series colours** are `--series-1..8`, the validated dataviz palette, re-checked against `#ffffff` and `#15232a`. Don't add hues and don't use the accent.
- **Colour follows the country, not its rank.** A compared country takes the lowest free slot, and the survivors keep their colour when one is removed. There are 8 hues and up to 10 compared countries: slots 9 and 10 reuse hues 1 and 2 as dashed lines (also dashed in the legend), so no hue is added. Leaderboard country filters have no colour, so they don't use slots.
- **Non-per-country series** (for example attrition by reason) take slots in their fixed `:series-order`.
- **Marks:** 2 px lines and recessive grid lines and axes. One y-axis only; never a dual axis. A place axis is inverted (`:y-invert? true`): 1 at the top, so climbing the standings goes up. It always shows the whole field (`:y-domain [1 n]`, n the number of countries), both ends labelled.
- **Labels:**
  - 2 or more series always get a legend, and up to 4 also get direct labels at the line end. A single series has no legend; the title names it.
  - **The comparison charts** have their own legend too, listing only the coloured countries (the grey context lines have none). There are no Compare chips any more.
  - Text uses the text tokens, never the series colour.
- **Interaction:**
  - Line charts have a crosshair and a tooltip with all series.
  - Bar charts have a tooltip per bar. Stacked bars follow the given order.
  - Every chart has a Chart/Table toggle.
- **Runner tables:** the runner leaderboard and the team page's Runners card are the same grid with table roles (`.lb-row`, widths in `grid-template-columns`, so off-screen rows skip layout; the same `runner-row`): Pos, Runner, Country, Seed, Best, Yards, Status, Last yard, Avg, Fastest, Total, Margin, then the yard-time sparkline. The team page has Seed race (96 px, `.lb-team`) where the leaderboard has Country. Both cards have the same "Table | Margin | Yard times" switch in their head (each its own setting). The team page sorts with "Seed | Yards | Avg" above the table (remembered; Seed by default, Yards most first, Avg fastest first with untimed runners last).
- **Leaderboard heatmap** ("Table | Margin | Yard times", one segmented control in the card head: Margin and Yard times are the heatmap, shaded by margin or by time): rows in the table's type (15 px, the name semibold, so switching views doesn't change the text size): position (muted), name (a plain button, underlined on hover), [flag] code, then the team grid's `.lg` cells 22 px high and its legend; 4 px between rows, the same viewport-fixed tooltip. The sort stays as set in the table.
  - **Margin | Yard times** (the heatmap's two buttons in that control, remembered; Margin is the heatmap's default): Margin shades each timed yard by the margin after it (rest per yard over the last 3 timed yards up to it), in the Race view's margin steps (`.lg.mg0..5` on `--m-1..5`; 10:00 or more is an outlined empty cell). No text in the cells; the tooltip adds "margin m:ss". A yard after which the runner was slowing (as on the Race view: the last 2 yards 3+ minutes slower than the 6 before) gets a 5 px `--text-primary` bar along its bottom (`.lg.slowing`); not a full outline, which would read as the hollow RTC cell. The tooltip adds the slowdown. The stop yard is the text colour (`.lg.end`), because solid red is the darkest margin step. Its own legend.
  - **Stops in the yard grids** (Runners heatmap, Seeds, team page): an INC or OVER is the yard they failed, solid (`.lg.dnf`, or `.lg.end` by margin); an RTC is the yard they didn't start, hollow with a 2.5 px `--text-primary` inset (`.lg.rtc`), so the two read apart without colour. A country winner's row just ends. Legend: "stopped in the yard", "didn't start the yard (RTC)".
- **Team page, every yard:** Margin or Yard times in the Runners card's switch (its own setting, remembered; Margin the heatmap's default) shows the lap grid in the card, in the card's sort order, with the same margin shading, slowing bars, stop cells and legend as the Runners view's heatmap; no country column.
- **Sort buttons** (runner leaderboard, both views): a segmented control "Pos | Last yard | Avg | Fastest | Total | Margin | Trend" at the right of the filter row (wraps under it when narrow), the active one pressed with an `--accent` ▲ or ▼; clicking it again flips.
- **Sparklines** in the runner table are muted strokes with no axes. Clicking a row opens the runner dialog (`dialog.zoom` with a `.runner-card`): a 4-column grid of figures (2 columns on phones), then the lap-time chart with a y-axis in mm:ss on whole 5 minutes that doesn't start at zero. The runner is `--series-1` with dots, the field median a muted dashed line, the 60:00 limit a dashed `--critical` line labelled "limit 60:00", and the fastest lap a `--good` point labelled "fastest". A text legend below names all four.

## Stability: no flicker, no jumping

- **Charts keep their card height.** A chart's svg has a fixed aspect (viewBox 640×240). Every non-chart state uses `chart/placeholder`, a box with the same aspect: loading, "No data yet", and "Add countries above…".
- **No partial first paint:** the page shows "Loading live data…" until the first data arrives, then renders everything at once. The chart queries start alongside the main fetch, not after it.
- **Fonts load with `display=block`.** Text appears only once Big Shoulders and Barlow are ready (cached after the first visit), instead of jumping from a fallback font.
- **Auto-refresh changes only what changed.** With unchanged data, a refresh replaces no DOM nodes. Keep it that way: stable React keys, and no remounting of whole views.
- **Removing a country** removes it from the charts at once, without waiting for the next response.

## Copy

- **Yards, never laps:** Big's Backyard Ultra calls a loop a yard, so every text on the site says yard (yards, yard time, "out after yard N"), as the official site does. The code, the API and these docs still say lap (`:laps`, `current-lap`). [decided]
- Titles are short and uppercase through CSS, not in the source.
- **Wording:**
  - "in race", "out", "country winner", "out after lap N" (the last lap the country completed);
  - "did not finish" and "no result" for the two out reasons; the official stop codes RTC, INC and OVER as they are, explained in tooltips, and "did not start" for DNS.
- Before the start, the start time is shown in the visitor's local time.
- Data problems are worded as "Showing the last good data", never as an error page.
- After a deploy, an open page shows a black bar across the top of the page, sticky while scrolling: white "A new version of this site is out." and an orange Reload button with white text (fixed colours in both themes). It never reloads by itself.

## Accessibility

- A visible focus ring (2 px accent) on inputs, buttons, links and focusable SVG marks.
- `aria-current="page"` on the active menu item, and `aria-pressed` on star buttons.
- Every chart has a table alternative, and identity is never colour-only: legend or direct labels, and country names beside the colour keys.
