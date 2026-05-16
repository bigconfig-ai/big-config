(ns big-config.pluggable
  "A pluggable workflow allows extending or overriding step behavior using a multimethod."
  (:require
   [big-config.core :as core]
   [big-config.utils :refer [debug]]))

(defmulti handle-step
  "A multimethod that dispatches on the `step`. It is used by `->workflow*` to
  allow pluggable step handling. Methods receive `[f step step-fns opts]`, where
  `f` is the original step implementation from the workflow's `:wire-fn`.

  The `:default` implementation calls the original function provided by the
  `:wire-fn` in `->workflow*`."
  (fn [_f step _step-fns _opts] step))

(defmethod handle-step :default [f _step _step-fns opts]
  (f opts))

(defn ->workflow*
  "Similar to `core/->workflow`, but wraps each step execution in the `handle-step`
  multimethod, allowing for external extension or overriding of specific steps."
  [{:keys [first-step last-step wire-fn next-fn]}]
  (fn [step-fns opts]
    (let [new-wire-fn (fn [step step-fns]
                        (let [[f next-step] (wire-fn step step-fns)]
                          [(fn [opts]
                             (handle-step f step step-fns opts))
                           next-step]))
          wf (core/->workflow {:first-step first-step
                               :last-step last-step
                               :wire-fn new-wire-fn
                               :next-fn next-fn})]
      (wf step-fns opts))))

(comment
  (debug tap-values
    (defmethod handle-step ::start
      [f step step-fns opts]
      (merge opts (core/ok) {step :custom
                             :f (f opts)
                             :step-fns step-fns}))
    (let [wf (->workflow* {:first-step ::start
                           :last-step ::end
                           :wire-fn (fn [step _]
                                      (case step
                                        ::start [#(merge % (core/ok) {step :default}) ::second]
                                        ::second [#(merge % (core/ok) {step :default}) ::end]
                                        ::end [identity]))})]
      (wf [] {})))
  (-> tap-values))
