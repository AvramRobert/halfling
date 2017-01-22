(ns halfling.task
  (:gen-class)
  (require [halfling.result :as r]))

(deftype Task [result queue])

(defmacro task [& body] `(Task. (future (r/success nil))
                                [(fn [x#] ~(cons 'do body))]))

(defmacro when-success [^Task task & body]
  `(let [result# (.result ~task)]
     (if (and (realized? result#)
              (r/failed? @result#))
       (Task. result# [])
       ~(cons 'do body))))

(defn task? [t] (instance? Task t))
(defn completed? [^Task task] (realized? (.result task)))
(defn executed? [^Task task] (and (completed? task)
                                  (empty? (.queue task))))
(defn then [^Task task f]
  (when-success task
                (Task. (.result task)
                       (conj (.queue task) f))))

(defn deref-task [task]
  (if (task? task)
    (loop [result @(.result task)
           queue (.queue task)]
      (cond
        (r/failed? result) result
        (task? (r/get! result)) (recur (deref-task (r/get! result)) queue)
        (empty? queue) result
        :else (recur (r/attempt ((first queue) (r/get! result))) (rest queue))))
    (r/failure "Incompatible task dereference"
               (str "Cannot dereference " task " as it is not an instance of " `Task)
               [])))

(defmacro do-tasks [bindings body]
  (assert (vector? bindings) "Bindings must be a vector")
  (assert (even? (.count bindings)) "Bindings must be even")
  (->> bindings
       (destructure)
       (partition 2)
       (reverse)
       (reduce (fn [f [name form]]
                 `(then ~form (fn [~name] ~f))) body)))

(defn run-task [^Task task]
  (when-success task (Task. (future (deref-task task)) [])))

(defn zip [& tasks]
  (reduce #(do-tasks [coll %1 val %2] (conj coll val)) (task []) tasks))

(defn zipWith [^Task task f]
  (then task #(vector (f %) %)))

(defn ap [f & tasks]
  (then (apply zip tasks) #(apply f %)))