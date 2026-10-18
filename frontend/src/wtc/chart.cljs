(ns wtc.chart
  "Generic SVG charts. Input is a list of series:
    [{:key \"NED\" :label \"Netherlands\" :color \"var(--series-1)\"
      :values [[x y] ...]}]
  Every chart comes with a legend (2+ series; `:legend? false` when the page
  shows a shared one), a hover layer and a table view."
  (:require [clojure.string :as str]
            [reagent.core :as r]))

(def W 640)
(def H 240)

;; viewBox sizes; text scales with the svg, so a big chart gets a bigger
;; viewBox rather than a stretched small one
(def sizes {:normal [W H] :big [1100 520] :wide [1100 400]})

(defn- nice-step [span n]
  (let [raw (/ span n)
        mag (js/Math.pow 10 (js/Math.floor (js/Math.log10 raw)))
        f (/ raw mag)]
    (* mag (cond (<= f 1) 1 (<= f 2) 2 (<= f 5) 5 :else 10))))

(defn- ticks
  "Nice ticks from below lo. With cover? the last tick is >= hi (y axes),
  otherwise ticks stop at hi (x axes). `step` fixes the tick distance."
  [lo hi n integer? cover? & [step]]
  (let [s (or step (cond-> (nice-step (max 1e-9 (- hi lo)) n) integer? (max 1)))
        start (* s (js/Math.floor (/ lo s)))
        limit (if cover? (+ hi (* s 0.999)) (+ hi 1e-9))]
    (vec (take-while #(<= % limit) (iterate #(+ % s) start)))))

(defn- scale [[d0 d1] [r0 r1]]
  (fn [v] (+ r0 (* (- r1 r0) (if (= d0 d1) 0.5 (/ (- v d0) (- d1 d0)))))))

(defn- y-axis [series {:keys [y-zero? refs y-step y-invert? y-domain y-reach] top :y-max :or {y-zero? true}} stacked?]
  (if-let [[lo hi] y-domain]
    ;; a fixed range, e.g. every place from 1 to the number of countries
    (let [ts (ticks lo hi 4 true false y-step)
          step (if (next ts) (- (second ts) (first ts)) 1)
          ;; both ends labelled; drop a tick that would crowd one
          inner (filter #(and (> (- % lo) (* 0.4 step)) (> (- hi %) (* 0.4 step))) ts)]
      {:ticks (vec (concat [lo] inner [hi])) :domain [lo hi]})
  (let [ys (concat (if stacked?
                     (vals (apply merge-with + (map #(into {} (:values %)) series)))
                     (mapcat #(map second (:values %)) series))
                   (map :y refs))
        y-max (if (seq ys) (reduce max ys) 0)
        y-min (if (seq ys) (reduce min ys) 0)
        ;; y-zero? keeps 0 inside the axis, from below (counts) or above (gaps)
        ;; :y-reach keeps at least that low a value on the axis (e.g. -10)
        lo (cond-> (if y-zero? (min 0 y-min) y-min) y-reach (min y-reach))
        ;; :y-max fixes the top (e.g. 0 for a gap to the leader)
        hi (if top top (let [h (if y-zero? (max 0 y-max) y-max)] (if (<= h lo) (+ lo 1) h)))
        ts (ticks lo hi 4 false true y-step)
        ;; a place axis (inverted) starts at 1, not at the tick below it
        ts (if (and y-invert? (>= lo 1) (< (first ts) 1)) (assoc ts 0 1) ts)]
    {:ticks ts :domain [(first ts) (max (last ts) hi)]})))

(defn- xs-of [series] (vec (sort (distinct (mapcat #(map first (:values %)) series)))))

(defn- fmt [f v] (if f (f v) (str v)))

(defn placeholder
  "A chart-sized box with a message, so swapping it for a chart doesn't
  change the card's height."
  [text]
  [:div.plot-box [:span text]])

(defn- legend [series kind]
  (when (> (count series) 1)
    [:ul.legend
     (for [{:keys [key label color dash?]} series]
       ^{:key key}
       [:li [:span {:class (if (= kind :line) "key-line" "key-box")
                    :style {:background (if dash?
                                          (str "repeating-linear-gradient(90deg, " color " 0 5px, transparent 5px 8px)")
                                          color)}}]
        label])]))

(defn- tooltip [{:keys [left title rows]}]
  [:div.tooltip {:style (if (> left 60)
                          {:right (str (- 100 left) "%") :margin-right 12}
                          {:left (str left "%") :margin-left 12})}
   [:div.tt-title title]
   (for [{:keys [key label color value]} rows]
     ^{:key key}
     [:div.tt-row [:span.key-line {:style {:background color}}]
      [:strong value] [:span.tt-label label]])])

(defn- frame [{:keys [m sy y-ticks fmt-y x-ticks sx fmt-x x-label y-label]}]
  [:g
   (for [t y-ticks]
     ^{:key (str "y" t)}
     [:g [:line.grid {:x1 (:l m) :x2 (- (:w m) (:r m)) :y1 (sy t) :y2 (sy t)}]
      [:text.tick {:x (- (:l m) 6) :y (sy t) :dy "0.32em" :text-anchor "end"} (fmt fmt-y t)]])
   [:line.axis {:x1 (:l m) :x2 (- (:w m) (:r m)) :y1 (- (:h m) (:b m)) :y2 (- (:h m) (:b m))}]
   (for [t x-ticks]
     ^{:key (str "x" t)}
     [:text.tick {:x (sx t) :y (- (:h m) (:b m) -16) :text-anchor "middle"} (fmt fmt-x t)])
   (when x-label [:text.axis-label {:x (- (:w m) (:r m)) :y (- (:h m) 2) :text-anchor "end"} x-label])
   (when y-label [:text.axis-label {:x 0 :y 8} y-label])])

(defn- direct-labels
  "End-of-line labels for up to 4 series, nudged apart so they don't collide."
  [series sx sy]
  (let [ends (->> series
                  (keep (fn [s] (when-let [[x y] (last (:values s))]
                                  {:s s :x (sx x) :y (sy y)})))
                  (sort-by :y))
        placed (reduce (fn [acc e]
                         (let [prev (peek acc)
                               y (if (and prev (< (- (:y e) (:y prev)) 12)) (+ (:y prev) 12) (:y e))]
                           (conj acc (assoc e :y y))))
                       [] ends)]
    [:g (for [{:keys [s x y]} placed]
          ^{:key (:key s)}
          [:text.direct {:x (+ x 6) :y y :dy "0.32em"} (:key s)])]))

(defn- nearest [xs v] (apply min-key #(js/Math.abs (- % v)) xs))

(defn- pointer->x [e m]
  (let [rect (.getBoundingClientRect (.-currentTarget e))
        px (* (- (.-clientX e) (.-left rect)) (/ (- (:w m) (:l m) (:r m)) (.-width rect)))]
    (+ (:l m) px)))

(defn line-chart
  "Options beyond the series: `:refs` [{:y :label :neutral?}] dashed horizontal
  lines (a limit, in the critical colour; :neutral? for a baseline),
  `:marks` [{:x :y :label :color}] labelled points, `:direct-labels? false`,
  `:y-step` a fixed y tick distance, `:y-invert? true` for a place axis
  (low values at the top), `:y-domain [lo hi]` a fixed y range, `:y-max` a
  fixed top with `:y-reach` the least the axis goes down to.
  A series can have `:dash? true` and `:dots? true`."
  [_]
  (let [hover (r/atom nil)]
    (fn [{:keys [series fmt-y fmt-x x-label y-label refs marks] :as opts}]
      (let [labels? (and (:direct-labels? opts true) (<= 1 (count series) 4))
            [w h] (sizes (:size opts :normal))
            m {:l 44 :r (if labels? 44 12) :t 24 :b 30 :w w :h h}
            xs (xs-of series)
            {y-ticks :ticks y-dom :domain} (y-axis series opts false)
            sx (scale [(first xs) (last xs)] [(:l m) (- (:w m) (:r m))])
            sy (scale y-dom (cond-> [(- (:h m) (:b m)) (:t m)] (:y-invert? opts) reverse))
            invert (fn [px] (+ (first xs) (* (- (last xs) (first xs))
                                             (/ (- px (:l m)) (- (:w m) (:l m) (:r m))))))
            hx @hover]
        (if (empty? xs)
          [placeholder "No data yet"]
          [:div.chart
           (when (and (:legend? opts true) (seq series)) [legend series :line])
           [:div.plot
            [:svg {:view-box (str "0 0 " (:w m) " " (:h m)) :role "img" :aria-label y-label}
             [frame {:m m :sy sy :y-ticks y-ticks :fmt-y fmt-y :sx sx :fmt-x fmt-x
                     :x-ticks (ticks (first xs) (last xs) 8 true false) :x-label x-label :y-label y-label}]
             (for [{:keys [y label neutral?]} refs]
               ^{:key (str "ref" y)}
               [:g {:class (when neutral? "neutral")}
                [:line.ref {:x1 (:l m) :x2 (- (:w m) (:r m)) :y1 (sy y) :y2 (sy y)}]
                (when label [:text.ref-label {:x (- (:w m) (:r m)) :y (- (sy y) 6) :text-anchor "end"} label])])
             (for [{:keys [key color values dash? dots?]} series]
               ^{:key key}
               [:g
                [:path.line {:class (when dash? "dash")
                             :d (str "M" (str/join "L" (map (fn [[x y]] (str (sx x) "," (sy y))) values)))
                             :stroke color}]
                (when dots?
                  (for [[x y] values]
                    ^{:key x} [:circle.pt {:cx (sx x) :cy (sy y) :r 2.5 :fill color}]))])
             (for [{:keys [x y label color]} marks]
               ^{:key (str "mark" x)}
               [:g [:circle.dot {:cx (sx x) :cy (sy y) :r 5 :fill color}]
                (when label [:text.mark-label {:x (sx x) :y (+ (sy y) 18) :text-anchor "middle"} label])])
             (when labels? [direct-labels series sx sy])
             (when hx
               [:g [:line.crosshair {:x1 (sx hx) :x2 (sx hx) :y1 (:t m) :y2 (- (:h m) (:b m))}]
                (for [{:keys [key color values]} series
                      :let [y (some (fn [[x y]] (when (= x hx) y)) values)]
                      :when y]
                  ^{:key key} [:circle.dot {:cx (sx hx) :cy (sy y) :r 4 :fill color}])])
             [:rect {:x (:l m) :y 0 :width (- (:w m) (:l m) (:r m)) :height (:h m) :fill "transparent"
                     :tab-index 0
                     :on-pointer-move #(reset! hover (nearest xs (invert (pointer->x % m))))
                     :on-pointer-leave #(reset! hover nil)
                     :on-blur #(reset! hover nil)
                     :on-key-down (fn [e]
                                    (let [i (.indexOf xs (or @hover (last xs)))]
                                      (case (.-key e)
                                        "ArrowLeft" (reset! hover (nth xs (max 0 (dec i))))
                                        "ArrowRight" (reset! hover (nth xs (min (dec (count xs)) (inc i))))
                                        nil)))}]]
            (when hx
              [tooltip {:left (* 100 (/ (sx hx) (:w m)))
                        :title (str (or x-label "x") " " (fmt fmt-x hx))
                        :rows (->> series
                                   (keep (fn [{:keys [values] :as s}]
                                           (when-let [y (some (fn [[x y]] (when (= x hx) y)) values)]
                                             (assoc s :y y :value (fmt fmt-y y)))))
                                   (sort-by (comp - :y)))}])]])))))

(defn pick-chart
  "Many lines, muted, with the ones that matter (`:lead? true`) in the text
  colour. Hovering picks the line nearest the pointer at that x, plus every
  line on the same value there (they overlap), and names them: `:title-fn`
  (x -> title), `:label-fn` (series -> hiccup, e.g. a flag and name) and
  `:text-fn` (y -> text). Takes the line-chart axis options."
  [_]
  (let [hover (r/atom nil)]  ; {:x data-x :py svg-y}
    (fn [{:keys [series fmt-y fmt-x x-label y-label title-fn label-fn text-fn] :as opts}]
      (let [[w h] (sizes (:size opts :normal))
            m {:l 44 :r 12 :t 24 :b 30 :w w :h h}
            xs (xs-of series)
            {y-ticks :ticks y-dom :domain} (y-axis series opts false)
            sx (scale [(first xs) (last xs)] [(:l m) (- (:w m) (:r m))])
            sy (scale y-dom (cond-> [(- (:h m) (:b m)) (:t m)] (:y-invert? opts) reverse))
            invert (fn [px] (+ (first xs) (* (- (last xs) (first xs))
                                             (/ (- px (:l m)) (- (:w m) (:l m) (:r m))))))
            {hx :x py :py} @hover
            at (fn [s] (some (fn [[x y]] (when (= x hx) y)) (:values s)))
            near (when hx
                   (some->> series (keep #(when-let [y (at %)] [% y (js/Math.abs (- (sy y) py))]))
                            seq (apply min-key #(nth % 2))))
            ;; only when the pointer is close to a line; overlapping lines come along
            picked (when (and near (< (nth near 2) 14))
                     (filter #(= (second near) (at %)) series))
            picked-keys (set (map :key picked))]
        (if (empty? xs)
          [placeholder "No data yet"]
          [:div.chart
           [:div.plot
            [:svg {:view-box (str "0 0 " (:w m) " " (:h m)) :role "img" :aria-label y-label}
             [frame {:m m :sy sy :y-ticks y-ticks :fmt-y fmt-y :sx sx :fmt-x fmt-x
                     :x-ticks (ticks (first xs) (last xs) 8 true false) :x-label x-label :y-label y-label}]
             ;; muted first, then the lead, then the picked on top
             (for [{:keys [key values lead?]} (sort-by (fn [s] [(contains? picked-keys (:key s)) (boolean (:lead? s))]) series)]
               ^{:key key}
               [:path.line {:class (cond (contains? picked-keys key) "picked" lead? "lead" :else "context")
                            :d (str "M" (str/join "L" (map (fn [[x y]] (str (sx x) "," (sy y))) values)))}])
             (when (seq picked)
               [:g [:line.crosshair {:x1 (sx hx) :x2 (sx hx) :y1 (:t m) :y2 (- (:h m) (:b m))}]
                [:circle.dot.picked {:cx (sx hx) :cy (sy (second near)) :r 4}]])
             [:rect {:x (:l m) :y 0 :width (- (:w m) (:l m) (:r m)) :height (:h m) :fill "transparent"
                     :on-pointer-move (fn [e]
                                        (let [rect (.getBoundingClientRect (.-currentTarget e))]
                                          (reset! hover {:x (nearest xs (invert (pointer->x e m)))
                                                         :py (* (- (.-clientY e) (.-top rect)) (/ (:h m) (.-height rect)))})))
                     :on-pointer-leave #(reset! hover nil)}]]
            (when (seq picked)
              (let [left (* 100 (/ (sx hx) (:w m)))]
                [:div.tooltip {:style (if (> left 60)
                                        {:right (str (- 100 left) "%") :margin-right 12}
                                        {:left (str left "%") :margin-left 12})}
                 [:div.tt-title (title-fn hx)]
                 (for [s (take 8 picked)]
                   ^{:key (:key s)}
                   [:div.tt-row [label-fn s] [:span.tt-label (text-fn (at s))]])
                 (when (> (count picked) 8) [:div.tt-row [:span.tt-label (str "and " (- (count picked) 8) " more")]])]))]])))))

(defn- bar-path
  "Bar anchored to the baseline with a rounded data end (top)."
  [x y w h]
  (let [rad (min 4 (/ w 2) h)]
    (str "M" x "," (+ y h) "V" (+ y rad)
         "Q" x "," y " " (+ x rad) "," y
         "H" (- (+ x w) rad)
         "Q" (+ x w) "," y " " (+ x w) "," (+ y rad)
         "V" (+ y h) "Z")))

(defn bar-chart
  "Single or stacked bars; series stack in the order given."
  [_]
  (let [hover (r/atom nil)]
    (fn [{:keys [series fmt-y fmt-x x-label y-label] :as opts}]
      (let [[w h] (sizes (:size opts :normal))
            m {:l 44 :r 12 :t 24 :b 30 :w w :h h}
            xs (xs-of series)
            {y-ticks :ticks y-dom :domain} (y-axis series opts true)
            band (/ (- (:w m) (:l m) (:r m)) (max 1 (count xs)))
            bw (max 1 (- band 2))
            sx (fn [x] (+ (:l m) (* band (+ 0.5 (.indexOf xs x)))))
            sy (scale y-dom [(- (:h m) (:b m)) (:t m)])
            by-x (fn [x] (keep (fn [s] (when-let [y (some (fn [[vx vy]] (when (= vx x) vy)) (:values s))]
                                         (assoc s :y y)))
                               series))
            step (max 1 (js/Math.ceil (/ (count xs) 10)))
            hx @hover]
        (if (empty? xs)
          [placeholder "No data yet"]
          [:div.chart
           (when (:legend? opts true) [legend series :bar])
           [:div.plot
            [:svg {:view-box (str "0 0 " (:w m) " " (:h m)) :role "img" :aria-label y-label}
             [frame {:m m :sy sy :y-ticks y-ticks :fmt-y fmt-y :sx sx :fmt-x fmt-x
                     :x-ticks (take-nth step xs) :x-label x-label :y-label y-label}]
             (for [x xs
                   :let [segs (filter #(pos? (:y %)) (by-x x))
                         x0 (- (sx x) (/ bw 2))
                         tops (reductions + (map :y segs))]]
               ^{:key x}
               [:g {:opacity (if (and hx (not= hx x)) 0.55 1)}
                (for [[i {:keys [key color y]}] (map-indexed vector segs)
                      :let [top (sy (nth tops i))
                            bottom (sy (- (nth tops i) y))
                            h (max 0 (- bottom top (if (pos? i) 2 0)))]]
                  ^{:key key}
                  (if (= i (dec (count segs)))
                    [:path {:d (bar-path x0 top bw h) :fill color}]
                    [:rect {:x x0 :y top :width bw :height h :fill color}]))
                [:rect {:x (- (sx x) (/ band 2)) :y (:t m) :width band :height (- (:h m) (:t m) (:b m))
                        :fill "transparent" :tab-index 0
                        :on-pointer-enter #(reset! hover x) :on-focus #(reset! hover x)
                        :on-pointer-leave #(reset! hover nil) :on-blur #(reset! hover nil)}]])]
            (when hx
              [tooltip {:left (* 100 (/ (sx hx) (:w m)))
                        :title (str (or x-label "x") " " (fmt fmt-x hx))
                        :rows (map #(assoc % :value (fmt fmt-y (:y %))) (by-x hx))}])]])))))

(defn data-table [{:keys [series fmt-y fmt-x x-label]}]
  (let [xs (xs-of series)
        lookup (into {} (map (fn [s] [(:key s) (into {} (:values s))])) series)]
    [:div.table-wrap
     [:table
      [:thead [:tr [:th (or x-label "x")] (for [s series] ^{:key (:key s)} [:th (:label s)])]]
      [:tbody
       (for [x (reverse xs)]
         ^{:key x}
         [:tr [:td (fmt fmt-x x)]
          (for [s series]
            ^{:key (:key s)}
            [:td (some->> (get-in lookup [(:key s) x]) (fmt fmt-y))])])]]]))

(defn sparkline [values]
  (when (> (count values) 1)
    (let [xs (map first values) ys (map second values)
          sx (scale [(apply min xs) (apply max xs)] [1 99])
          sy (scale [(apply min ys) (max (apply max ys) (inc (apply min ys)))] [22 2])]
      [:svg.spark {:view-box "0 0 100 24" :preserve-aspect-ratio "none" :aria-hidden true}
       [:path {:d (str "M" (str/join "L" (map (fn [[x y]] (str (sx x) "," (sy y))) values)))}]])))
