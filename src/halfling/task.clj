(ns halfling.task
  (:gen-class)
  (require [halfling.result :as r]))

(deftype Task [result queue])

(defmacro task
  "Takes a body of expressions and yields a `Task` object, that deferes their
  computation until run explicitly. Running the task (see `run-task`) will execute its deferred
  expressions in a separate thread and subsequently cache their result upon completion.
  Task execution can be waited upon by calling `deref-task`."
  {:added "0.1"}
  [& body] `(Task. (future (r/success nil))
                   [(fn [x#] ~(cons 'do body))]))

(defmacro when-success [^Task task & body]
  "Takes a task together with a body of expressions and only runs the body
  if the task has either succeeded, or is currently still running. If the task failed,
  then it propagates this failure and ignores the body completely.
  Note: This does `not` block. It will still execute `body`, even if the task is currently
  still running."
  {:added "0.1"}
  `(let [result# (.result ~task)]
     (if (and (realized? result#)
              (r/failed? @result#))
       (Task. result# [])
       ~(cons 'do body))))

(defn task? [x]
  "Returns `true` if input is an instance of `Task`."
  {:added "0.1"}
  (instance? Task x))

(defn completed? [^Task task]
  "Returns `true` if the task has finished its computation."
  {:added "0.1"}
  (realized? (.result task)))

(defn executed? [^Task task]
  "Returns `true` if the task has been executed completely."
  {:added "0.1"}
  (and (completed? task)
       (empty? (.queue task))))

(defn then [^Task task f]
  "Returns a new task containing the result of applying `f` to the
   value of the task that has been supplied. This application happens
   lazily and only upon completion of `task`. `f` is allowed to return both
   arbitrary values and other tasks.
   For example:
      (then (task 1) inc)
      (then (task 1) #(task (inc %)))"
  {:added "0.1"}
  (when-success task
                (Task. (.result task)
                       (conj (.queue task) f))))

(defn deref-task [task]
  "Executes a task blockingly and returns the result of its execution.
  The result is represented explicitly as a structure (see halfling.result)
  and can either be a :success or a :failure. :success will always be associated
  with the final result of that execution, whilst :failure will always be associated
  with descriptive information about its cause."
  {:added "0.1"}
  (if (task? task)
    (loop [result @(.result task)
           queue (.queue task)]
      (cond
        (r/failed? result) result
        (task? (r/get! result)) (recur (deref-task (r/get! result)) queue)
        (empty? queue) result
        :else (recur (r/attempt ((first queue) (r/get! result))) (rest queue))))
    (r/failure "Incompatible task dereference"
               (str "Cannot dereference input of `" task "`. Please provide an instance of " `Task)
               [])))

;; Should I check to see if every form is a task and fail if one is not?
;; Should I also accept non-task forms, but treat them a little differently?
(defmacro do-tasks [bindings body]
  "Similar to `let` but specific to tasks. Evaluates any number of tasks in
  a common context. The name of each binding-form is associated with the future value
  of the task to which it is bound. In contrast to `let`, this returns a new task,
  that will only perform this computation when run explicitly.
  Note: The task bindings and ultimately their execution is linearized."
  {:added "0.1"}
  (assert (vector? bindings) "`do-tasks` requires a vector for its binding")
  (assert (even? (.count bindings)) "`do-tasks` requires an even number of forms in bindings vector")
  (->> bindings
       (destructure)
       (partition 2)
       (reverse)
       (reduce (fn [f [name form]]
                 `(then ~form (fn [~name] ~f))) body)))

;; FIXME: "has completed its execution" -> Not in there
(defn run-task [^Task task]
  "Runs a task asynchronously and caches its result.
  The result is represented explicitly as a structure (see halfling.result)
  and can either be a :success or a :failure. :success will always be associated
  with the final result of that execution, whilst :failure will always be associated
  with descriptive information about its cause. Defaults to `identity` if called
  on a task that is already executing, or has completed its execution.
  Note: To wait on a task, use `deref-task`."
  {:added "0.1"}
  (when-success task (if (completed? task)
                       (Task. (future (deref-task task)) [])
                       task)))

(defn zip [& tasks]
  "Returns a new task, that gathers the values of any number of tasks in a vector.
  Order is preserved."
  {:added "0.1"}
  (reduce #(do-tasks [coll %1 val %2] (conj coll val)) (task []) tasks))

(defn zipWith [^Task task f]
  "Returns a new task, that contains the result of applying `f` to the
  value of `task`, and zipping that value with the value of `task`."
  {:added "0.1"}
  (then task #(vector (f %) %)))

(defn ap [f & tasks]
  "Returns a new task, that uses the values of any number of
  supplied tasks as function parameters for `f`. It subsequently invokes `f` on
  those values. Note: The arity of `f` is proportional to the number of supplied tasks.
  For example:
  1 task => unary function
  2 tasks => binary function
  3 tasks => ternary function
  ..."
  {:added "0.1"}
  (then (apply zip tasks) #(apply f %)))
