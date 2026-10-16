(ns wtc.panels
  "The dashboard's visualisations. Each panel is a metric from the backend's
  /api/query plus how to draw it. To add one: add a backend metric to
  `wtc.metrics/registry`, then a map here.

  Keys: :id :title :subtitle :metric :kind (:line | :bar) :x-label :y-label,
  plus one of
    :per-country? true   one series per selected country (by=country query)
    :series-label :k     one series per value of metric label :k, ordered by
                         :series-order, named by :series-names
  and optionally
    :y-format :mmss      seconds shown as m:ss
    :y-zero? false       y-axis doesn't have to start at 0 (line charts)")

(def panels
  [{:id :started
    :title "Runners starting each lap"
    :metric "runners_started"
    :per-country? true
    :kind :line
    :x-label "lap" :y-label "runners"}
   {:id :score
    :title "Team score"
    :subtitle "Total laps by all runners of a country, which decides the ranking"
    :metric "team_score"
    :per-country? true
    :kind :line
    :x-label "lap" :y-label "laps"}
   {:id :attrition
    :title "Runners out per lap"
    :subtitle "All countries"
    :metric "attrition"
    :series-label :reason
    :series-order ["dnf" "missing"]
    :series-names {"dnf" "Did not finish" "missing" "No result"}
    :kind :bar
    :x-label "lap" :y-label "runners"}
   {:id :histogram
    :title "Countries by runners still in"
    :subtitle "Current lap, all countries"
    :metric "countries_by_active_runners"
    :kind :bar
    :x-label "runners in" :y-label "countries"}])
