(ns halfling.task2
  (:import (clojure.lang IMeta IPending IBlockingDeref IDeref)
           (java.util.concurrent Future)))

(deftype Task [future actions recovery]
  IMeta (meta [_] {:type Task}))

(defrecord Result [status value])

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

(defn- task? [task] (= Task (type task)))

(defn- pure [result]
  (Task. (const-future result) [] nil))

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

(defmacro attempt [& body]
  `(try (succeed ~@body)
        (catch Exception e#
          (fail (.toString e#) (vec (.getStackTrace e#))))))

(defmacro task [& actions]
  `(Task. (const-future (succeed nil))
          [(fn [x#] ~(cons 'do actions))]
          nil))

(defmacro deft [name args & defs]
  `(def ~name
     (fn ~args
       (let [tsk# (first ~args)]
         (assert (task? tsk#) (str "First argument to `deft` must always be a `Task`"))
         ~@defs))))

(defn success [value] (pure (succeed value)))
(defn failure [message] (pure (fail message)))
(defn from-result [result] (pure result))

(deft get! [task] @(.future task))

(deft fmap [task f]
 (Task. (.future task) (conj (.actions task) f) (.recovery task)))

(comment
  "f :: a -> b => attempt => f :: a -> Result b
   f :: a -> Exception => attempt => f :: a -> Result b
   f :: a -> Task b => attempt => f :: a -> Result (Task b)")

(deft run [task]
  (loop [result (get! task)
         actions (.actions task)]
    (let [[f & fs] actions
          {status :status
           value  :value} result]
      (cond
        (fail? result) result
        (task? value) (recur (run value) actions)
        (nil? f) result
        :else (recur (attempt (f value)) fs)))))