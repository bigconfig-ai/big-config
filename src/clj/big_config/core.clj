(ns big-config.core
  "A workflow performs a series of steps, threading an `opts` map through each one."
  (:require
   [big-config :as bc]
   [big-config.utils :refer [->fn]]))

(defn ok
  "Return opts with success. Each step must return `opts` with a
  `:big-config/exit` key value greater than or equal to 0, consistent with Unix
  shell exit codes.

  ```clojure
  (defn step-a [opts]
    ...
    (merge opts (ok) {::some-new-key value}))

  (defn step-b [opts]
    ...
    (ok opts))
  ```"
  ([]
   {::bc/exit 0
    ::bc/err nil})
  ([opts]
   (merge opts {::bc/exit 0
                ::bc/err nil})))

(defn choice
  "To be used in the `next-fn`. See `->workflow`"
  [{:keys [on-success
           on-failure
           opts]}]
  (let [exit (::bc/exit opts)]
    (if (= exit 0)
      [on-success opts]
      [on-failure opts])))

(defn- compose [step-fns f]
  (reduce (fn [f-acc f-next]
            (partial f-next f-acc)) (fn [_ opts] (f opts)) step-fns))

(defn- resolve-step-fns [step-fns]
  (-> (map ->fn step-fns)
      reverse))

(defn- try-f [f step opts]
  (try (f step opts)
       (catch Exception e
         (merge opts
                (ex-data e)
                {::bc/err (ex-message e)
                 ::bc/exit 1
                 ::bc/stack-trace (apply str (interpose "\n" (map str (.getStackTrace e))))}))))

(defn- resolve-next-fn [next-fn last-step]
  (if (nil? next-fn)
    (fn [_ next-step opts]
      (if next-step
        (choice {:on-success next-step
                 :on-failure last-step
                 :opts opts})
        [nil opts]))
    next-fn))

(defn ->workflow
  "Creates a workflow based on the following `wf-opts` map:
   * **`:first-step`** (Required): The qualified keyword representing the
     initial step in the workflow. Defaults typically to `::start`.
   * **`:last-step`** (Optional): The qualified keyword for the final step. If
     omitted, it defaults to `::end`, sharing the same namespace as `:first-step`.
   * **`:wire-fn`** (Required): A function that accepts two arguments—the
     current step and the `step-fns`. `step-fns` is used to invoke subworkflow
     using `partial`. It must return `[step-fn next-step]`.
   * **`:next-fn`** (Optional): A function used to handle complex or conditional
     branching logic when the default transitions provided by the `wire-fn` are
     insufficient.

  Example of a workflow subworkflow:

  ```clojure
  (let [wf (->workflow {:first-step ::start
                        :wire-fn (fn [step step-fns]
                                   (case step
                                     ::start [#(ok %) ::sub-wf]
                                     ::sub-wf [(partial sub-wf step-fns) ::end]
                                     ::end [identity]))})]
    (wf) ; => [::start ::end]
    (wf [my-step-fn] {::my :value}))
  ```

  Example of a workflow with `next-fn`:

  ```clojure
  (def lock (->workflow {:first-step ::generate-lock-id
                         :wire-fn (fn [step _]
                                    (case step
                                      ::generate-lock-id [generate-lock-id ::delete-tag]
                                      ::delete-tag [delete-tag ::create-tag]
                                      ::create-tag [create-tag ::push-tag]
                                      ::push-tag [push-tag ::get-remote-tag]
                                      ::get-remote-tag [(comp get-remote-tag delete-tag) ::read-tag]
                                      ::read-tag [read-tag ::check-tag]
                                      ::check-tag [check-tag ::end]
                                      ::end [identity]))
                         :next-fn (fn [step next-step opts]
                                    (case step
                                      ::end [nil opts]
                                      ::push-tag (choice {:on-success ::end
                                                          :on-failure next-step
                                                          :opts opts})
                                      ::delete-tag [next-step opts]
                                      (choice {:on-success next-step
                                               :on-failure ::end
                                               :opts opts})))}))
  ```"
  {:arglists '([wf-opts])}
  [{:keys [first-step
           last-step
           wire-fn
           next-fn]}]
  (let [last-step (or last-step
                      (keyword (namespace first-step) "end"))]
    (fn workflow
      ([]
       [first-step last-step])
      ([step-fns opts]
       (when (nil? opts)
         (throw (IllegalArgumentException. "opts should never be nil")))
       (let [step-fns (resolve-step-fns step-fns)]
         (loop [step first-step
                opts opts]
           (let [[f next-step] (wire-fn step step-fns)
                 f (compose step-fns f)
                 {:keys [::bc/exit] :as opts} (try-f f step opts)
                 _ (when (nil? opts)
                     (throw (ex-info "opts must never be nil" {:step step})))
                 _ (when-not (nat-int? exit)
                     (throw (ex-info ":big-config/exit must be a natural number" opts)))
                 next-fn (resolve-next-fn next-fn last-step)
                 [next-step next-opts] (next-fn step next-step opts)]
             (if next-step
               (recur next-step next-opts)
               next-opts))))))))

(comment
  (let [wf (->workflow {:first-step ::start
                        :step-fns ["big-config.step-fns/bling-step-fn"]
                        :wire-fn (fn [step _]
                                   (case step
                                     ::start [(fn [opts]
                                                (println "foo")
                                                (merge opts {::bc/exit 1
                                                             ::bc/err "Failure"})) ::end]
                                     ::end [identity]))})]
    (wf [] {})))

(defn ->step-fn
  "A step function is middleware that accepts three arguments: the wrapped step
  function, the step keyword, and the `opts` map. To maintain the execution
  chain, it must return the updated (or original) `opts` map.

  For convenience, `->step-fn` allows you to provide two simpler functions. These
  functions take only two arguments—the step and the `opts` map—and do not need to
  return the map. These are typically used for side effects, such as logging to
  stdout or terminating the process with a specific exit code.

  These side-effect functions are executed immediately before and after a workflow
  step and receive the current execution context."
  [{:keys [before-f after-f]}]
  (cond
    (every? nil? [before-f after-f]) (throw (IllegalArgumentException. "At least one f needs to be provided"))
    (= [nil :same] [before-f after-f]) (throw (IllegalArgumentException. ":before-f must be a f with :after-f :same")))
  (fn [f step opts]
    (when before-f
      (before-f step opts))
    (let [opts (f step opts)
          after-f (case after-f
                    nil (fn [_ _])
                    :same before-f
                    after-f)]
      (after-f step opts)
      opts)))
