(ns big-config.pluggable-test
  (:require
   [big-config.core :as core]
   [big-config.pluggable :as sut]
   [clojure.test :refer [deftest is testing]]))

(deftest handle-step-default-test
  (testing "default handle-step calls the wire-fn's function"
    (let [wire-fn (fn [step _]
                    (case step
                      ::start [core/ok ::end]
                      ::end [identity nil]))
          wf (sut/->workflow* {:first-step ::start
                               :wire-fn wire-fn})
          res (wf [] {})]
      (is (= 0 (:big-config/exit res))))))

(deftest handle-step-override-test
  (testing "overriding handle-step for a specific step"
    (let [wire-fn (fn [step _]
                    (case step
                      ::start [core/ok ::end]
                      ::end [identity nil]))
          wf (sut/->workflow* {:first-step ::start
                               :wire-fn wire-fn})]

      (defmethod sut/handle-step ::start
        [_f _step _step-fns opts]
        (merge opts (core/ok) {:overridden true}))

      (try
        (let [res (wf [] {})]
          (is (= 0 (:big-config/exit res)))
          (is (true? (:overridden res))))
        (finally
          (remove-method sut/handle-step ::start))))))

(deftest pluggable-workflow-threading-test
  (testing "values are correctly threaded through overridden steps"
    (let [wire-fn (fn [step _]
                    (case step
                      ::start [core/ok ::middle]
                      ::middle [core/ok ::end]
                      ::end [identity nil]))
          wf (sut/->workflow* {:first-step ::start
                               :wire-fn wire-fn})]

      (defmethod sut/handle-step ::middle
        [_f _step _step-fns opts]
        (merge opts (core/ok) {:middle-val 123}))

      (try
        (let [res (wf [] {:initial true})]
          (is (true? (:initial res)))
          (is (= 123 (:middle-val res)))
          (is (= 0 (:big-config/exit res))))
        (finally
          (remove-method sut/handle-step ::middle))))))

(deftest pluggable-workflow-step-fns-test
  (testing "step-fns are correctly invoked in pluggable workflow"
    (let [wire-fn (fn [step _]
                    (case step
                      ::start [core/ok ::end]
                      ::end [identity nil]))
          wf (sut/->workflow* {:first-step ::start
                               :wire-fn wire-fn})
          step-fn-called (atom false)
          my-step-fn (fn [f step opts]
                       (reset! step-fn-called true)
                       (f step opts))
          res (wf [my-step-fn] {})]
      (is (= 0 (:big-config/exit res)))
      (is (true? @step-fn-called)))))
