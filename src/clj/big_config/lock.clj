(ns big-config.lock
  "
  BigConfig Lock manages resource locking and unlocking while applying changes.
  It provides functionality similar to **Atlantis for Terraform**, but operates
  entirely **client-side**. This makes operations interactive again by removing
  the mandatory Pull Request step, and it is versatile enough to be used with
  other tools like **Ansible**.

  Under the hood, BigConfig leverages **Git repository tags** to acquire and
  release locks.

  ### Workflow Context
  * **`opts` map**: The primary BigConfig configuration map threaded through the
  workflow steps.

  ### Options for `opts`
  To manage locks, the following keys are required within the `opts` map:
  * **`:big-config.lock/owner`** (Required): A string used to distinguish
  between different users or CI environments.
  * **`:big-config.lock/lock-keys`** (Required): A list of qualified keys used
  to generate a unique `lock-id`.

  ### Lock Logic
  * **Re-acquisition:** Re-acquiring a `lock-id` with the *same* `owner` will
  succeed (idempotent).
  * **Conflict:** Attempting to acquire a `lock-id` currently held by a
  *different* `owner` will fail.

  ## `big-config.unlock/unlock-any`
  This workflow is used to force-release a `lock-id`, regardless of which owner
  currently holds it.
  "
  (:require
   [big-config :as bc]
   [big-config.core :as utils :refer [->workflow choice]]
   [big-config.run :refer [generic-cmd]]
   [big-config.utils :refer [sort-nested-map]]
   [clojure.edn :as edn]
   [clojure.string :as str]))

(defn ^:no-doc generate-lock-id [opts]
  (let [{:keys [::lock-keys ::owner]} opts
        lock-details (select-keys opts lock-keys)
        lock-name (-> lock-details
                      sort-nested-map
                      hash
                      (->> (format "%X"))
                      (as-> $ (str "LOCK-" $)))]
    (-> opts
        (assoc ::lock-details lock-details)
        (update ::lock-details assoc ::owner owner)
        (merge {::lock-name lock-name
                ::bc/exit 0
                ::bc/err nil}))))

(defn ^:no-doc delete-tag [opts]
  (let [{:keys [::lock-name]} opts]
    (generic-cmd :opts opts
                 :cmd ["git" "tag" "-d" lock-name])))

(defn- create-tag [opts]
  (let [{:keys [::lock-name ::lock-details]} opts]
    (generic-cmd :opts opts
                 :shell-opts {:in (str ">>>" (pr-str lock-details))}
                 :cmd ["git" "tag" "-a" lock-name "-F" "-"])))

(defn- push-tag [opts]
  (let [{:keys [::lock-name]} opts]
    (generic-cmd :opts opts
                 :cmd ["git" "push" "origin" lock-name])))

(defn ^:no-doc delete-remote-tag [opts]
  (let [{:keys [::lock-name]} opts]
    (generic-cmd :opts opts
                 :cmd ["git" "push" "--delete" "origin" lock-name])))

(defn- get-remote-tag [opts]
  (let [{:keys [::lock-name]} opts]
    (generic-cmd :opts opts
                 :cmd ["git" "fetch" "origin" "tag" lock-name "--no-tags"])))

(defn- read-tag [opts]
  (let [{:keys [::lock-name]} opts]
    (generic-cmd :opts opts
                 :cmd ["git" "cat-file" "-p" lock-name]
                 :key ::tag-content)))

(defn- parse-tag-content [tag-content]
  (-> tag-content
      str/split-lines
      (->> (filter #(str/starts-with? % ">>>")))
      first
      (str/replace-first ">>>" "")
      edn/read-string))

(defn- check-tag [opts]
  (let [{:keys [::tag-content]} opts
        ownership (every? (fn [[k v]]
                            (= (get opts k) v))
                          (parse-tag-content tag-content))]
    (merge opts (if ownership
                  {::bc/exit 0
                   ::bc/err nil}
                  {::bc/exit 1
                   ::bc/err "Different owner"}))))

(defn ^:no-doc check-remote-tag [opts]
  (let [{:keys [::lock-name]} opts
        {:keys [::bc/exit ::bc/err] :as opts} (generic-cmd :opts opts
                                                           :cmd ["git" "ls-remote" "--exit-code" "origin" (str "refs/tags/" lock-name)])]
    (merge opts (if (= exit 2)
                  {::bc/exit 0
                   ::bc/err nil}
                  {::bc/exit 1
                   ::bc/err err}))))

(def
  ^{:doc
    "This workflow is used to acquire the `lock-id`. See the namespace `big-config.lock`."
    :arglists '([] [step-fns opts])}
  lock (->workflow {:first-step ::generate-lock-id
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

(comment
  (->> (lock {::owner "alberto"})
       (into (sorted-map))))
