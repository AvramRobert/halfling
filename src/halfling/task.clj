(ns halfling.task
  (:import (clojure.lang IMeta IPending IBlockingDeref IDeref)
           (java.util.concurrent Future)
           (java.io Writer)))

(declare task
         run
         run-async
         wait
         mapply
         then
         recover
         get!
         get-or-else
         fulfilled?
         broken?
         done?
         executed?
         attempt
         task?
         execute
         execute-par
         remap)

(def ^:const serial :serial)
(def ^:const parallel :parallel)

(def ^:private ^:const successful :success)
(def ^:private ^:const failed :failed)

(deftype Task [exec future actions recovery]
  IMeta (meta [_] {:type Task})
  IDeref (deref [this] (get! this))
  IBlockingDeref (deref [this timeout else] (get! this timeout else)))

(defmethod print-method Task [tsk ^Writer writer]
  (letfn [(write! [status value]
            (.write writer (str "#Task"
                                {:executed? (executed? tsk)
                                 :status    status
                                 :value     value})))]
    (cond
      (fulfilled? tsk) (write! successful (get! tsk))
      (broken? tsk) (write! failed (get! tsk))
      :else (write! :pending nil))))

(defrecord Result [status value])

(defn- is-task? [task fn]
  (assert (task? task) (str "Input to `" fn "` must be a `Task`")))

(defn- all-tasks? [tasks fn]
  (assert (every? task? tasks) (str "All values provided to `" fn "` must be `Task`s")))

(defn const-future
  "Wraps a value in a completed `Future` object."
  {:added "1.0.0"}
  [value]
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

(defn- task?
  "Returns `true` if `thing` is of type `Task`, `false` otherwise."
  {:added "1.0.0"}
  [thing] (= Task (type thing)))

(defn- purely
  "Given any value `a`, returns a task containing it."
  {:added "1.0.0"}
  [a] (Task. serial a [] nil))

(defn- pure
  "Given a `Result`, returns a `task` containing it."
  {:added "1.0.0"}
  [^Result result] (purely (const-future result)))

(defn- fail
  "Returns a failed `Result`."
  {:added "1.0.0"}
  [error]
  (Result. failed error))

(defn- succeed
  "Returns a successful `Result`."
  {:added "1.0.0"}
  [value]
  (Result. successful value))

(defn- failure?
  "Returns `true` if `result` is failed, `false` otherwise."
  {:added "1.0.0"}
  [^Result result]
  (= :failed (:status result)))

(defn- success?
  "Returns `true` if `result` is successful, `false` otherwise."
  {:added "1.0.0"}
  [^Result result]
  (= :success (:status result)))

(defn- peer
  "Returns the `Result` of `task`, which contains the value and whether
   it was successful or not. Blocks `task` until the task is realised.
   If you want the concrete value of `Result`, see `get!`.
   Note: Doesn't run the task!"
  {:added "1.0.0"}
  [^Task task] (is-task? task "peer")
  @(.future task))

(defn- remap
  "Changes the attributes of `task` as specified by
   the mappings in `map-f`.
   `map-f` may contain the following:
      {:future   a function applied on the current future, that returns a new one
       :actions  a function applied on the current collection of actions, that returns a new one
       :exec     a value from #{:serial, :parallel}
       :recovery a recovery function}
   Returns a new task containing the change specified in `map-f`."
  {:added "1.0.0"}
  [map-f ^Task task] (is-task? task "remap")
  (let [f        (:future map-f identity)
        g        (:actions map-f identity)
        exec     (:exec map-f (.exec task))
        recovery (:recovery map-f (.recovery task))]
    (Task. exec (f (.future task)) (g (.actions task)) recovery)))

(defmacro attempt
  "Safely runs a `body` in a `try` block
   and captures its outcome in a `Result`.
   In case of a success, the result will look like:
   {:status :success
    :value  <result of computation>}

   In case of a failure, the result will look like:
   {:status :failure
    :value  <throwable/exception object>}"
  {:added "1.0.0"}
  [& body]
  `(try (succeed (do ~@body))
        (catch Exception e# (fail e#))))

(defmacro task
  "Takes and number of expressions or actions and
   returns a task that will lazily evaluate them."
  {:added "1.0.0"}
  [& actions]
  `(Task. serial
          (const-future ~(succeed nil))
          [(fn [x#] ~(cons 'do actions))]
          nil))

(defn- execute
  "Executes a `task` blockingly until it finishes.
   Returns a `Result` of that execution."
  {:added "1.0.0"}
  [^Task task] (is-task? task "execute")
  (letfn [(recoverable? [result] (and (failure? result) (.recovery task)))
          (parallel-task? [tsk] (and (task? tsk) (= parallel (.exec tsk))))]
    (loop [result  (peer task)
           actions (.actions task)]
      (let [[f & fs] actions
            value   (:value result)
            recover (.recovery task)]
        (cond
          (recoverable? result) (execute (halfling.task/task (recover value)))
          (failure? result) result
          (parallel-task? value) (recur (execute-par value) actions)
          (task? value) (recur (execute value) actions)
          (nil? f) result
          :else (recur (attempt (f value)) fs))))))

(defn- execute-par
  "A `task` executed by `execute-par` will contain as payload a collection
   of other tasks that are to be executed in parallel.
   Tries to execute that payload in parallel, whilst blocking the current thread
   until they finish.
   Returns a `Result` of that execution."
  {:added "1.0.0"}
  [^Task task] (is-task? task "execute-par")
  (letfn [(recoverable? [tasks] (and (some broken? tasks) (.recovery task)))
          (recover [tasks] (halfling.task/task ((.recovery task) (->> tasks (filter broken?) (mapv get!)))))]
    (let [[compose & actions] (.actions task)
          tasks (->> (get! task)
                     (mapv run-async)
                     (mapv wait))]
      (cond (every? fulfilled? tasks)
            (->> tasks
                 (mapv get!)
                 (apply compose)
                 (succeed)
                 (pure)
                 (remap {:actions (constantly actions)})
                 (run)
                 (peer))
            (recoverable? tasks) (peer (run (recover tasks)))
            :else (fail (->> tasks (filter broken?) (map get!)))))))

(defn success
  "Given some `value`, returns a realised successful task containing the given `value`"
  {:added "1.0.0"}
  [value] (pure (succeed value)))

(defn failure
  "Given some string `message`, returns a realised failed task containing an error with the given `message`"
  {:added "1.0.0"}
  [message] (pure (fail (Exception. message))))

(defn failure-t
  "Given a proper error `Throwable`, returns a realised failed task containing it."
  {:added "1.2.0"}
  [^Throwable throwable] (pure (fail throwable)))

(defn done?
  "Returns `true` if `task` has been realised, `false` otherwise.
   Note: Doesn't check if the task has been run.
   An un-run task is still considered to be realised.
   For both checks, take a look at `executed?`."
  {:added "1.0.0"}
  [^Task task] (is-task? task "done?")
  (realized? (.future task)))

(defn executed?
  "Returns `true` if `task` has been realised and run, `false` otherwise."
  {:added "1.0.0"}
  [^Task task] (is-task? task "executed?")
  (and (done? task)
       (empty? (.actions task))))

(defn fulfilled?
  "Returns `true` if `task` has been realised and was successful, `false` otherwise.
   Note: Doesn't check if a task has been run.
   An un-run task is still considered to be realised."
  {:added "1.0.0"}
  [^Task task] (is-task? task "fulfilled?")
  (and (done? task)
       (success? (peer task))))

(defn broken?
  "Returns `true` if `task` has been realised and failed, `false` otherwise.
   Note: Doesn't check if a task has been run.
   An un-run task is still considered to be realised."
  {:added "1.0.0"}
  [^Task task] (is-task? task "broken?")
  (and (done? task)
       (failure? (peer task))))

(defn wait
  "Blocks thread until `task` has been realised."
  {:added "1.0.0"}
  ([^Task task] (is-task? task "wait")
   (remap {:future #(const-future @%)} task))
  ([^Task task timeout else] (is-task? task "wait")
   (remap {:future #(const-future (deref % timeout (succeed else)))} task)))

(defn then
  "Lazily applies a function `f` on the value of `task` only
   in case it is successful. `f` is allowed to return both simple
   values or other tasks.
   The following law applies:
    (then (task 1) #(inc %)) == (then (task 1) #(task (inc %))"
  {:added "1.0.0"}
  [^Task task f] (is-task? task "then")
  (remap {:actions #(conj % f)} task))

(defmacro then-do
  "A version of `then` where the result of `task` is ignored.
   Lazily runs the `body` after the `task` ignoring the `task`s return.
   Mainly thought for sequential side-effects.
   The following law applies:
    (-> (task 1) (then-do (println 12))) == (do-tasks [_ (task 1) _ (println 12)])"
  {:added "1.0.0"}
  [^Task task & body]
  `(then ~task (fn [x#] (do ~@body))))

(defn recover
  "Recovers a failed `task` with function `f`.

   NON-PARALLEL EXECUTION: `f` has a `Throwable` error object as an argument.
   PARALLEL EXECUTION: `f` has a collection of `Throwable` error objects as an argument.

   `f` may either return a simple value or another task.

   Note: Tasks that are generally run in parallel are tasks composed together
   using `mapply`, `zip` or `sequenced`."
  {:added "1.0.0"}
  [^Task task f] (is-task? task "recover")
  (remap {:recovery f} task))

(defn get!
  "Returns the value of the `Result` of `task`. Blocks `task` until it is realised.
   If `task` is successful, returns its value.
   If it is failed, returns the `Throwable` error object.
   Note: Doesn't run the task!"
  {:added "1.0.0"}
  ([^Task task] (is-task? task "get!")
   (-> (wait task) (.future) (deref) (:value)))
  ([^Task task timeout else] (is-task? task "get!")
   (-> (wait task timeout else) (.future) (deref) (:value))))

(defn get-or-else
  "Returns the value of the `Result` of `task` if it was successful,
   returns `else` otherwise. Block `task` until it is realised.
   Note: Doesn't run the task!"
  {:added "1.0.0"}
  ([^Task task else] (is-task? task "get-or-else")
   (let [result (-> (wait task) (.future) (deref))]
     (if (failure? result) else (:value result))))
  ([^Task task timeout else] (is-task? task "get-or-else")
   (let [result (-> (wait task timeout else) (.future) (deref))]
     (if (failure? result) else (:value result)))))

(defn mapply
  "Takes all the values of `tasks` and lazily applies a
   function `f` on them if they were successful.
   If one task fails, the total result will be a failure.
   `tasks` will be run in parallel.
   `f` is allowed to return both simple values and other tasks.
   The arity of `f` is equal to the amount of `tasks`.
   The following law applies:
   (arity f) == (count tasks)"
  {:added "1.0.0"}
  [f & tasks] (all-tasks? tasks "mapply")
  (remap {:exec parallel} (-> (vec tasks) (succeed) (pure) (then f))))

(defn zip
  "Takes the values of `tasks` and aggregates them to a vector if they were successful.
   If one task fails, the total result will be a failure.
   `tasks` will be run in parallel."
  {:added "1.0.0"}
  [& tasks] (all-tasks? tasks "zip")
  (apply (partial mapply vector) tasks))

(defn sequenced
  "Takes a collection of tasks and lazily transforms it in a task containing
   a collection of all the values of those tasks.
   If one task fails, the total result will be a failure.
   `tasks` will be run in parallel."
  {:added "1.0.0"}
  [tasks] (all-tasks? tasks "sequenced")
  (let [inside-out (apply zip tasks)]
    (cond
      (set? tasks) (then inside-out set)
      (list? tasks) (then inside-out list)
      :else inside-out)))

(defmacro do-tasks
  "A `let`-like construct that allows working with
  tasks as if they were successfully realised.
  Binds single tasks to names in a `let`-like fashion and
  considers the names as being the realised values of those tasks.
  In addition, it also supports non-task expressions. These will
  automatically be lifted to a `task`.
  Example:
   (do-tasks [a (task 1)
              _ (println a)
              b (task 2)]
    (+ a b)"
  {:added "1.0.0"}
  [bindings & body]
  (->> (destructure bindings)
       (partition 2)
       (reverse)
       (reduce
         (fn [expr [name# binding#]]
           `(then (task ~binding#) (fn [~name#] ~expr))) (cons 'do body))))

(defn run
  "Runs `task` synchronously.
   Returns another task containing the result of that execution.
   Note: Parallel tasks will be run in parallel,
   but the current thread will be blocked until all tasks realise."
  {:added "1.0.0"}
  [task] (is-task? task "run")
  (let [execution (.exec task)]
    (if (= serial execution)
      (pure (execute task))
      (pure (execute-par task)))))

(defn run-async
  "Runs `task` asynchronously.
   Returns another task containing the result of that execution.
   Note: Returns immediately. Parallel tasks will be run in parallel."
  {:added "1.0.0"}
  [task] (is-task? task "run-async")
  (let [execution (.exec task)]
    (if (= serial execution)
      (purely (future (execute task)))
      (purely (future (execute-par task))))))