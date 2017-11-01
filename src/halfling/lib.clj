(ns halfling.lib
  (require '[halfling.task :as t]))

(defn p-map [f coll]
  (let [cores      (.availableProcessors (Runtime/getRuntime))
        partitions (Math/round ^Float (/ (count coll) cores))
        gather     (partial t/mapply (fn [& args] (apply concat args)))]
    (->> coll
         (partition partitions)
         (map #(t/task (map f %)))
         (apply gather))))
