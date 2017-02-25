(ns halfling.task
  (:gen-class)
  (require [halfling.result :as r])
  (:import (clojure.lang IDeref IPending IBlockingDeref)
           (java.util.concurrent TimeoutException Future)))


(declare run, run-async, peer)

(deftype ^:private Task [result queue])
(deftype ^:private ParTask [tasks queue])

(defn const-future [value]
  "Wraps a value in a completed `Future` object."
  {:added "0.1.0"}
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

(defn ^:private point
  "Creates a completed task. It can additionally accept a queue
  of operations, that are to be applied on `value`."
  {:added "0.1.0"}
  ([value]
   (point value []))
  ([value queue]
   (Task. (const-future value) queue)))

(defn ^:private timeout [ms]
  "Creates a failed result in case of timeout."
  {:added "0.1.0"}
  (r/attempt
    (throw (new TimeoutException (str "Task took too long while waiting (" ms " ms)")))))

(defmacro task
  "Takes a body of expressions and yields a `Task` object, that deferes their
  computation until run explicitly. Running the task (see `run-async` or `run`) will
  execute its deferred expressions and subsequently cache their result upon completion.
  In case of `run-async`, task execution can be waited upon by calling `wait`."
  {:added "0.1.0"}
  [& body] `(Task. (const-future (r/success nil))
                   [(fn [x#] ~(cons 'do body))]))

(defmulti peer
          "Returns the result of a task if it is `completed`. If
          the task is executing or otherwise unevaluated, returns nil."
          {:added    "0.1.0"
           :revision "0.1.1"} class)

(defmethod peer Task [task]
  (let [result (.result task)]
    (when (realized? result) @result)))

(defmethod peer ParTask [task] (r/success nil))

(defmulti completed?
          "Returns `true` if the task has finished its computation."
          {:added    "0.1.0"
           :revision "0.1.1"} class)

(defmethod completed? Task [task]
  (realized? (.result task)))
(defmethod completed? ParTask [_] true)

(defn task? [x]
  "Returns `true` if input is an instance of `Task`."
  {:added    "0.1.0"
   :revision "0.1.1"}
  (or
    (r/of-type? Task x)
    (r/of-type? ParTask x)))

(defn executed? [task]
  "Returns `true` if the task has been executed completely."
  {:added "0.1.0"}
  (assert (task? task) "The input to `executed?` must be a `Task.")
  (and (completed? task)
       (empty? (.queue task))))

(defmacro ^:private when-success [task & body]
  "Takes a task together with a body of expressions and only runs the body
  if the task has either succeeded, or is currently still running. If the task failed,
  then it propagates this failure and ignores the body completely.
  Note: This does `not` block. It will still execute `body`, even if the task is currently
  still running."
  {:added    "0.1.0"
   :revision "0.1.1"}
  `(let [result# (peer ~task)]
     (if (and
           (not (nil? result#))                             ;; don't use `completed?` here because of possible race conditions
           (r/failed? result#))
       (Task. result# [])
       ~(cons 'do body))))

(defmulti ^:private bind
          "Curried `bind` operation for `Task`. Given a task and a function,
          returns another task containing the result of applying the function
          to the value of that task once it's completed. In general, the function
          should preferably return another task. This constraint is however not enforced
          here."
          {:added "0.1.1"}
          class)

(defmethod ^:private bind Task [task]
  (fn [f]
    (when-success task
                  (Task. (.result task)
                         (conj (.queue task) f)))))

(defmethod ^:private bind ParTask [task]
  (fn [f]
    (when-success task
                  (ParTask. (.tasks task)
                            (conj (.queue task) f)))))


(defn then [task f]
  "Returns a new task containing the result of applying `f` to the
   value of the task that has been supplied. This application happens
   lazily and only upon completion of `task`. `f` is allowed to return both
   arbitrary values and other tasks.
   For example:
     (then (task 1) inc)
     (then (task 1) #(task (inc %)))"
  {:added    "0.1.0"
   :revision "0.1.1"}
  (assert (task? task) "Input to `then` must be a `Task`")
  ((bind task) f))

(defmacro do-tasks [bindings & body]
  "Similar to `let` but specific to tasks. Evaluates any number of tasks in
  a common context. The name of each binding-form is associated with the future value
  of the task to which it is bound. In contrast to `let`, this returns a new task,
  that will only perform this computation when run explicitly. It also accepts simple
  expressions as forms. These are automatically lifted in a `Task` context.

  For example:
  (do-tasks [a (task (+ 1 1))
             b (+ a 1)]

  is equivalent tor

  (do-tasks [a (task (+ 1 1))
             b (task (+ a 1)]

  Note: The form execution is serialised."
  {:added "0.1.0"}
  (assert (vector? bindings) "`do-tasks` requires a vector for its binding")
  (assert (even? (.count bindings)) "`do-tasks` requires an even number of forms in bindings vector")
  (->> bindings
       (destructure)
       (partition 2)
       (reverse)
       (reduce (fn [f [name form]]
                 `(then (task ~form) (fn [~name] ~f))) (cons 'do body))))

(defmulti run
          "Runs a task blockingly and returns the result of its execution.
        The result is represented explicitly as a structure (see halfling.result)
        and can either be a :success or a :failure. :success will always be associated
        with the final result of that execution, whilst :failure will always be associated
        with descriptive information about its cause."
          {:added    "0.1.0"
           :revision "0.1.1"} class)

(defmethod run Task [task]
  (loop [result @(.result task)
         queue (.queue task)]
    (cond
      (r/failed? result) result
      (task? (r/get! result)) (recur (run (r/get! result)) queue)
      (empty? queue) result
      :else (recur (r/attempt ((first queue) (r/get! result))) (rest queue)))))

(defmethod run ParTask [task]
  (loop [all (map run-async (.tasks task))]
    (if (every? executed? all)
      (let [results (map #(deref (.result %)) all)]
        (if (every? r/success? results)
          (let [f (first (.queue task))
                args (map r/get! results)]
            (run (point (r/attempt (apply f args)) (rest (.queue task)))))
          (let [failed (filter r/failed? results)]
            (r/failure "One or more tasks have failed" (str "A number of " (count failed) " tasks have failed")))))
      (recur all))))

(defn run-async [task]
  "Runs a task asynchronously and caches its future result. Returns a new task, that
  will contain the cached result. The result is represented explicitly as
  a structure (see halfling.result) and can either be a :success or a :failure.
  :success will always be associated with the final result of that execution, whilst
  :failure will always be associated with descriptive information about its cause.
  Defaults to `identity` if called on a task that is already executing, or ha1s completed
  its execution. Note: To wait on a task, use `wait`."
  {:added "0.1.0"}
  (assert (task? task) "The input to `run-async` must be a `Task`")
  (when-success task (if (completed? task)
                       (Task. (future (run task)) [])
                       task)))

(defn wait
  "Waits for `task` to complete. It can either wait indefinitely or
  for a certain amount of milliseconds. Additionally it may accept a
  value that is to be returned in case of timeout."
  {:added "0.1.0"}
  ([^Task task]
   (assert (task? task) "The input to `wait` must be a `Task`.")
   @(.result task))
  ([^Task task timeout-ms]
   (assert (task? task) "The input to `wait` must be a `Task`.")
   (wait task timeout-ms (timeout timeout-ms)))
  ([^Task task timeout-ms timeout-val]
   (assert (task? task) "The input to `wait` must be a `Task`.")
   (deref (.result task) timeout-ms timeout-val)))

(defn ap [f & tasks]
  "Returns a new task, that uses the values of any number of
   supplied tasks as function parameters for `f`. It subsequently invokes `f` on
   those values. Note: The arity of `f` is proportional to the number of supplied tasks.
   For example:
    1 task => unary function
    2 tasks => binary function
    3 tasks => ternary function
    ..."
  {:added    "0.1.0"
   :revision "0.1.1"}
  (ParTask. (flatten tasks) [f]))

(defn zip [& tasks]
  "Takes any number of tasks and returns a new task, that gathers their values in a vector.
  Order is preserved."
  {:added    "0.1.0"
   :revision "0.1.1"}
  (assert (every? task? tasks) "Inputs to `zip` must be tasks.")
  (ap vector tasks))

(defn zip-with [^Task task f]
  "Returns a new task, that contains the result of applying `f` to the
  value of `task`, and zipping that value with the value of `task`."
  {:added "0.1.0"}
  (assert (task? task) "The first input parameter to `zip-with` must be a `Task`.")
  (then task #(vector (f %) %)))

;; FIXME: I don't see why this should'nt also support maps
(defn sequenced [coll]
  "Takes a collection of tasks and returns a task containing a collection
  with the concrete values of the previous tasks."
  {:added "0.1.0"}
  (assert (every? task? coll) "The input to `sequence` must be some collection of tasks")
  (cond
    (set? coll) (then (apply zip coll) set)
    (list? coll) (then (apply zip coll) list)
    :else (apply zip coll)))

(defn p-map [f coll]
  "An implementation of parallel map using the task API.
  Usage is analogous to that of `clojure.core/pmap`"
  {:added "0.1.1"}
  (let [cores (.availableProcessors (Runtime/getRuntime))
        partitions (Math/round ^Float (/ (.count coll) cores))]
    (->> coll
         (partition partitions)
         (map #(task (map f %)))
         (ap (fn [& args] (apply concat args))))))

