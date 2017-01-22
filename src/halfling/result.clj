(ns halfling.result
  (:gen-class))

(defrecord Result [status val])

(defn failure [cause message trace]
  (Result. :failure
           {:cause   cause
            :message message
            :trace   trace}))

(defn success [data]
  (Result. :success data))

(defn fold [^Result result succ-f err-f]
  (case (:status result)
    :success (succ-f result)
    :failure (err-f result)
    (failure "Fold on incorrect `Result` status"
             (str "The status of `" (:status result) "` is not supported")
             [])))

(defmacro attempt [body]
  `(try (success ~body)
        (catch Exception e#
          (failure (.getCause e#)
                   (.getMessage e#)
                   (vec (.getStackTrace e#))))))

(defn get! [^Result result]
  (fold result :val identity))

(defn fmap [^Result result f]
  (fold result #(attempt (f (get! %))) identity))

(defn join [^Result result]
  (loop [current result]
    (case (:status current)
      :failure result
      :success (if (instance? Result (:data current))
                 (recur (:data current))
                 current))))

(defn failed? [^Result result]
  (fold result (fn [_] false) (fn [_] true)))