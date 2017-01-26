(ns halfling.result-test
  (require [halfling.result :refer :all]
           [clojure.test :as tst]
           [clojure.test.check.clojure-test :as ct]
           [clojure.test.check :as c]
           [clojure.test.check.generators :as gen]
           [clojure.test.check.properties :as prop]))


(def exception (new RuntimeException "Well, this is bad."))

(defn gen-result [variants]
  "Generates `Result`s based on three variants:
     1. :successes - generates only successful results
     2. :failures - generates only failed results
     3. :mixed - generates both failed and successful results with equal probability"
  (case variants
    :successes (gen/fmap #(attempt %) gen/any)
    :failures (gen/fmap (fn [_] (attempt (throw exception))) gen/any)
    :mixed (gen/fmap (fn [i]
                       (attempt
                         (if (first (gen/sample gen/boolean 1))
                           i
                           (throw exception)))) gen/any)))


;; I. Associativity
(defn associative1 [val f g]
  (= (-> (attempt val)
         (fmap f)
         (fmap g))
     (-> (attempt val)
         (fmap (comp g f)))))

(defn associative2 [val f g]
  (= (bind (attempt val)
           (fn [a]
             (bind (attempt (f a))
                   (fn [b] (attempt (g b))))))
     (bind
       (bind (attempt val)
             (fn [a] (attempt (f a))))
       (fn [b] (attempt (g b))))))

(ct/defspec associativity
            100
            (letfn [(act [bool f a] (if bool (f a) (throw exception)))]
              (prop/for-all [bool gen/boolean
                             value gen/int]
                            (let [α (partial act bool inc)
                                  β (partial act bool dec)]
                              (associative1 value α β)
                              (associative2 value α β)))))

;; II. Fold idempotence
(defn idempotence [result v]
  (= (fold result (fn [_] v) (fn [_] v)) v))

(ct/defspec fold-idempotence
            100
            (prop/for-all [result (gen-result :mixed)
                           a gen/int]
                          (idempotence result a)))

;; III. Fold malformation
(defn malformation [result]
  (-> result
      (assoc :status :malformed)
      (fold (fn [_] ())
            (fn [_] ()))
      (failed?)))

(ct/defspec fold-malformation
            100
            (prop/for-all [result (gen-result :successes)]
                          (malformation result)))

;; IV. Get consistency
(defn consistency [failed-result]
  (failed? (get! failed-result)))

(ct/defspec get-consistency
            100
            (prop/for-all [result (gen-result :failures)]
                          (consistency result)))


(tst/run-tests)