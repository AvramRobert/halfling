(ns halfling.task
  (import (clojure.lang IMeta IPending IBlockingDeref IDeref)
          (java.util.concurrent Future)))

(declare task
         run
         run-async
         wait
         mapply
         then
         recover
         peer
         get!
         get-or-else
         fulfilled?
         broken?
         done?
         spent?
         execute
         execute-par
         remap)

(deftype Task [exec future actions recovery]
  IMeta (meta [_] {:type Task}))

(defrecord Result [status value])

(def ^:const serial :serial)
(def ^:const parallel :parallel)

(defn const-future [value]
  "Wraps a value in a completed `Future` object."
  {:added "1.0.0"}
  (reify
    IDeref
    (deref [_] value)
    IBlockingDeref
    (deref [_ _ _] value)
    IPending
    (isRealized [_] true)
    Future
    (get [_] value)
    (get [_ _ _] value)
    (isDone [_] true)
    (isCancelled [_] false)
    (cancel [_ _] false)))

(defn- task? [thing] (= Task (type thing)))

(defn- purely [a]
  (Task. serial a [] nil))

(defn- pure [result]
  (purely (const-future result)))

(defn- fail
  ([message]
   (fail message []))
  ([message trace]
   (Result. :failed
            {:message message
             :trace   trace})))

(defn- succeed [value]
  (Result. :success value))

(defn- fail? [result]
  (= :failed (:status result)))

(defn- success? [result]
  (= :success (:status result)))

(defn- is-task? [task fn]
  (assert (task? task) (str "Input to `" fn "` must be a `Task`")))

(defn- all-tasks? [tasks fn]
  (assert (every? task? tasks) (str "All values provided to `" fn "` must be `Task`s")))

(defmacro attempt [& body]
  `(try (succeed ~@body)
        (catch Exception e#
          (fail (.toString e#) (vec (.getStackTrace e#))))))

(defmacro task [& actions]
  `(Task. serial
          (const-future ~(succeed nil))
          [(fn [x#] ~(cons 'do actions))]
          nil))

(defn- remap [task map-f]
  (is-task? task "remap")
  (let [f        (:future map-f identity)
        g        (:actions map-f identity)
        exec     (:exec map-f (.exec task))
        recovery (:recovery map-f (.recovery task))]
    (Task. exec (f (.future task)) (g (.actions task)) recovery)))

(defn- execute [task]
  (is-task? task "execute")
  (letfn [(recoverable? [result] (and (fail? result) (.recovery task)))]
    (loop [result  (peer task)
           actions (.actions task)]
      (let [[f & fs] actions
            {status :status
             value  :value} result
            recover (.recovery task)]
        (cond
          (recoverable? result) (execute (halfling.task/task (recover result)))
          (fail? result) result
          (task? value) (recur (execute value) actions)
          (nil? f) result
          :else (recur (attempt (f value)) fs))))))

(defn- execute-par [task]
  (is-task? task "execute-par")
  (letfn [(recoverable? [tasks] (and (some broken? tasks) (.recovery task)))
          (recover [tasks] (halfling.task/task ((.recovery task) (filter broken? tasks))))]
    (loop [tasks (mapv run-async (get! task))]
      (cond
        (every? fulfilled? tasks)
        (let [[gather & actions] (.actions task)]
          (as-> tasks all
                (mapv get! all)
                (apply gather all)
                (pure (succeed all))
                (remap all {:actions (constantly actions)})
                (execute all)))
        (recoverable? tasks) (execute (recover tasks))
        (some broken? tasks)
        (fail "One or more tasks have failed")
        :else (recur tasks)))))

(defn success [value] (pure (succeed value)))
(defn failure [message] (pure (fail message)))

(defn done? [task]
  (is-task? task "done?")
  (realized? (.future task)))

(defn spent? [task]
  (is-task? task "spent?")
  (and (done? task)
       (empty? (.actions task))))

(defn fulfilled? [task]
  (is-task? task "fulfilled?")
  (and (done? task)
       (success? (peer task))))

(defn broken? [task]
  (is-task? task "broken?")
  (and (done? task)
       (fail? (peer task))))

(defn wait [task]
  (is-task? task "wait")
  (remap task {:future #(const-future @%)}))

(defn then [task f]
  (is-task? task "then")
  (remap task {:actions #(conj % f)}))

(defmacro then-do [task & body]
  `(then ~task (fn [x#] (do ~@body))))

(defn recover [task f]
  (is-task? task "recover")
  (remap task {:recovery f}))

(defn get! [task]
  (is-task? task "get!")
  (-> (wait task) (.future) (deref) (:value)))

(defn peer [task]
  (is-task? task "peer")
  @(.future task))

(defn get-or-else [task else]
  (is-task? task "get-or-else")
  (let [result (-> (wait task) (.future) (deref))]
    (if (fail? result) else (:value result))))

(defn mapply [f & tasks]
  (all-tasks? tasks "mapply")
  (-> (vec tasks) (succeed) (pure) (then f) (remap {:exec parallel})))

(defn zip [& tasks]
  (all-tasks? tasks "zip")
  (apply (partial mapply vector) tasks))

(defn sequenced [tasks]
  (all-tasks? tasks "sequenced")
  (let [inside-out (apply zip tasks)]
    (cond
      (set? tasks) (then inside-out set)
      (list? tasks) (then inside-out list)
      :else inside-out)))

(defmacro do-tasks [bindings & body]
  (->> (destructure bindings)
       (partition 2)
       (reverse)
       (reduce
         (fn [expr [name# binding#]]
           `(then (task ~binding#) (fn [~name#] ~expr))) (cons 'do body))))

(defn run [task]
  (is-task? task "run")
  (let [execution (.exec task)]
    (if (= serial execution)
      (pure (execute task))
      (pure (execute-par task)))))

(defn run-async [task]
  (is-task? task "run-async")
  (let [execution (.exec task)]
    (if (= serial execution)
      (purely (future (execute task)))
      (purely (future (execute-par task))))))