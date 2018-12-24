(ns halfling.task-test
  (:require [clojure.test :refer [is deftest]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.properties :refer [for-all]]
            [halfling.task :refer :all]))

(defn extract! [task] (get! (run task)))

(def error (Exception. "Error!"))

;; I. Associativity
;;  tsk :: Task a
;;  f :: a -> Task b
;;  g :: b -> Task c
;;  task then f then g == task then (λx . (f x) then g)

(defn associative [tsk f g]
  (is (= (-> tsk (then f) (then g) (extract!))
         (-> tsk (then #(then (f %) g)) (extract!)))))

(defspec associativity
         100
         (for-all [int  gen/int
                   bool gen/boolean]
            (let [f (if bool
                      (fn [_] (throw error))
                      #(task (inc %)))
                  g #(task (dec %))]
              (associative (task int) f g))))

;; II. Right identity
;; tsk :: Task a
;; task :: a -> Task a
;; tsk then task == tsk

(defn right-id [tsk]
  (is (= (extract! (then tsk #(task %)))
         (extract! tsk))))

(defspec right-identity
         100
         (for-all [int  gen/int
                   bool gen/boolean]
            (let [tsk (if bool
                      (task (throw error))
                      (task int))]
              (right-id tsk))))

;; III. Partial Left identity
;; val :: a
;; task :: a -> Task a
;; f :: a -> Task a
;; (task val) then f == f a

(defn left-id [val f]
  (is (= (-> (task val) (then f) (extract!))
         (extract! (f val)))))

(defspec left-identity
         100
         (for-all [int  gen/int
                   bool gen/boolean]
           (let [f (if bool
                     (constantly (task (throw error)))
                     #(task (inc %)))]
             (left-id int f))))

;; IV. Asynchronicity

(defn asynchronous [tsk max-ms]
  (let [s0 (System/nanoTime)
        _  (run-async tsk)
        s1 (System/nanoTime)
        delta (/ (- s1 s0) 1000000.0)]
    (is (< delta max-ms))))

(defn waitable [tsk max-ms]
  (let [s0 (System/nanoTime)
        _  (wait (run-async tsk))
        s1 (System/nanoTime)
        delta (/ (- s1 s0) 1000000.0)]
    (is (>= delta max-ms))))

(defn timeoutable [tsk timeout else]
  (is (= (get! (wait (run-async tsk) timeout else)) else)))

(defn derefable [tsk expected]
  (is (= @(run tsk) expected)))

(defn consistent [tsk expected]
  (let [result (extract! (wait (run tsk)))]
    (is (= result expected))))

(defspec asynchronicity
         100
         (for-all [int gen/int]
            (let [duration 20
                  tsk (task (Thread/sleep duration) (inc int))]
              (asynchronous tsk duration)
              (waitable tsk duration)
              (timeoutable tsk 10 -1)
              (derefable tsk (inc int))
              (consistent tsk (inc int)))))

;; V. Comprehension

(defn comprehend [task1 task2 task3 f]
  (is (= (extract! (do-tasks [a task1
                              b task2
                              c task3]
                             (f a b c)))
         (extract! (then task1
                         (fn [a]
                           (then task2
                                 (fn [b]
                                   (then task3
                                         (fn [c] (f a b c)))))))))))

(defn comprehend-recovered [task1 task2 task3 f]
  (is (= (extract! (do-tasks [a task1
                              b task2
                              c task3
                              :recover (fn [errs] (mapv #(.getMessage %) errs))]
                             (f a b c)))
         (extract! (recover
                     (then task1
                           (fn [a]
                             (then task2
                                   (fn [b]
                                     (then task3
                                           (fn [c] (f a b c)))))))
                     (fn [errs] (mapv #(.getMessage %) errs)))))))

(defn comprehend-directly-recovered [task1 task2 task3 f]
  (is (= (extract! (do-tasks [a task1
                              b task2
                              c task3
                              :recover-as "Recovery"]
                             (f a b c)))
         (extract! (recover-as
                     (then task1
                           (fn [a]
                             (then task2
                                   (fn [b]
                                     (then task3
                                           (fn [c] (f a b c)))))))
                     "Recovery")))))

(defspec comprehension
         100
         (for-all [a gen/int
                   b gen/int
                   c gen/int
                   bool gen/boolean]
         (let [args (if bool
                      [(task a) (task (throw error)) (task c) +]
                      [(task a) (task b) (task c) +])]
           (apply comprehend args)
           (apply comprehend-recovered args)
           (apply comprehend-directly-recovered args))))

;; VI. Applicativity

(defn combination [task1 task2 f]
  (is (= (extract! (mapply f task1 task2))
         (extract! (task (f (extract! task1) (extract! task2)))))))

(defn propagation [tsk f]
  (is (broken? (run (mapply f tsk (task (throw error)))))))

(defn zipping [task1 task2]
  (is (= (extract! (zip task1 task2))
         [(extract! task1) (extract! task2)])))

(defn sequencing [coll-tasks to-coll]
  (is (= (extract! (sequenced coll-tasks))
         (to-coll (mapv extract! coll-tasks)))))

(defspec applicativity
         100
         (for-all [a gen/int
                   b gen/int
                   bool gen/boolean]
           (let [task1 (task a)
                 task2 (task b)]
             (combination task1 task2 +)
             (propagation task1 inc)
             (zipping task1 task2)
             (sequencing [task1 task2] vec)
             (sequencing #{task1 task2} set)
             (sequencing (list task1 task2) list))))

;; VII. Destructivity

(defmacro coflatten [val n]
  (reduce (fn [a# _] `(task ~a#)) val (range 0 n)))

(defmacro destructive [val n]
  `(let [tsk# (coflatten ~val ~n)]
     (is (= (extract! tsk#) ~val))))

(deftest destructivity
  (destructive "value" 100))

;; VIII. Recoverability

(defn recovered [tsk expected]
  (is (= (-> tsk
             (then (fn [_] (throw error)))
             (recover (constantly expected))
             (extract!))
         expected)))

(defn recovered-as [tsk expected]
  (is (= (-> tsk
             (then (fn [_] (throw error)))
             (recover-as expected)
             (extract!))
         expected)))

(defn recovered-error [tsk]
  (let [msg "Failed!"]
    (is (= (-> tsk
               (then-do (failure msg))
               (recover (fn [errs] (mapv #(.getMessage %) errs)))
               (extract!)) [msg]))))

(defn recovered-parallel-error [tsk1 tsk2 tsk3]
  (let [msg1 "Failed-1!"
        msg2 "Failed-2!"
        tsk-e (-> (zip tsk1
                       (-> tsk2 (then-do (failure msg1)))
                       (-> tsk3 (then-do (failure msg2))))
                  (recover (fn [errs] (mapv #(.getMessage %) errs))))]
    (is (= (extract! tsk-e) [msg1 msg2]))))

(defspec recoverability
         100
         (for-all [int gen/int
                   recover gen/string]
                  (recovered (task int) recover)
                  (recovered-as (task int) recover)
                  (recovered-error (task int))
                  (recovered-parallel-error (task int) (task int) (task int))))

;; IX. Alternation

(defn alternate [tsk else]
  (is (= (-> tsk
             (then (fn [_] (throw error)))
             (run)
             (get-or-else else))
         else)))


(defspec alternation
         100
         (for-all [int gen/int
                   alt gen/string]
           (alternate (task int) alt)))

;; X. Execution impartiality under composition

(defn impartial-exec [val vals]
  (is (= (-> (task val)
             (then (fn [x] (->> vals (mapv #(task (+ x %))) (sequenced))))
             (extract!))
         (mapv #(+ val %) vals))))

(defspec impartiality
         100
         (for-all [int gen/int
                   other-ints (gen/vector gen/int)]
                  (impartial-exec int other-ints)))