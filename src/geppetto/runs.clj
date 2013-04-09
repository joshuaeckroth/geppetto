(ns geppetto.runs
  (:use [korma.db :only [transaction]])
  (:use [korma.core])
  (:use [geppetto.models])
  (:use [geppetto.misc]))

(defn add-simulation
  [runid control-params comparison-params]
  (:generated_key
   (with-db @geppetto-db
     (insert simulations
             (values [{:runid runid
                       :controlparams control-params
                       :comparisonparams comparison-params}])))))

(defn simulation-count
  [runid]
  (:count (first (with-db @geppetto-db
                   (select simulations (where {:runid runid})
                           (aggregate (count :runid) :count))))))

(defn add-sim-results
  [simid resultstype results]
  (with-db @geppetto-db
    (let [rs (dissoc results :control-params :comparison-params :params)
          vals (for [[field val] rs]
                 (let [entry {:simid simid :resultstype (name resultstype)
                              :field (name field)}]
                   (cond (= Double (type val))
                         (assoc entry :valtype "floatval" :floatval val
                                :strval nil :intval nil :boolval nil)
                         (= Integer (type val))
                         (assoc entry :valtype "intval" :intval val
                                :strval nil :floatval nil :boolval nil)
                         (= Boolean (type val))
                         (assoc entry :valtype "boolval" :intval nil
                                :strval nil :floatval nil
                                :boolval (if (.booleanValue val) 1 0))
                         :else
                         (assoc entry :valtype "strval" :strval val
                                :intval nil :floatval nil :boolval nil))))]
      (insert results-fields (values (vec vals))))))

(defn add-run
  "Expected keys in run-meta map: ..."
  [run-meta]
  (:generated_key (with-db @geppetto-db (insert runs (values [run-meta])))))

(defn commit-run
  "Only sends the last results from each simulation."
  [run-meta all-results]
  (with-db @geppetto-db
    (let [runid (add-run run-meta)]
      (when runid
        (doseq [sim-results all-results]
          (let [control-params (or (:control-params
                                    (:control sim-results))
                                   (:params (:control sim-results)))
                comparison-params (:comparison-params
                                   (:comparison sim-results))
                simid (add-simulation runid control-params comparison-params)]
            (doseq [resultstype [:control :comparison :comparative]]
              (when-let [results (get sim-results resultstype)]
                (add-sim-results simid resultstype results)))))))))

(defn get-run
  [runid]
  (first
   (with-db @geppetto-db
     (select runs
             (with parameters)
             (where {:runid runid})
             (fields :runid :starttime :endtime :username
                     :seed :nthreads :repetitions
                     :pwd :hostname :recorddir :datadir :project
                     :commit :commitdate :commitmsg :branch
                     :runs.paramid :parameters.name
                     :parameters.problem :parameters.description
                     :parameters.control :parameters.comparison)))))

(defn list-runs
  []
  (with-db @geppetto-db
    (select runs (with parameters))))

(defn delete-run
  [runid]
  (with-db @geppetto-db
    (let [run (get-run runid)]
      (doseq [simid (map :simid (select simulations (fields :simid) (where {:runid runid})))]
        (delete results-fields (where {:simid simid})))
      (delete simulations (where {:runid runid}))
      (delete runs (where {:runid runid})))))

(defn list-projects
  []
  (sort (set (filter identity (map :project (with-db @geppetto-db
                                       (select runs (fields :project))))))))

(defn set-project
  [runid project]
  (with-db @geppetto-db
    (update runs (set-fields {:project project}) (where {:runid runid}))))

(defn gather-results-fields
  [runid resultstype]
  (with-db @geppetto-db
    (let [simid (:simid (first (select simulations
                                       (fields :simid)
                                       (where {:runid runid})
                                       (limit 1))))]
      (sort (set (map (comp keyword :field)
                    (select results-fields
                            (fields :field)
                            (where {:simid simid :resultstype (name resultstype)}))))))))

(defn get-sim-results
  [simid resultstype selected-fields]
  (with-db @geppetto-db
    (if (empty? selected-fields) {}
        (apply merge
               (map (fn [{:keys [field valtype strval floatval intval boolval]}]
                    {(keyword field)
                     (cond (= "strval" valtype) strval
                           (= "floatval" valtype) floatval
                           (= "intval" valtype) intval
                           (= "boolval" valtype) (Boolean. boolval))})
                  (select results-fields
                          (fields :field :valtype :strval :floatval :intval :boolval)
                          (where (and (= :simid simid)
                                      (= :resultstype (name resultstype))
                                      (apply or (map (fn [f] (= :field f))
                                                   (map name selected-fields)))))))))))

(defn get-results
  [runid resultstype selected-fields]
  (with-db @geppetto-db
    (let [run (get-run runid)
          sims (select simulations (fields :simid :controlparams :comparisonparams)
                       (where {:runid runid}))]
      (for [sim sims]
        (assoc (get-sim-results (:simid sim) resultstype selected-fields)
          :control-params (:controlparams sim)
          :comparison-params (:comparisonparams sim))))))

(defn get-raw-results
  "Get results without associating in simid and control-params/comparison-params."
  [runid]
  (with-db @geppetto-db
    (let [run (get-run runid)
          sims (select simulations (fields :simid) (where {:runid runid}))
          fields (into {} (for [resultstype [:control :comparison :comparative]]
                            [resultstype (gather-results-fields runid resultstype)]))]
      (for [sim sims]
        (into {} (for [resultstype [:control :comparison :comparative]]
                   [resultstype (get-sim-results (:simid sim) resultstype
                                                 (get fields resultstype))]))))))
