(ns geppetto.analysis
  (:require [clojure.set :as set])
  (:require [incanter.stats :as stats])
  (:require [plumbing.graph :as graph])
  (:use [geppetto.fn]))

(defn try-all
  [g args default-params metric repetitions]
  (let [ps (all-fn-params-combinations g)
        f (graph/eager-compile g)
        uniq-params (set (for [params ps] (merge params default-params)))]
    (into {} (for [params uniq-params]
               (let [results (for [_ (range repetitions)]
                               (get (f (assoc args :params params))
                                    metric))]
                 [params (stats/mean results)])))))

(defn- calc-group-effect
  [results sample-size grouped-results grouped-count]
  (let [val-stats (for [[val ps] grouped-results]
                    (let [rs (map #(get results %) ps)
                          m (stats/mean rs)]
                      {:val val :n (count rs) :m m
                       :ss (reduce + (map (fn [r] (Math/pow (- r m) 2.0)) rs))}))
        within-group-var (/ (reduce + (map :ss val-stats)) (- sample-size grouped-count))
        overall-mean (stats/mean (vals results))
        between-group-var (/ (reduce + (map (fn [{:keys [n m]}]
                                              (* n (Math/pow (- m overall-mean) 2.0)))
                                            val-stats))
                             (dec grouped-count))
        f-stat (if (= 0 within-group-var) Double/NaN
                   (/ between-group-var within-group-var))]
    {:f-stat f-stat :means (into {} (map (fn [{:keys [val m]}] [val m]) val-stats))}))

(defn transform-results
  "Go from [{:result 10 :params \"{:simulation 0 :Seed 123 :Foo 3 :Bar 7}\" ...}]
   to {{:Foo 3 :Bar 7} {:result 10 ...} ...}"
  [results]
  ;; TODO: FIX for comparative runs (:control-params, :comparative-params)
  (into {} (for [rs results]
             (let [params (dissoc (read-string (:params rs)) :simulation :Seed)]
               [params (dissoc rs :params :simulation :Seed)]))))

(defn calc-effect
  ([results]
     (let [t-results (if (map? results) results (transform-results results))
           param-keys (set (keys (first (keys t-results))))
           metrics (set/difference (set (keys (first (vals t-results)))) param-keys)]
       (prn metrics)
       (into {} (for [metric metrics]
                  [metric (calc-effect t-results metric)]))))
  ([results metric]
     (let [t-results (if (map? results) results (transform-results results))
           params (first (keys t-results))
           ;; extract just the metric for each param-key
           m-results (into {} (for [[params rs] t-results] [params (get rs metric)]))
           sample-size (count m-results)]
       (into {} (for [param (keys params)
                      :let [grouped-results (group-by #(get % param) (keys m-results))
                            grouped-count (count grouped-results)]
                      :when (not= 1 grouped-count)]
                  [param (calc-group-effect m-results sample-size grouped-results grouped-count)])))))
