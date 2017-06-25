(ns halfling.task-test
  (require [halfling.task :refer :all]
           [halfling.result :as r]
           [clojure.test.check.clojure-test :as ct]
           [clojure.test.check :as c]
           [clojure.test.check.generators :as gen]
           [clojure.test.check.properties :as prop]))

(def exception (new RuntimeException "Well, this is bad."))

;; I. Associativity
(defn associative [tsk f g]
  (let [a (run (then tsk #(then (f %) g)))
        b (run (then (then tsk f) g))]
    (= a b)))

(ct/defspec associativity
            100
            (letfn [(act [bool f a]
                      (if bool
                        (task (f a))
                        (throw exception)))]
              (prop/for-all [bool gen/boolean
                             a gen/nat]
                            (let [α (partial act bool inc)
                                  β (partial act bool dec)]
                              (associative (task a) α β)))))

;; II. Right identity
(defn right-id [tsk]
  (= (run (then tsk #(task %)))
     (run tsk)))

(ct/defspec right-identity
            100
            (prop/for-all [a gen/nat]
                          (right-id (task a))))

;; III. Left identity
(defn left-id [a f]
  (= (run
       (then (task a) f))
     (run (f a))))

(ct/defspec left-identity
            100
            (letfn [(f [bool a]
                      (if bool
                        (task a)
                        (task (throw exception))))]
              (prop/for-all [bool gen/boolean
                             a gen/nat]
                            (left-id a (partial f bool)))))


;; IV. Asynchronicity
(defn asynchronous [duration start end]
  (< (double (/ (- end start) 1000000.0)) duration))

(defn consistent [tsk expected]
  (= (run tsk) expected))

(defn waitable [tsk expected]
  (= (wait (run-async tsk)) expected))

(defn peerable [tsk]
  (= (peer (run-async tsk)) nil))

(defn timoutable
  ([tsk ms]
   (r/failed? (wait (run-async tsk) ms)))
  ([tsk ms val]
   (= val (wait (run-async tsk) ms val))))

(ct/defspec asynchronicity
            100
            (prop/for-all [i (gen/choose 1 3)
                           value gen/nat]
                          (let [duration (* i 5)
                                work (task
                                       (Thread/sleep duration) value)
                                s (System/nanoTime)
                                tsk (run-async work)
                                e (System/nanoTime)]
                            (and
                              (asynchronous duration s e)
                              (consistent work (r/success value))
                              (waitable work (r/success value))
                              (peerable work)
                              (timoutable work (/ duration 2))
                              (timoutable work (/ duration 2) "default")))))

;; V. Contextuality
(defn contextual [tsk]
  (=
    (run
      (do-tasks [a tsk
                 b (task (inc a))]
                (task (+ a b))))
    (run
      (then tsk
            (fn [a]
              (then (task (inc a))
                    (fn [b]
                      (task (+ a b)))))))))

(ct/defspec contextuality
            100
            (letfn [(f [bool a]
                      (if bool
                        (task a)
                        (task (throw exception))))]
              (prop/for-all [value gen/nat
                             bool gen/boolean]
                            (contextual ((partial f bool) value)))))


;; VI. Applicativity
(defn zips [to-do]
  (= (->> to-do
          (map #(task %))
          (apply zip)
          (run))
     (r/success to-do)))

(ct/defspec zipping
            100
            (prop/for-all [bool gen/boolean
                           to-do (-> gen/nat
                                     (gen/vector)
                                     (gen/not-empty))]
                          (zips to-do)))

(defn zipsWith [value]
  (= (run (zip-with (task value) str))
     (r/success [(str value) value])))

(ct/defspec zippingWith
            100
            (prop/for-all [value gen/nat]
                          (zipsWith value)))

(defn sequences [coll]
  (letfn [(collectify [x]
            (cond
              (set? coll) (set x)
              (list? coll) (seq x)
              :else (vec x)))]
    (= (->> coll
            (map #(task %))
            (collectify)
            (sequenced)
            (run))
       (r/success coll))))

(ct/defspec sequencing
            100
            (prop/for-all [s (gen/not-empty (gen/set gen/nat))
                           l (gen/not-empty (gen/list gen/nat))
                           v (gen/not-empty (gen/vector gen/nat))]
                          (and
                            (sequences s)
                            (sequences v)
                            (sequences l))))