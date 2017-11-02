(ns halfling.lib
  (require [halfling.task :as t]))

(defn p-map
  "Applies a function `f` on all items of a collection `coll` in parallel.
   A version of `pmap` that uses the `Task` API for parallelism."
  {:added "1.0.0"}
  [f coll]
  (let [cores      (.availableProcessors (Runtime/getRuntime))
        partitions (Math/round ^Float (/ (count coll) cores))
        gather     (partial t/mapply (fn [& args] (apply concat args)))]
    (->> coll
         (partition partitions)
         (map #(t/task (map f %)))
         (apply gather))))
