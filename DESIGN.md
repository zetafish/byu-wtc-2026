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

- A **left menu** (200 px, sticky, full height) holds the brand and the views Countries, Charts, Runners and Seats. Each item has a small count, explained by a tooltip.
  - The active item has an orange left bar and a tint.
  - Below **760 px**, the menu becomes a sticky top tab bar with an orange underline, and the brand is hidden.
- **Content** is at most 1240 px wide. The header (title, stats, chips, notices, finished banner) is on every view, and the chosen view goes below it.
- **Cards** sit in a 2-column grid (1 column below 760 px). Tables are `.wide` and span both columns.
- **Tables** show all rows (`.table-wrap.full`) without an inner scroll. They scroll horizontally on narrow screens.

## Components

- **Stat:** an uppercase label over a big display number. The lap stat is in the accent.
- **Chip:** a rounded pill with a border, for summary counts and for the "Comparing" countries, which have a ✕ to remove them.
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
- **Colour follows the country, not its rank.** A selected country takes the lowest free slot, and the survivors keep their colour when one is deselected. Because there are 8 hues, at most 8 countries can be selected.
- **Non-per-country series** (for example attrition by reason) take slots in their fixed `:series-order`.
- **Marks:** 2 px lines and recessive grid lines and axes. One y-axis only; never a dual axis.
- **Labels:**
  - 2 or more series always get a legend, and up to 4 also get direct labels at the line end. A single series has no legend; the title names it.
  - Text uses the text tokens, never the series colour.
- **Interaction:**
  - Line charts have a crosshair and a tooltip with all series.
  - Bar charts have a tooltip per bar. Stacked bars follow the given order.
  - Every chart has a Chart/Table toggle.
- **Sparklines** in the runner table are muted strokes with no axes. Clicking a row opens the full lap-time chart, with a y-axis in mm:ss that doesn't start at zero.

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
