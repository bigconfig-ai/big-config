(ns big-config.workflow
  "
  The goal of the BigConfig Workflow is to enable independent development of
  automation units while providing a structured way to compose them into complex
  pipelines.

  ### Workflow Types
  * **`tool-workflow`**: The fundamental unit. It renders templates and
  executes CLI tools (e.g., Terraform/OpenTofu, Ansible).
  * **`comp-workflow`**: A high-level orchestrator that sequences multiple
  `tool-workflows` to create a unified lifecycle (e.g., `create`, `delete`).
  * **`system-workflow`**: A lifecycle management engine (see `big-config.system`)
  that coordinates starting and stopping system components as an alternative to Integrant.

  ### Usage Syntax
  BigConfig Workflow can be used as a library or as a CLI using Babashka.
  The engine is powered by **[pluggable steps](../pluggable)**, allowing for
  seamless extension via multimethods.

  ```shell
  # Execute a tool workflow directly
  bb <tool-workflow> <step|cmd>+ [-- <raw-command>]

  # Execute a composite workflow
  bb <comp-workflow> <step>+
  ```

  ### Examples
  ```shell
  # Individual development/testing
  bb tool-wf-a render tofu:init -- tofu apply -auto-approve
  bb tool-wf-b render ansible-playbook:main.yml

  # Orchestrated execution
  bb comp-wf-c create
  ```

  In this example, `comp-wf-c` composes `tool-wf-a` and `tool-wf-b`.
  Development and debugging happen within the individual tool workflows, while
  the composite workflow manages the sequence.

  ### Available Steps
  #### `tool-workflow`
  | Step       | Description                                                     |
  | :----      | :----                                                           |
  | **render**     | Generate the configuration files.                               |
  | **git-check**  | Verifies the working directory is clean and synced with origin. |
  | **git-push**   | Pushes local commits to the remote repository.                  |
  | **lock**       | Acquires an execution lock.                                     |
  | **unlock-any** | Force-releases the lock, regardless of the current owner.       |
  | **exec**       | Executes commands provided.                                     |
  #### `comp-workflow`
  | Step       | Description                                                     |
  | :----      | :----                                                           |
  | **create**     | Invokes one or more `tool-workflows` to create a resource.        |
  | **delete**     | Invokes one or more `tool-workflows` to delete a resource.        |
  | **git-check**  | Verifies the working directory is clean and synced with origin. |
  | **git-push**   | Pushes local commits to the remote repository.                  |
  | **lock**       | Acquires an execution lock.                                     |
  | **unlock-any** | Force-releases the lock, regardless of the current owner.       |

  ### Extending Workflows with Custom Steps

  The workflow engine is powered by **[pluggable steps](../pluggable)**.
  You can extend or override any step by defining a method for the
  `big-config.pluggable/handle-step` multimethod. This allows you to add custom
  logic or completely change the behavior of built-in steps like `render` or `lock`.

  ```clojure
  (require '[big-config.pluggable :as pluggable])
  (require '[big-config.core :as core])

  (defmethod pluggable/handle-step ::my-custom-step
    [f step step-fns opts]
    ;; Your custom logic here
    (println \"Executing custom step!\" step (count step-fns))
    (f opts))

  ;; Usage in a workflow
  (run-steps step-fns {::workflow/steps [:my-custom-step]})
  ```

  When using `parse-args` (e.g., in Babashka tasks), you might need to register
  new step names so they are recognized as steps rather than shell commands.
  This is done by rebinding the dynamic var `*parse-args-steps*`. Note that
  steps are keywords, not strings.

  ```clojure
  (binding [workflow/*parse-args-steps* (conj workflow/*parse-args-steps* :my-custom-step)]
    (workflow/parse-args [\"my-custom-step\" \"render\"]))
  ```

  ### Core Logic & Functions
  * **`run-steps`**: The engine for dynamic workflow execution. It's a pluggable workflow.
  * **`->workflow*`**: Creates a workflow of workflows.
  * **`prepare`**: Shared logic for rendering templates and initializing
  execution environments.
  * **`parse-args`**: Utility function to normalize string or vector-based
  arguments.
  * **`select-globals`**: Utility function to copy the global options across
  workflows. Propagates `::bc/env`, `::run/shell-opts`, `::render/module`,
  `::render/profile`, `::prefix`, `::object-prefix`, and `::globals`.
  * **`merge-params`**: Utility function to merge the package params with the
  tool params. `tools` is a sequence of qualified keywords.
  * **`read-bc-pars`**: Utility function to override the package params with
  environment variable.

  ### Options for `wf*-opts`.
  * `:first-step` (required): First step of the workflow.
  * `:last-step` (optional): Optional last step (defaults to `<namespace-of-first-step>/end`).
  * `:pipeline` (required): A vector containing the repetition of:
    * The qualified keyword of the tool workflow (e.g., `::tools/tofu`).
    * A vector containing:
      * Arguments for the tool workflow (e.g., `[\"render ...\"]`).
      * An optional `opts-fn` to adapt/merge outputs from previous steps into the current params.
  > **Note:** `->workflow*` calls `new-prefix` on the globals derived from
  > `opts`, which derives a **deterministic** discriminator from `:first-step`
  > and stamps it into `::prefix` and `::object-prefix`. This is intentional: a
  > given workflow always resolves to the **same** directory and object path so
  > that (a) generated OpenTofu/Ansible can be inspected and iterated on during
  > development, (b) a **local OpenTofu backend** keeps its `terraform.tfstate`
  > across runs, and (c) paired `create`/`delete` workflows (which share
  > `:first-step`) operate on the same state.
  >
  > Concurrency is an **explicit non-goal** of `new-prefix`. Running the same
  > workflow concurrently against the same prefix is unsupported by design;
  > mutual exclusion is the job of the `lock` step (`big-config.lock`). For
  > isolated parallel runs, override `::prefix`/`::object-prefix` explicitly.

  ### Options for `opts` and step `render`
  * `::name` (required): The unique identifier for the workflow instance.
  * `::path-fn` (optional): Logic for resolving file paths.
  * `::object-fn` (optional): Logic for resolving the target object name (defaults to `<object-prefix>/<name-as-dashed-string>`).
  * `::prefix` (optional): The base directory prefix (defaults to `.dist`).
  * `::object-prefix` (optional): The base object prefix used to compute `:target-object` in template params (defaults to `tofu`).
  * `::params` (optional): The input data for the workflow. The conventions is
  to use unqualified keywords with prefixes like: `:oci-config-file-profile` or
  `:hcloud-server-type`.

  ### Options for `prepare-opts` and step `render`
  * `::name` (required): The unique identifier for the workflow instance.

  ### Options for `prepare-overrides` and step `render`
  * `::path-fn` (optional): Logic for resolving file paths.
  * `::object-fn` (optional): Logic for resolving the target object name.
  * `::prefix` (optional): Override for the base directory prefix.
  * `::object-prefix` (optional): Override for the base object prefix.
  * `::params` (optional): The input data for the workflow.

  > **Note:** `prepare` injects `:target-dir` and `:target-object` into every
  > template map so templates can reference them without repeating path logic.

  ### Options for `opts` and step `create` or `delete`
  * `::create-fn` (required): The workflow to create the resource.
  * `::delete-fn` (required): The workflow to delete the resource.
  * `::create-opts` (optional): The override opts for `create`.
  * `::delete-opts` (optional): The override opts for `delete`.

  ### Naming Conventions
  To distinguish between the library core and the Babashka CLI implementation:

  * **`[workflow name]`**: The library-level function. Requires `step-fns` and `opts`.
  * **`[workflow name]*`**: The Babashka-ready task. Accepts `args` and optional `opts`.

  ```clojure
  (ns wf)

  (defn tofu
    [step-fns opts]
    (let [opts (prepare {::name ::tofu
                         ::render/templates [{:template \"tofu\"
                                              :overwrite true
                                              :transform [[\"tofu\"
                                                           :raw]]}]}
                        opts)]
      (run-steps step-fns opts)))
  ```

  ```clojure
  (ns wf)

  (defn tofu*
    [args & [opts]]
    (let [opts (merge (parse-args args)
                      opts)]
      (tofu [] opts)))
  ```

  ```clojure
  ; bb.edn
  {:deps {group/artifact {:local/root \".\"}}
   :tasks
   {:requires ([wf :as wf])
    tofu {:doc \"bb tofu render tofu:init tofu:apply:-auto-approve\"
          :task (wf/tofu* *command-line-args*)}
    ansible {:doc \"bb ansible render -- ansible-playbook main.yml\"
             :task (wf/ansible* *command-line-args*)}
    resource {:doc \"bb resource create\"
              :task (wf/resource* *command-line-args*)}}}
  ```

  ### Decoupled Data Sharing
  Standard Terraform/HCL patterns often lead to tight coupling, where downstream
  resources must know the exact structure of upstream providers (e.g., the
  specific IP output format of AWS vs. Hetzner).

  **BigConfig Workflow solves this through Parameter Adaptation:**

  1. **Isolation**: `tool-wf-b` (Ansible) never talks directly to `tool-wf-a`
  (Tofu).
  2. **Orchestration**: The `comp-workflow` acts as a glue layer. It uses `path`
  to discover outputs from the previous workflows (e.g., via `tofu output
  --json`) and maps them to the `::params` required by the next.
  3. **Interchangeability**: You can swap a Hetzner workflow for an AWS workflow
  without modifying the downstream Ansible code. Only the mapping logic in the
  `comp-workflow` needs to be updated.

  > **Note:** Resource naming and state booking are outside the scope of BigConfig Workflow.

  ### Composite workflow
  This example demonstrates a **composite workflow** orchestrating one or more
  **tool workflows**. To maintain modularity, tool workflows must be isolated
  using distinct `opts` maps. They may operate within the same directory—for
  instance, when pairing `tofu apply` and `tofu destroy`—or function in separate
  environments.

  * **`resource-create`**: The composite workflow to create the resource.
  * **`resource-delete`**: The composite workflow to delete the resource.
  * **`resource`**: The composite workflow to expose the steps interface.

  > **Note:** `resource-create` and `resource-delete` share the same
  `:first-step` to ensure they utilize the same directory when Tofu is using
  local state.

  ```clojure
  (defn opts-fn
    [opts]
    (let [ip (-> (p/shell {:dir (workflow/path opts ::tool/tofu)
                           :out :string} \"tofu show --json\")
                 :out
                 (json/parse-string keyword)
                 (->> (s/select-one [:values :root_module :resources s/FIRST :values :ipv4_address])))]
      (merge-with merge opts {::workflow/params {:ip ip}})))
  ```

  ```clojure
  (def resource-create
    (workflow/->workflow* {:first-step ::start-create-or-delete
                           :last-step :end-create-or-delete
                           :pipeline [::tool/tofu [\"render tofu:init tofu:apply:-auto-approve\"]
                                      ::tool/ansible [\"render ansible-playbook:main.yml\" opts-fn]
                                      ::tool/ansible-local [\"render ansible-playbook:main.yml\" opts-fn]]}))
  ```

  ```clojure
  (def resource-delete
    (workflow/->workflow* {:first-step ::start-delete-or-delete
                           :last-step ::end-delete-or-delete
                           :pipeline [::tool/tofu [\"render tofu:destroy:-auto-approve\"]]}))
  ```

  ```clojure
  (defn resource
    [step-fns opts]
    (let [opts (merge {::workflow/create-fn resource-create
                       ::workflow/delete-fn resource-delete}
                      opts)
          wf (core/->workflow {:first-step ::start
                               :wire-fn (fn [step step-fns]
                                          (case step
                                            ::start [(partial workflow/run-steps step-fns) ::end]
                                            ::end [identity]))})]
      (wf step-fns opts)))
  ```
  "
  (:require
   [big-config :as bc]
   [big-config.core :as core]
   [big-config.git :as git]
   [big-config.lock :as lock]
   [big-config.pluggable :as pluggable]
   [big-config.render :as render]
   [big-config.run :as run]
   [big-config.unlock :as unlock]
   [big-config.utils :refer [assert-args-present debug keyword->name
                             keyword->path]]
   [bling.core :refer [bling]]
   [clojure.string :as str]
   [com.rpl.specter :as s]
   [selmer.parser :as parser]
   [selmer.util :as util]))

(def ^{:doc "Print all steps of the workflow. See the namespace
  `big-config.workflow`."
       :arglists '([step opts])}
  print-step-fn
  (core/->step-fn {:before-f (fn [step {:keys [::bc/exit] :as opts}]
                               (binding [util/*escape-variables* false]
                                 (let [[lock-start-step] (lock/lock)
                                       [unlock-start-step] (unlock/unlock-any)
                                       [check-start-step] (git/check)
                                       [render-start-step] (render/templates)
                                       [prefix color] (if (and exit
                                                               (not= exit 0))
                                                        ["\uf05c" :red.bold]
                                                        ["\ueabc" :green.bold])
                                       msg (cond
                                             (= step lock-start-step) (parser/render "Lock (owner {{ big-config..lock/owner }})" opts)
                                             (= step unlock-start-step) "Unlock any"
                                             (= step check-start-step) "Checking if the working directory is clean"
                                             (= step render-start-step) (parser/render "Rendering workflow: {{ big-config..workflow/name }}" opts)
                                             (= step ::run/run-cmd) (parser/render "Running:\n> {{ big-config..run/cmds | first}}" opts)
                                             :else nil)]
                                   (when msg
                                     (binding [*out* *err*]
                                       (println (bling [color (parser/render (str "{{ prefix }} " msg) {:prefix prefix})])))))))
                   :after-f (fn [step {:keys [::bc/exit] :as opts}]
                              (let [[_ check-end-step] (git/check)
                                    prefix "\uf05c"
                                    msg (cond
                                          (= step check-end-step) "Working directory is NOT clean"
                                          (= step ::run/run-cmd) (parser/render "Failed running:\n> {{ big-config..run/cmds | first }}" opts)
                                          :else nil)]
                                (when (and msg
                                           (> exit 0))
                                  (binding [*out* *err*]
                                    (println (bling [:red.bold (parser/render (str "{{ prefix }} " msg) {:prefix prefix})]))))))}))

(comment (print-step-fn))

(defn- resolve-fn [kw opts]
  (let [f (get opts kw)]
    (cond
      (nil? f) (throw (ex-info (format "`%s` not defined" kw) opts))
      (fn? f) f
      (symbol? f) (requiring-resolve f)
      :else (throw (ex-info (format "Value for `%s` is neither a function nor a symbol" kw) opts)))))

(defn select-globals [{:keys [globals] :as opts}]
  (->> (or globals [::bc/env
                    ::run/shell-opts
                    ::render/module
                    ::render/profile
                    ::prefix
                    ::object-prefix
                    ::globals])
       (select-keys opts)))

(defn run-steps
  "A *composition layer* — a dynamic \"workflow of workflows\", not a workflow
  step. Takes a list of `::steps` plus `::create-fn` / `::delete-fn`.

  Most steps (`::lock`, `::git-check`, `::render`, `::exec`, `::git-push`,
  `::unlock-any`) are ordinary in-workflow steps and obey the usual pure
  `opts -> opts` threading contract. `::create` and `::delete` are *entire
  subworkflows*, resolved via `::create-fn` / `::delete-fn`.

  Architectural decision — subworkflow isolation (by design):

  * The pure-step / single-threaded-`opts` contract is scoped to the steps
    *within one workflow*. At this composition layer the invariant is instead
    that each subworkflow runs on an isolated, purpose-built `opts`:
    `::create` / `::delete` receive `create-opts` / `delete-opts`
    (caller-supplied `::create-opts` / `::delete-opts` merged with the shared
    globals), never the parent's running `opts`.
  * Each subworkflow's terminal `opts` is accumulated under its step key as a
    vector, so repeated `::create` / `::delete` invocations are kept
    side-by-side as history; only `::bc/exit` / `::bc/err` propagate upward to
    drive the parent's branching and short-circuit.

  The closed-over atom is therefore the deliberate accumulator + isolation
  barrier that implements these semantics, not a purity leak. Folding the step
  queue and results into one threaded `opts` would break subworkflow isolation
  and discard per-invocation result history — it is not a behavior-preserving
  change. See the namespace `big-config.workflow`."
  {:arglists '([step-fns opts])}
  [step-fns {:keys [::steps ::create-opts ::delete-opts] :as opts}]
  (let [globals-opts (select-globals opts)
        create-opts (merge (or create-opts {}) globals-opts)
        delete-opts (merge (or delete-opts {}) globals-opts)
        opts* (atom opts)
        steps* (atom (map (fn [step]
                            (if (namespace step)
                              step
                              (keyword "big-config.workflow" (name step))))
                          steps))
        wf (pluggable/->workflow* {:first-step ::start
                                   :last-step ::end
                                   :wire-fn (fn [step step-fns]
                                              (case step
                                                ::start [core/ok]
                                                ::lock [(partial lock/lock step-fns)]
                                                ::git-check [(partial git/check step-fns)]
                                                ::render [(partial render/templates step-fns)]
                                                ::create [(fn [create-opts] ((resolve-fn ::create-fn opts) step-fns create-opts))]
                                                ::delete [(fn [delete-opts] ((resolve-fn ::delete-fn opts) step-fns delete-opts))]
                                                ::exec [(partial run/run-cmds step-fns)]
                                                ::git-push [(partial git/git-push)]
                                                ::unlock-any [(partial unlock/unlock-any step-fns)]
                                                [identity]))
                                   :next-fn (fn [step _ {:keys [::bc/exit] :as opts}]
                                              (if (#{::create ::delete} step)
                                                (do
                                                  (swap! opts* merge (select-keys opts [::bc/exit ::bc/err]))
                                                  (swap! opts* update step (fnil conj []) opts))
                                                (reset! opts* opts))
                                              (cond
                                                (= step ::end)
                                                [nil @opts*]

                                                (> exit 0)
                                                [::end @opts*]

                                                :else
                                                (let [next-step (first @steps*)
                                                      _ (swap! steps* rest)]
                                                  (if next-step
                                                    [next-step (case next-step
                                                                 ::create create-opts
                                                                 ::delete delete-opts
                                                                 @opts*)]
                                                    [::end @opts*]))))})]
    (wf step-fns @opts*)))

(comment
  (debug tap-values
    (run-steps [(fn [f step opts]
                  (tap> [step opts])
                  (f step opts))]
               {::steps [:create :delete :create :delete]
                ::create-fn (fn [_ opts] (core/ok opts))
                ::delete-fn (fn [_ opts] (core/ok opts))
                ::create-opts {:a 1}
                ::delete-opts {:a 2}
                ::bc/env :repl
                ::lock/owner "alberto"}))
  (-> tap-values))

(comment
  (debug tap-values
    (defmethod pluggable/handle-step ::foo [_f step step-fns opts]
      (tap> [step step-fns opts])
      (merge opts (core/ok) {step "custom"}))
    (remove-method pluggable/handle-step ::lock)
    (run-steps []
               {::steps [:bar :baz]
                ::bc/env :repl
                ::lock/owner "alberto"}))
  (-> tap-values))

(def ^:dynamic *parse-args-steps* #{:lock :git-check :render :create :delete :exec :git-push :unlock-any})

(defn parse-args
  "Utility functions to normalize string or vector-based arguments. See the
  namespace `big-config.workflow`."
  [str-or-args]
  (loop [xs str-or-args
         token nil
         steps []
         cmds []]
    (cond
      (string? xs)
      (let [xs (-> (str/trim xs)
                   (str/split #"\s+"))]
        (recur (rest xs) (first xs) steps cmds))

      (and (sequential? xs)
           (seq xs)
           (nil? token))
      (recur (rest xs) (first xs) steps cmds)

      (*parse-args-steps* (keyword token))
      (let [steps (into steps [(keyword token)])]
        (recur (rest xs) (first xs) steps cmds))

      (= "--" token)
      (if (seq xs)
        (let [steps (if (some #{:exec} steps)
                      steps
                      (into steps [:exec]))
              cmds (conj cmds (str/join " " xs))]
          (recur '() nil steps cmds))
        (throw (ex-info "-- cannot be without a command" {})))

      token
      (let [steps (if (some #{:exec} steps)
                    steps
                    (into steps [:exec]))
            cmds (into cmds [(str/replace token ":" " ")])]
        (recur (rest xs) (first xs) steps cmds))

      :else
      {::steps steps
       ::run/cmds cmds})))

(comment
  (parse-args "render"))

(defn- add-suffix [kw suffix]
  (keyword (namespace kw) (str (name kw) suffix)))

(defn- parse-path [path]
  (str/split path #"/"))

(defn- build-path [parts profile suffix]
  (-> (vec parts)
      (conj (format "%s-%s" profile suffix))
      (->> (str/join "/"))))

(defn ^:no-doc new-prefix
  [{:keys [::object-prefix ::prefix ::render/profile] :as opts} first-step]
  (let [prefix (or prefix ".dist")
        object-prefix (or object-prefix "tofu")
        profile (or profile "default")
        dirs (parse-path prefix)
        object-dirs (parse-path object-prefix)
        profile-found? (str/starts-with? (last dirs) profile)
        prev-hash (when profile-found?
                    (last (str/split (last dirs) #"-")))
        base-dirs (if profile-found? (butlast dirs) dirs)
        base-object-dirs (if profile-found? (butlast object-dirs) object-dirs)
        suffix (-> first-step
                   (str prev-hash)
                   hash
                   Integer/toHexString)]

    (assoc opts
           ::prefix (build-path base-dirs profile suffix)
           ::object-prefix (build-path base-object-dirs profile suffix))))

(comment
  (new-prefix {} :io.github.amiorin.rama.package/start-create-or-delete))

(defn ->workflow*
  "Creates a *composition layer* — a \"workflow of workflows\" — from a
  `:pipeline` of subworkflow steps. Not a workflow step itself.

  Architectural decision — subworkflow isolation (by design):

  * The pure-step / single-threaded-`opts` contract is scoped to the steps
    *within one workflow*. At this composition layer the invariant is instead
    that every pipeline step runs on an isolated, purpose-built `opts`, built
    per step as `(merge step-args globals-opts <step>-opts)` — never the
    parent's running `opts`.
  * Each subworkflow's terminal `opts` is stored under its step key; only
    `::bc/exit` / `::bc/err` propagate upward to drive branching and
    short-circuit.

  The closed-over atom is the deliberate accumulator + isolation barrier that
  implements these semantics, not a purity leak. Folding everything into one
  threaded `opts` would break subworkflow isolation and is not a
  behavior-preserving change. See the namespace `big-config.workflow`."
  {:arglists '([wf*-opts])}
  [{:keys [first-step last-step pipeline]}]
  (when-not (sequential? pipeline)
    (throw (IllegalArgumentException. ":pipeline must be like [::tool/tofu ...\n::tool/ansible ...")))
  (fn [step-fns opts]
    (let [last-step (or last-step
                        (keyword (namespace first-step) "end"))
          globals-opts (-> opts
                           select-globals
                           (new-prefix first-step))
          step->opts-and-opts-fn (->> pipeline
                                      (apply hash-map)
                                      (reduce-kv (fn [a step [args opts-fn]]
                                                   (let [opts-fn (or opts-fn identity)
                                                         step-opts (-> step (add-suffix "-opts") opts)
                                                         step-args (parse-args args)
                                                         step-opts (merge step-args globals-opts step-opts)]
                                                     (assoc a step [step-opts opts-fn]))) {}))
          step->var (fn [step]
                      (cond
                        (= step first-step) core/ok
                        (= step last-step) identity
                        :else (let [sym (symbol (namespace step) (name step))]
                                (partial (requiring-resolve sym) step-fns))))
          step->f-and-next-step (-> pipeline
                                    (->> (take-nth 2))
                                    (->> (cons first-step))
                                    (concat [last-step nil])
                                    (->> (partition 2 1))
                                    (->> (reduce (fn [a [step next-step]]
                                                   (let [f (step->var step)]
                                                     (assoc a step [f next-step]))) {})))
          opts* (atom opts)
          wire-fn (fn [step _] (step->f-and-next-step step))
          steps-set (-> pipeline (->> (take-nth 2)) set)
          next-fn (fn [step next-step {:keys [::bc/exit] :as opts}]
                    (if (steps-set step)
                      (do
                        (swap! opts* merge (select-keys opts [::bc/exit ::bc/err]))
                        (swap! opts* assoc step opts))
                      (reset! opts* opts))
                    (cond
                      (= step ::end)
                      [nil @opts*]

                      (> exit 0)
                      [::end @opts*]

                      :else
                      [next-step (let [[new-opts opts-fn]
                                       (get step->opts-and-opts-fn next-step [@opts* identity])]
                                   (opts-fn new-opts))]))
          wf (pluggable/->workflow* {:first-step first-step
                                     :last-step last-step
                                     :wire-fn wire-fn
                                     :next-fn next-fn})]
      (wf step-fns opts))))

(comment
  (debug tap-values
    #_{:clj-kondo/ignore [:inline-def]}
    (defn s1
      [_step-fns opts]
      (core/ok opts))
    #_{:clj-kondo/ignore [:inline-def]}
    (defn s2
      [_step-fns opts]
      (core/ok opts))
    (let [wf (->workflow* {:first-step ::start
                           :pipeline [::s1 ["pwd"]
                                      ::s2 ["pwd"]]})]
      (wf [] {::bc/env :repl})))
  (-> tap-values))

(defn path
  "Find the path of a previous workflow to extract the outputs. See the namespace `big-config.workflow`."
  [{:keys [::prefix]} name]
  (format "%s/%s" (or prefix ".dist") (keyword->path name)))

(comment
  (path {::prefix ".dist/inst-a"} ::tofu))

(defn prepare
  "Prepare `opts`. See the namespace `big-config.workflow`."
  {:arglists '([opts overrides])}
  [{:keys [::name] :as opts} {:keys [::object-fn ::path-fn ::object-prefix ::prefix ::params] :as overrides}]
  (assert-args-present opts overrides name)
  (let [path-fn (or path-fn #(format "%s/%s" (or prefix ".dist") (-> % ::name keyword->path)))
        object-fn (or object-fn #(format "%s/%s" (or object-prefix "tofu") (-> % ::name keyword->name)))
        opts (merge opts overrides)
        dir (path-fn opts)
        object (object-fn opts)
        opts (->> opts
                  (s/transform [::render/templates s/ALL] #(merge % params
                                                                  {:target-dir dir
                                                                   :target-object object}))
                  (s/setval [::run/shell-opts :dir] dir))]
    opts))

(comment
  (debug tap-values
    (prepare (new-prefix {::name ::tofu
                          ::render/templates [{}]} ::foo) {})))

(defn merge-params
  "Merge the package params with the tool params. Tools is a seq of qualified
  keywords. See the namespace `big-config.workflow`."
  [tools params opts]
  (let [tool (first tools)]
    (if tool
      (->> opts
           (s/transform [::create-opts tool ::params] #(merge params %))
           (s/transform [::delete-opts tool ::params] #(merge params %))
           (recur (rest tools) params))
      opts)))

(comment
  (debug tap-values
    (merge-params [:tools/tofu-opts :tools/tofu-dns-opts] {:zone-id "zone-id"} {::create-opts {:tools/tofu-opts {::params {:zone-id "my-zone-id"}}}}))
  (-> tap-values))

(def ^:private prefix "BC_PAR_")

(defn read-bc-pars
  "Function to override any `params` with an environment variable. If the
  `param` is `cloudflare-zone-id` then the environment variable is `export
  BC_PAR_CLOUDFLARE_ZONE_ID=\"your-zone-id\"` See the namespace
  `big-config.workflow`.
  "
  ([opts] (read-bc-pars opts (System/getenv)))
  ([opts env]
   (let [params-from-env (->> env
                              (filter #(str/starts-with? (key %) prefix))
                              (map (fn [[k v]] [(-> k
                                                    (subs (count prefix))
                                                    str/lower-case
                                                    (str/replace "_" "-")
                                                    (str/replace "." "-")
                                                    keyword) v]))
                              (into {}))]
     (merge-with merge opts {::params params-from-env}))))

(comment
  (read-bc-pars {::params {:foo :bar}}))
