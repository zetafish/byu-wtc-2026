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
    :variants [{:label … :metric … :subtitle … :y-label …} …]
                         switchable views of one panel; the chosen variant's
                         keys override the panel's, the first is the default
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
    :per-country? true
    :kind :line
    :x-label "lap"
    ;; the total hides small differences between the top countries; the gap shows them
    :variants [{:label "Gap to leader" :metric "team_score_gap" :y-label "laps behind"
                :subtitle "Laps behind the leading country (of all countries)"}
               {:label "Total" :metric "team_score" :y-label "laps"
                :subtitle "Total laps by all runners of a country"}]}
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
