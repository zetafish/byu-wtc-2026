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
    :y-zero? false       y-axis doesn't have to start at 0 (line charts)
    :y-invert? true      a place axis, 1 at the top
    :y-max 0 :y-reach -10  the top fixed at 0, the axis down to at least -10
    :y-field :place      the y range is the whole field: 1 to the number of
                         countries (:place, inverted), 0 to it (:count), or 0
                         to the number of runners (:runners)
    :per-yard? true      x is the yard, so \"Last 12\" can cap it
    :size :big | :wide   a full-width card with a larger chart (default: half
                         width, normal size)
    :pick? true          all the group's countries as grey lines, the leader
                         black; hovering names the line (:pick-text :gap,
                         :yards or :place says how)")

(def panels
  [{:id :score
    :title "The race yard by yard"
    ;; every country of the group in grey, the leader black; hover names a line
    :pick? true
    ;; full width and tall: many lines, and in grey a whole class, must stay readable
    :size :big
    :per-country? true
    :per-yard? true
    :kind :line
    :x-label "yard"
    ;; the total hides small differences between the top countries; the gap shows them
    :variants [{:label "Gap to leader" :metric "team_score_gap" :y-label "yards behind" :y-max 0 :y-reach -10 :pick-text :gap
                :subtitle "Yards behind the leading country (of all countries)"}
               {:label "Yards" :metric "team_score" :y-label "yards" :pick-text :yards
                :subtitle "Team score: total yards by all runners of a country"}
               {:label "Rank" :metric "team_rank" :y-label "place" :y-invert? true :y-field :place :pick-text :place
                :subtitle "Place after each yard (1 is first; ties share a place)"}]}
   {:id :started
    :title "Runners starting each yard"
    :metric "runners_started"
    :size :wide
    :pick? true
    :pick-text :runners
    :per-country? true
    :per-yard? true
    :kind :line
    :x-label "yard" :y-label "runners"}
   {:id :running
    :title "Countries still running"
    :subtitle "After each yard, all countries"
    :metric "countries_running"
    :per-yard? true
    :y-field :count
    :kind :line
    :x-label "yard" :y-label "countries"}
   {:id :runners-running
    :title "Runners still running"
    :subtitle "Runners starting each yard, all countries"
    :metric "runners_started"
    :per-yard? true
    :y-field :runners
    :kind :line
    :x-label "yard" :y-label "runners"}
   {:id :attrition
    :title "Drops by yard"
    :subtitle "Runners out at each bell, all countries"
    :metric "attrition"
    :per-yard? true
    :series-label :reason
    :series-order ["rtc" "inc" "over" "dnf" "missing"]
    :series-names {"rtc" "RTC: didn't start the next yard" "inc" "INC: didn't finish the yard"
                   "over" "OVER: over the hour" "dnf" "Did not finish" "missing" "No result"}
    :kind :bar
    :x-label "yard" :y-label "runners"}
   {:id :histogram
    :title "Countries by runners still in"
    :subtitle "Current yard, all countries"
    :metric "countries_by_active_runners"
    :kind :bar
    :x-label "runners in" :y-label "countries"}])
