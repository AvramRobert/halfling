(ns halfling.result
  (:gen-class))

(defrecord Result [status val])

(declare result)

(defn of-type? [^Class c v]
  "Returns true if the type of `v` is equal to that of the
  supplied class `c`. Used for type-checking and as a more accurate
  version of `instance?`."
  {:added "0.1.1"}
  (= (type v) c))

(defn success [val]
  "Wraps a value in a succeeded `Result`.
  If the supplied value throws an exception, then this side-effect shall
  not be captured and converted to a failure. For this, see `attempt`."
  {:added "0.1.0"}
  (Result. :success val))

(defn failure
  "Creates a failed `Result` given a cause of failure, a message for
  that failure and an (optional) stack-trace. The stack trace is generally
  expected to be a vector of some individual traces."
  {:added "0.1.0"}
  ([cause message]
    (failure cause message []))
  ([cause message trace]
   (Result. :failure
            {:cause   cause
             :message message
             :trace   trace})))

(defn result?
  "Returns `true` if input is an instance of `Result`"
  {:added "0.1.0"}
  [x] (of-type? Result x))

(defmacro attempt [& body]
  "Takes a body of expressions and evaluates them in a `try` block.
  Returns a `Result` containing either the result of that
  evaluation if it has succeeded, or a failure containing its cause,
  message and stack-trace."
  {:added "0.1.0"}
  `(try (success ~(cons 'do body))
        (catch Exception e#
          (failure (.getCause e#)
                   (.getMessage e#)
                   (vec (.getStackTrace e#))))))

(defn fold [^Result result succ-f err-f]
  "Takes a result together with two functions and applies `succ-f` on that result
  in case of success, and `err-f` otherwise. If somehow the status of a result is neither
  :success nor :failure, then returns a failure."
  {:added "0.1.0"}
  (assert (result? result) "The first input parameter to `fold` must be a `Result`.")
  (case (:status result)
    :success (succ-f result)
    :failure (err-f result)
    (failure "Fold on incorrect `Result` status"
             (str "The status of `" (:status result) "` is not supported")
             [])))

(defn get! [^Result result]
  "Extracts the value of a `Result` in case it succeeded.
  Defaults to `identity` in case of a failure."
  {:added "0.1.0"}
  (assert (result? result) "The input to `get!` must be a `Result`.")
  (fold result :val identity))

(defn get-or [^Result result else]
  "Extracts the value of a `Result` in case it succeeded,
  returns `else` in case it failed."
  {:added "0.1.0"}
  (assert (result? result) "The first input parameter to `get-or` must be a `Result`.")
  (fold result get! (fn [_] else)))

(defn fmap [^Result result f]
  "Applies a function to the value of the supplied result.
  If the result failed, then it simply propagates the failure.
  If the application of `f` throws an exception, it is then converted
  to a failure."
  {:added "0.1.0"}
  (assert (result? result) "The first input parameter to `fmap` must be a `Result`.")
  (fold result #(attempt (f (get! %))) identity))

(defn join [^Result result]
  "The equivalent of `flatten` but for `Result`.
  Nested results are flattened out into a single one. If
  one of the nests is a failure, then the whole result will
  be considered a failure."
  {:added "0.1.0"}
  (assert (result? result) "The input to `join` must be a `Result`.")
  (loop [current result]
    (case (:status current)
      :failure result
      :success (if (instance? Result (get! current))
                 (recur (get! current))
                 current))))

(defn bind [^Result result f]
  "Takes a result and a function, that operates on its value and returns
  another result. Applies `f` on the value of `result` and merges the
  resulting nested results into a single one."
  {:added "0.1.0"}
  (assert (result? result) "The input to `bind` must be a `Result`.")
  (join (fmap result f)))

(defn failed? [^Result result]
  "Returns `true` if the result is a failure."
  {:added "0.1.0"}
  (assert (result? result) "The input to `failed?` must be a `Result`.")
  (fold result (fn [_] false) (fn [_] true)))

(defn success? [^Result result]
  "Returns `true` if the result is a success."
  {:added "0.1.0"}
  (assert (result? result) "The input to `success?` must be a `Result`.")
  (not (failed? result)))