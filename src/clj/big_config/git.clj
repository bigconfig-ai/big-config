(ns big-config.git
  (:require
   [big-config :as bc]
   [big-config.core :refer [->workflow]]
   [big-config.run :refer [generic-cmd]]))

(defn get-revision [revision key opts]
  (let [revision (cond
                   (string? revision) revision
                   (keyword? revision) (revision opts)
                   :else (throw (ex-info "Revision is neither a string nor a keyword" {:revision revision
                                                                                       :key key
                                                                                       :opts opts})))
        cmd (format "git rev-parse %s" revision)]
    (generic-cmd :opts opts
                 :cmd cmd
                 :key key)))

(defn fetch-origin [opts]
  (generic-cmd :opts opts
               :cmd "git fetch origin"))

(defn upstream-name [key opts]
  (let [cmd "git rev-parse --abbrev-ref @{upstream}"]
    (generic-cmd :opts opts
                 :cmd cmd
                 :key key)))

(defn git-diff [opts]
  (generic-cmd :opts opts
               :cmd "git diff --quiet"))

(defn git-push [opts]
  (generic-cmd :opts opts
               :cmd "git push"))

(defn compare-revisions [opts]
  (let [{:keys [::prev-revision
                ::current-revision
                ::origin-revision]} opts
        res (or (= prev-revision origin-revision)
                (= current-revision origin-revision))]
    (merge opts (if res
                  {::bc/exit 0
                   ::bc/err nil}
                  {::bc/exit 1
                   ::bc/err "The local revisions don't match the remote revision"}))))

(def ^{:doc "Check if the local revision matches origin"
       :arglists '([]
                   [step-fns opts])}
  check (->workflow {:first-step ::git-diff
                     :wire-fn (fn [step _]
                                (case step
                                  ::git-diff [git-diff ::fetch-origin]
                                  ::fetch-origin [fetch-origin ::upstream-name]
                                  ::upstream-name [(partial upstream-name ::upstream-name) ::pre-revision]
                                  ::pre-revision [(partial get-revision "HEAD~1" ::prev-revision) ::current-revision]
                                  ::current-revision [(partial get-revision "HEAD" ::current-revision) ::origin-revision]
                                  ::origin-revision [(partial get-revision ::upstream-name ::origin-revision) ::compare-revisions]
                                  ::compare-revisions [compare-revisions ::end]
                                  ::end [identity]))}))

(comment
  (->> (check {})
       (into (sorted-map))))
