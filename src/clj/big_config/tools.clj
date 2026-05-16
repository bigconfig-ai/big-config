(ns big-config.tools
  "These are the templates available in BigConfig out of the box.

  ### Available Templates

    * `package`: Scaffold a compute-only BigConfig package.
    * `devenv`: Generate `devenv` files for Clojure and Babashka development.
    * `action`: Create GitHub Actions workflows for Clojure projects.

  -----

  ### DEPRECATED Templates

  > **Note:** These templates must be migrated from `step` to `workflow`.

    * `terraform`: Scaffold a project to manage Terraform/OpenTofu with BigConfig.
    * `dotfiles`: Manage user configuration (dotfiles) with templates.
    * `ansible`: Scaffold an Ansible-based infrastructure project.
    * `multi`: A hybrid template for projects using both Ansible and Terraform.
    * `tools`: Create a `tools.clj` for programmatic workflow control.
    * `generic`: A base template for generic infrastructure projects.

  See [Clojure Tools](/guides/clojure-tools/) guide.
  "
  (:require
   [babashka.fs :as fs]
   [babashka.neil :as neil]
   [babashka.process :refer [shell]]
   [big-config :as bc]
   [big-config.render :as render]
   [big-config.selmer-filters]
   [big-config.step-fns :as step-fns]
   [big-config.utils :refer [debug deep-merge]]
   [big-config.workflow :as workflow]
   [clojure.spec.alpha :as s]
   [clojure.string :as str]))

(defn- help
  [& _]
  (println "Use `clojure -A:deps -Tbig-config help/doc` instead"))

(comment
  (help))

(def ^:no-doc non-blank-string? (s/and string? (complement str/blank?)))
(s/def ::target-dir non-blank-string?)
(def ^:no-doc boolean-or-keyword? (s/or :keyword keyword? :boolean boolean?))
(s/def ::overwrite boolean-or-keyword?)
(s/def ::aws-profile non-blank-string?)
(s/def ::region non-blank-string?)
(s/def ::dev non-blank-string?)
(s/def ::prod non-blank-string?)

(defn- git-setup
  [{:keys [target-dir]} _]
  (when-not (fs/exists? (str target-dir "/.git"))
    (shell {:dir target-dir} "git init")
    (shell {:dir target-dir} "git add -A")
    (shell {:dir target-dir} "git commit -m 'initial import'")))

(defn ^:no-doc rename
  [{:keys [target-dir]} _]
  (fs/walk-file-tree target-dir
                     {:visit-file
                      (fn
                        [path _]
                        (let [path (str path)]
                          (when (str/ends-with? path ".source")
                            (fs/move path (str/replace path #".source$"  "") {:replace-existing true})))
                        :continue)}))

(comment
  (rename {:target-dir "test/fixtures/target/tools-8"} nil))

(defn- upgrade
  [{:keys [target-dir]} _]
  (binding [*out* (java.io.StringWriter.)]
    (neil/dep-upgrade {:opts {:deps-file (format "%s/deps.edn" target-dir)}})))

(defn- prepare
  [args]
  (reduce-kv (fn [a k v]
               (cond
                 (#{:overwrite :opts :post-process-fn :data-fn :template-fn} k) (assoc a k v)
                 :else (assoc a k (str v)))) {} args))

(defn- args->opts
  [args spec]
  (let [args (s/conform spec args)
        _ (when (s/invalid? args)
            (throw (ex-info "Invalid input" (s/explain-data spec args))))
        args (update args :overwrite #(second %))
        opts (:opts args)
        template (dissoc args :opts)]
    (merge {::render/templates [template]} opts)))

(def step-fns [workflow/print-step-fn
               (step-fns/->exit-step-fn ::workflow/end)
               (step-fns/->print-error-step-fn ::workflow/end)])

(defn- run-template
  [spec args defaults]
  (let [template-name (name spec)
        common {:template (format "big-config/%s" template-name)
                :target-dir template-name
                :overwrite true
                :opts (merge (workflow/parse-args "render")
                             {::bc/env :shell
                              ::workflow/name spec})}
        args (deep-merge common defaults (prepare args))
        opts (args->opts args spec)]
    (workflow/run-steps step-fns opts)))

(s/def ::owner non-blank-string?)
(s/def ::repository non-blank-string?)
(s/def ::package (s/keys :req-un [::target-dir ::overwrite ::owner ::repository]))

(defn data-fn [{:keys [owner repository] :as data} _ops]
  (let [namespace (format "io.github.%s.%s" owner repository)
        path (str/replace namespace #"\." "/")]
    (-> data
        (assoc :deps (format "io.github.%s/%s" owner repository))
        (assoc :namespace namespace)
        (assoc :path path))))

(defn package
  "Create a compute-only BigConfig package.

  Options:
  - :owner       GitHub owner
  - :repository  GitHub repository
  - :target-dir  target directory for the template (`package` is the default)
  - :overwrite   true or :delete (the target directory)

  Example:
    clojure -Tbig-config package :owner your-github-user :repository your-repo"
  [& {:as args}]
  (let [delimiters {:tag-open \<
                    :tag-close \>
                    :filter-open \{
                    :filter-close \}}]
    (run-template ::package args {:post-process-fn [rename upgrade]
                                  :data-fn data-fn
                                  :transform [["ansible" "src/resources/{{ path }}/tools/ansible" :raw]
                                              ["ansible-local" "src/resources/{{ path }}/tools/ansible-local" :raw]
                                              ["clj-kondo" ".clj-kondo" :raw]
                                              ["env" "env/dev/clj" :raw]
                                              ["lsp" ".lsp" :raw]
                                              ["root"
                                               {"envrc" ".envrc"
                                                "envrc.private" ".envrc.private"
                                                "gitignore" ".gitignore"
                                                "projectile" ".projectile"
                                                "dir-locals.el" ".dir-locals.el"}
                                               delimiters]
                                              ["src" "src/clj/{{ path }}" delimiters]
                                              ["tofu" "src/resources/{{ path }}/tools/tofu" :raw]
                                              ["tofu-backend" "src/resources/{{ path }}/tools/tofu-backend" :raw]]})))

(comment
  (debug tap-values
    (package :opts {::bc/env :repl}
             :target-dir "../../joe"
             :owner 'amiorin
             :repository 'joe))
  (-> tap-values))

(s/def ::terraform (s/keys :req-un [::target-dir ::overwrite ::aws-profile ::region ::dev ::prod]))

(defn terraform
  "DEPRECATED, use the `package` template

  Create a repo to manage Terraform/Tofu projects with BigConfig.

  Options:
  - :target-dir  target directory for the template (`terrafom` is the default)
  - :overwrite   true or :delete (the target directory)
  - :aws-profile aws profile in ~/.aws/crendentials
  - :region      aws region
  - :dev         aws account id for dev
  - :prod        aws account id for prod

  Example:
    clojure -Tbig-config terraform :region us-west-1"
  [& {:as args}]
  (run-template ::terraform args {:aws-profile "default"
                                  :region "eu-west-1"
                                  :dev "111111111111"
                                  :prod "222222222222"
                                  :post-process-fn [rename upgrade]
                                  :transform [["root"
                                               {"projectile" ".projectile"}
                                               {:tag-open \<
                                                :tag-close \>
                                                :filter-open \{
                                                :filter-close \}}]]}))

(comment
  (terraform :opts {::bc/env :repl}
             :aws-profile "251213589273"
             :region "eu-west-1"
             :dev "251213589273"
             :prod "251213589273"))

(s/def ::devenv (s/keys :req-un [::target-dir ::overwrite]))

(defn devenv
  "Create the devenv files for Clojure and Babashka development.

  Options:
  - :target-dir  target directory for the template (current directory is the default)

  Example:
    clojure -Tbig-config devenv"
  [& {:as args}]
  (run-template ::devenv args {:target-dir "."
                               :transform [["root"
                                            {"envrc" ".envrc"
                                             "devenv.nix" "devenv.nix"
                                             "devenv.yml" "devenv.yml"}
                                            :only
                                            :raw]]}))

(comment
  (devenv :opts {::bc/env :repl}))

(s/def ::dotfiles (s/keys :req-un [::target-dir ::overwrite]))

(defn dotfiles
  "DEPRECATED: clone https://github.com/amiorin/dotfiles-v3

  Create a repo to manage dotfiles with BigConfig.

  Options:
  - :target-dir  target directory for the template (`dotfiles` is the default)
  - :overwrite   true or :delete (the target directory)

  Example:
    clojure -Tbig-config dotfiles"
  [& {:as args}]
  (run-template ::dotfiles args {:post-process-fn [rename upgrade git-setup]
                                 :transform [["root"
                                              {"projectile" ".projectile"
                                               "envrc" ".envrc"
                                               "gitignore" ".gitignore"}
                                              :raw]]}))

(comment
  (dotfiles :opts {::bc/env :repl}))

(s/def ::ansible (s/keys :req-un [::target-dir ::overwrite]))

(defn ansible
  "DEPRECATED, use the `package` template

  Create a repo to manage Ansible projects with BigConfig.

  Options:
  - :target-dir  target directory for the template (`ansible` is the default)
  - :overwrite   true or :delete (the target directory)

  Example:
    clojure -Tbig-config ansible"
  [& {:as args}]
  (run-template ::ansible args {:post-process-fn [rename upgrade]
                                :transform [["root"
                                             {"envrc" ".envrc"
                                              "envrc.private" ".envrc.private"
                                              "gitignore" ".gitignore"
                                              "projectile" ".projectile"}
                                             :raw]]}))

(comment
  (ansible :opts {::bc/env :repl}))

(s/def ::multi (s/keys :req-un [::target-dir ::overwrite]))

(defn multi
  "DEPRECATED, use the `package` template

  Create a repo to manage both Ansible and Terraform projects with BigConfig.

  Options:
  - :target-dir  target directory for the template (`multi` is the default)
  - :overwrite   true or :delete (the target directory)

  Example:
    clojure -Tbig-config multi"
  [& {:as args}]
  (run-template ::multi args {:post-process-fn [rename upgrade]
                              :transform [["root"
                                           {"envrc" ".envrc"
                                            "envrc.private" ".envrc.private"
                                            "gitignore" ".gitignore"
                                            "projectile" ".projectile"}
                                           :raw]]}))

(comment
  (multi :opts {::bc/env :repl}))

(s/def ::action (s/keys :req-un [::target-dir ::overwrite]))

(defn action
  "Create a GitHub action for the CI of a Clojure project.

  Options:
  - :target-dir  target directory for the template (`action` is the default)
  - :overwrite   true or :delete (the target directory)

  Example:
    clojure -Tbig-config action"
  [& {:as args}]
  (run-template ::action args {:target-dir ".github/workflows"
                               :transform [["root"
                                            {"ci.yml" "ci.yml"}
                                            {:tag-open \<
                                             :tag-close \>
                                             :filter-open \<
                                             :filter-close \>}]]}))

(comment
  (action :opts {::bc/env :repl}))

(s/def ::path non-blank-string?)
(s/def ::ns non-blank-string?)
(s/def ::name non-blank-string?)
(s/def ::tools (s/keys :req-un [::target-dir ::overwrite ::path ::ns ::name]))

(defn tools
  "DEPRECATED, use https://github.com/amiorin/big-config/blob/main/src/clj/big_config/tools.clj

  Create a tools.clj for a Clojure project.

  Options:
  - :target-dir  target directory for the template (current directory is the default)
  - :path        path for the clojure source code
  - :ns          namespace containing the file tools.clj
  - :name        override the default name `tools`

  Example:
    clojure -Tbig-config tools"
  [& {:as args}]
  (run-template ::tools args {:target-dir "."
                              :name "tools"
                              :post-process-fn rename
                              :transform [["root"
                                           {"tools.clj.source" "{{ path }}/{{ ns|->file }}/{{ name|->file }}.clj.source"}
                                           {:tag-open \<
                                            :tag-close \>
                                            :filter-open \<
                                            :filter-close \>}]]}))

(comment
  (tools :opts {::bc/env :repl}
         :path "src/clj"
         :ns "big-config"
         :name "tools-v2"))

(s/def ::generic (s/keys :req-un [::target-dir ::overwrite]))

(defn generic
  "DEPRECATED, use the `package` template

  Create a repo to manage a generic projects with BigConfig.

  Options:
  - :target-dir  target directory for the template (`generic` is the default)
  - :overwrite   true or :delete (the target directory)

  Example:
    clojure -Tbig-config generic"
  [& {:as args}]
  (run-template ::generic args {:post-process-fn [rename upgrade]
                                :transform [["root"
                                             {"envrc" ".envrc"
                                              "envrc.private" ".envrc.private"
                                              "gitignore" ".gitignore"
                                              "projectile" ".projectile"}
                                             {:tag-open \<
                                              :tag-close \>}]]}))

(comment
  (generic :opts {::bc/env :repl}))
