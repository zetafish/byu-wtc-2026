# Design

How the site looks and behaves. `REQUIREMENTS.md` describes what it shows, and `ARCH.md` how it's built. The styles live in `frontend/public/css/style.css`; the markup is in `frontend/src/wtc/app.cljs` and `chart.cljs`.

## Visual language

The look is a race board, after `../upfront-live`, which the owner preferred to the first version of this site.

- **Type:** both fonts come from Google Fonts.
  - **Big Shoulders Display** (600/800): uppercase titles, menu items, big numbers and the podium.
  - **Barlow** (400–600): body text, tables and labels.
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
- **Dark mode:** each light token has its own dark value. They follow `prefers-color-scheme`, unless `data-theme="light"` or `"dark"` is set on `<html>`.

## Layout

- A **left menu** (200 px, sticky, full height) holds the brand and the views Race, Team, Runners and Seats. Each item has a small count, explained by a tooltip.
  - The active item has an orange left bar and a tint.
  - Below **760 px**, the menu becomes a sticky top tab bar with an orange underline, and the brand is hidden.
- **Content** is at most 1240 px wide. The header (title, stats, chips, notices, finished banner) is on every view, and the chosen view goes below it.
- **Cards** sit in a 2-column grid (1 column below 760 px). Tables are `.wide` and span both columns.
- **Tables** show all rows (`.table-wrap.full`) without an inner scroll. They scroll horizontally on narrow screens.

## Components

- **Team page** (`#team/CODE`): a switcher row (← picker → ☆), then cards: header (56 px flag, name and code, team-rule pill, a line of class · site · local time · reporting, then four figures), the chase (bucket lines by what a rival means for you: "⚠ Catching this team" in `--critical`, "✓ This team is catching" in `--good`, then muted "Staying level", "Can't catch this team", "Out of reach", each with [flag] code chips and "in N laps" for overtakes; a table with gap, runners "theirs vs yours", "This team gains / lap" (green ▲ / red ▼, always from this team's side) and the overtake, which always says something (muted for no overtake), with a highlighted "this team" row at 0; more than 4 level countries fold into one table row (the "Staying level" line still lists every one); or, switched with "Numbers | Gap over time | Runners in" (remembered), the gap chart with this country as a neutral dashed zero line and a note explaining it, or the runners who finished each lap (this country a muted dashed line); both charts have "All laps | Last 12" (remembered) and at most 8 rivals (one per series colour: those with an overtake first, soonest first, then the nearest). Above and below follow the place, tie-break included: a level country that ranks higher sits above "this team" and counts as ahead. Once this country is out, the lines are muted and read "Will pass this team", "Level" and "Stays behind", and countries ahead aren't shown at all (no danger: its score is final, only being overtaken can change). The chase is told once: no neighbour lines in the header. It never says "you": the viewer may follow a country that isn't theirs, so it's "this team", and the country's name in the subtitle), runners, the lap grid (cells shaded in 5 steps of `--series-1` by lap time, `--critical` for a DNF lap, striped for no time, an open `--good` box (inset 2 px) for the lap being run now; a DNS runner gets a muted italic "did not start" across the row; the subtitle doesn't explain the colours, the legend does), and two small charts. The team-rule pill is short: green "N in" (2 or more: two runners can go on for a long time), `--critical` "1 left" and "none left" (while the lap isn't settled yet), "★ name · lap N" for a winner and "✕ out after lap N"; the rule itself is in the tooltip.

- **Stat:** an uppercase label over a big display number. The lap stat is in the accent.
- **Chip:** a rounded pill with a border. Used for summary counts, for compared countries (with a ✕ and a colour key), and for leaderboard country filters (with a ✕).
- **Compare row:** "Compare" label, a search box (native `<datalist>` suggestions; Enter takes the best match), ghost buttons "Top 8", "★ Favourites" and "Clear", then the chips. Controls come before the chips so they don't move as chips are added. At narrow widths the row wraps.
- **Country label:** a country is always shown as [flag] name code (`country-label` in `wtc.app`): a 20×15 flag with a hairline border, the name, and the IOC code in muted text. The name truncates with an ellipsis, never the code. Flags are SVGs in `frontend/public/flags` (flag-icons 7.5.0, MIT), mapped from IOC codes in `wtc.flags`; AIN has no flag and gets an empty frame of the same size. Chart legends can't hold images, so they show name and code only. Countries are picked with `country-select`, not a native `<select>` (whose options can't show flags): a button with the current country, and a popup with a search box, favourites first, ↑ ↓ / Enter / Esc. The team switcher, the leaderboard's "+ Country" filter and the compare picker all use it. The runner table's Country column is 190 px for it.
- **Favourite star:** ☆/★ in the first column of the Countries table, styled like the runner stars. The table shows no series colour; compared rows only get the selected background.
- **Countries table rows** open the team page: a click anywhere on the row except the star; the name is a real link for the keyboard and new tabs. There is no expanded row any more.
- **Filter bar** (`.filters`): a picker, chips, a search input and toggles in one wrapping row above the leaderboard table.
- **Pills:**
  - `in` (green dot, "in race");
  - `win` ("★ name · lap N" or "★ country winner");
  - `out` ("✕ out · RTC / INC / OVER / did not start", the meaning in the tooltip; "did not finish / no result" without a stop code; or "out after lap N");
  - for seats: "open · N still in", "last one in", "★ winner" and "tie · N".
- **Toggles:** "★ only" (both tables) and "Show eliminated (n)" (runner leaderboard only) checkboxes in the card head. They're opt-in and hidden after the finish, when everything is shown.
- **Race view** (the countries table, `#race`; it was called Countries): for now only the table; the charts and the Compare row are hidden (`show-charts?`), and rows get no selected tint.
- **Eliminated countries** always show in the Countries table: the row is muted and the flag greyed (`filter: grayscale(1)`, 55% opacity).
- **Zoom dialog** (`dialog.zoom`): a native modal `<dialog>`, up to 1200 px wide, over a dark backdrop, showing one chart card big. A chart's plot area shows a zoom-in cursor; the ⤢ button in the card head does the same for keyboard users.
  - The big chart uses a larger viewBox (`chart/sizes` `:big`, 1100×520) rather than scaling up the small one, so the text stays at its normal size.
  - The comparison charts show their own legend in the big view, since the Compare chips aren't visible there.
- **Segmented control** (`.segmented`): the views of one chart, for example Gap to leader / Total, next to the Table toggle in the card head. The active view has `aria-pressed` and a tint.
- **Star:** ☆/★ in gold `--star` (`#b98900` light, `#ffc61a` dark), not the accent, so it reads as a star. It doesn't open the row.
- **Country key:** a small square in the country's series colour, shown wherever a selected country appears.

## Charts

Charts follow the dataviz method: pick the form first, colour last, and validate the palette.

- **Series colours** are `--series-1..8`, the validated dataviz palette, re-checked against `#ffffff` and `#15232a`. Don't add hues and don't use the accent.
- **Colour follows the country, not its rank.** A compared country takes the lowest free slot, and the survivors keep their colour when one is removed. Because there are 8 hues, at most 8 countries can be compared. Leaderboard country filters have no colour, so they don't use slots.
- **Non-per-country series** (for example attrition by reason) take slots in their fixed `:series-order`.
- **Marks:** 2 px lines and recessive grid lines and axes. One y-axis only; never a dual axis. A place axis is inverted (`:y-invert? true`): 1 at the top, so climbing the standings goes up. It always shows the whole field (`:y-domain [1 n]`, n the number of countries), both ends labelled.
- **Labels:**
  - 2 or more series always get a legend, and up to 4 also get direct labels at the line end. A single series has no legend; the title names it.
  - **The exception is the comparison charts:** the Compare chips above them, with a colour key per country, are their shared legend, so the charts have none of their own (`:legend? false`). That keeps the chart height fixed while countries are added.
  - Text uses the text tokens, never the series colour.
- **Interaction:**
  - Line charts have a crosshair and a tooltip with all series.
  - Bar charts have a tooltip per bar. Stacked bars follow the given order.
  - Every chart has a Chart/Table toggle.
- **Sparklines** in the runner table are muted strokes with no axes. Clicking a row opens the runner dialog (`dialog.zoom` with a `.runner-card`): a 4-column grid of figures (2 columns on phones), then the lap-time chart with a y-axis in mm:ss on whole 5 minutes that doesn't start at zero. The runner is `--series-1` with dots, the field median a muted dashed line, the 60:00 limit a dashed `--critical` line labelled "limit 60:00", and the fastest lap a `--good` point labelled "fastest". A text legend below names all four.

## Stability: no flicker, no jumping

- **Charts keep their card height.** A chart's svg has a fixed aspect (viewBox 640×240). Every non-chart state uses `chart/placeholder`, a box with the same aspect: loading, "No data yet", and "Add countries above…".
- **The Compare row** has a minimum height, so its empty text and its chips take the same space. With many long names it can still wrap to a second line.
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
