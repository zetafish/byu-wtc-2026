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

- A **left menu** (200 px, sticky, full height) holds the brand and the views Countries, Runners and Seats. Each item has a small count, explained by a tooltip.
  - The active item has an orange left bar and a tint.
  - Below **760 px**, the menu becomes a sticky top tab bar with an orange underline, and the brand is hidden.
- **Content** is at most 1240 px wide. The header (title, stats, chips, notices, finished banner) is on every view, and the chosen view goes below it.
- **Cards** sit in a 2-column grid (1 column below 760 px). Tables are `.wide` and span both columns.
- **Tables** show all rows (`.table-wrap.full`) without an inner scroll. They scroll horizontally on narrow screens.

## Components

- **Stat:** an uppercase label over a big display number. The lap stat is in the accent.
- **Chip:** a rounded pill with a border. Used for summary counts, for compared countries (with a ✕ and a colour key), and for leaderboard country filters (with a ✕).
- **Compare checkbox:** a checkbox plus a colour square in the country's series colour, which is empty when unticked. The square is the legend key for that country in the charts above.
- **Country squad grid:** in the expanded row below a country, a compact multi-column grid (`ul.squad`): seat number, name, laps, and a small mark (green dot for in, ★ for winner, red ✕ for out, with the reason in the tooltip).
- **Filter bar** (`.filters`): a picker, chips, a search input and toggles in one wrapping row above the leaderboard table.
- **Pills:**
  - `in` (green dot, "in race");
  - `win` ("★ name · lap N" or "★ country winner");
  - `out` ("✕ out · did not finish / no result", or "out after lap N");
  - for seats: "open · N still in", "last one in", "★ winner" and "tie · N".
- **Toggles:** "Show eliminated (n)" checkboxes in the card head. They're opt-in and hidden after the finish, when everything is shown.
- **Star:** ☆/★ in the accent. It doesn't open the row.
- **Country key:** a small square in the country's series colour, shown wherever a selected country appears.

## Charts

Charts follow the dataviz method: pick the form first, colour last, and validate the palette.

- **Series colours** are `--series-1..8`, the validated dataviz palette, re-checked against `#ffffff` and `#15232a`. Don't add hues and don't use the accent.
- **Colour follows the country, not its rank.** A compared country takes the lowest free slot, and the survivors keep their colour when one is unticked. Because there are 8 hues, at most 8 countries can be compared. Leaderboard country filters have no colour, so they don't use slots.
- **Non-per-country series** (for example attrition by reason) take slots in their fixed `:series-order`.
- **Marks:** 2 px lines and recessive grid lines and axes. One y-axis only; never a dual axis.
- **Labels:**
  - 2 or more series always get a legend, and up to 4 also get direct labels at the line end. A single series has no legend; the title names it.
  - **The exception is the comparison charts:** the Compare chips above them, with a colour key per country, are their shared legend, so the charts have none of their own (`:legend? false`). That keeps the chart height fixed while countries are ticked.
  - Text uses the text tokens, never the series colour.
- **Interaction:**
  - Line charts have a crosshair and a tooltip with all series.
  - Bar charts have a tooltip per bar. Stacked bars follow the given order.
  - Every chart has a Chart/Table toggle.
- **Sparklines** in the runner table are muted strokes with no axes. Clicking a row opens the full lap-time chart, with a y-axis in mm:ss that doesn't start at zero.

## Stability: no flicker, no jumping

- **Charts keep their card height.** A chart's svg has a fixed aspect (viewBox 640×240). Every non-chart state uses `chart/placeholder`, a box with the same aspect: loading, "No data yet", and "Tick countries…".
- **The Compare row** has a minimum height, so its empty text and its chips take the same space. With many long names it can still wrap to a second line.
- **No partial first paint:** the page shows "Loading live data…" until the first data arrives, then renders everything at once. The chart queries start alongside the main fetch, not after it.
- **Fonts load with `display=block`.** Text appears only once Big Shoulders and Barlow are ready (cached after the first visit), instead of jumping from a fallback font.
- **Auto-refresh changes only what changed.** With unchanged data, a refresh replaces no DOM nodes. Keep it that way: stable React keys, and no remounting of whole views.
- **Unticking a country** removes it from the charts at once, without waiting for the next response.

## Copy

- Titles are short and uppercase through CSS, not in the source.
- **Wording:**
  - "in race", "out", "country winner", "out after lap N" (the last lap the country completed);
  - "did not finish" and "no result" for the two out reasons.
- Before the start, the start time is shown in the visitor's local time.
- Data problems are worded as "Showing the last good data", never as an error page.

## Accessibility

- A visible focus ring (2 px accent) on inputs, buttons, links and focusable SVG marks.
- `aria-current="page"` on the active menu item, and `aria-pressed` on star buttons.
- Every chart has a table alternative, and identity is never colour-only: legend or direct labels, and country names beside the colour keys.
