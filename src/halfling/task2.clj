(ns halfling.task2
  (:import (clojure.lang IMeta IPending IBlockingDeref IDeref)
           (java.util.concurrent Future)))

(declare task run run-async fulfilled? broken? get!)

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

(defmacro attempt [& body]
  `(try (succeed ~@body)
        (catch Exception e#
          (fail (.toString e#) (vec (.getStackTrace e#))))))

(defmacro task [& actions]
  `(Task. serial
          (const-future (succeed nil))
          [(fn [x#] ~(cons 'do actions))]
          nil))

(defmacro deft [name args & defs]
  `(def ~name
     (fn ~args
       (let [tsk# (first ~args)]
         (assert (task? tsk#) (str "First argument to `deft` must always be a `Task`"))
         ~@defs))))

(deft remap [task map-f]
  (let [f        (:future map-f identity)
        g        (:actions map-f identity)
        exec     (:exec map-f (.exec task))
        recovery (:recovery map-f (.recovery task))]
  (Task. exec (f (.future task)) (g (.actions task)) recovery)))

(deft execute [task]
  (letfn [(recoverable? [result] (and (fail? result) (.recovery task)))]
    (loop [result @(.future task)
           actions (.actions task)]
      (let [[f & fs] actions
            {status :status
             value  :value} result
            recover (.recovery task)]
        (cond
          (recoverable? result) (execute (halfling.task2/task (recover result)))
          (fail? result) result
          (task? value) (recur (execute value) actions)
          (nil? f) result
          :else (recur (attempt (f value)) fs))))))

(deft execute-par [task]
  (letfn [(recoverable? [tasks] (and (some broken? tasks) (.recovery task)))
          (recover [tasks] (halfling.task2/task ((.recovery task) (filter broken? tasks))))]
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

(deft done? [task]
  (realized? (.future task)))

(deft spent? [task]
  (and (done? task)
       (empty? (.actions task))))

(deft fulfilled? [task]
  (and (done? task)
       (success? @(.future task))))

(deft broken? [task]
  (and (done? task)
       (fail? @(.future task))))

(deft wait [task]
  (remap task {:future #(const-future @%)}))

(deft then [task f]
  (remap task {:actions #(conj % f)}))

(deft recover [task f]
  (remap task {:recovery f}))

(deft get! [task] (-> (wait task) (.future) (deref) (:value)))

(defn mapply [f & tasks]
  (assert (every? task? tasks) "All values provided to `mapply` must be tasks")
  (-> (vec tasks) (succeed) (pure) (then f) (remap {:exec parallel})))

(defn zip [& tasks]
  (assert (every? task? tasks) "All values provided to `zip` must be tasks")
  (apply (partial mapply vector) tasks))

(defn sequenced [tasks]
  (assert (every? task? tasks) ("All values provided to `sequenced` must be tasks"))
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

(comment
  "f :: a -> b => attempt => f :: a -> Result b
   f :: a -> Exception => attempt => f :: a -> Result b
   f :: a -> Task b => attempt => f :: a -> Result (Task b)")

(deft run [task]
  (let [execution (.exec task)]
    (if (= serial execution)
      (pure (execute task))
      (pure (execute-par task)))))

(deft run-async [task]
   (let [execution (.exec task)]
     (if (= serial execution)
       (purely (future (execute task)))
       (purely (future (execute-par task))))))