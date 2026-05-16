(ns big-config.run
  (:require
   [babashka.process :as process]
   [big-config :as bc]
   [big-config.core :refer [->workflow ok]]
   [clojure.string :as str]))

(defn handle-cmd [opts proc]
  (let [res (-> (select-keys proc [:exit :out :err :cmd])
                (update-vals (fn [v] (if (string? v)
                                       (str/replace v #"\x1B\[[0-9;]+m" "")
                                       v))))]

    (-> opts
        (update ::bc/procs (fnil conj []) res)
        (merge (-> res
                   (select-keys [:exit :err])
                   (update-keys (fn [k] (keyword "big-config" (name k)))))))))

(defn default-runner
  "The single boundary between BigConfig and the OS. `cmd` may be a string
  (tokenized by babashka.process — used by the `exec` DSL) or a vector, which is
  splatted into `process/shell` as argv so each element is passed verbatim with
  no tokenization or shell interpretation (preferred for any command containing
  interpolated values). Rebind `*runner*` in tests to exercise command
  construction without spawning a real process."
  [shell-opts cmd]
  (if (sequential? cmd)
    (apply process/shell shell-opts cmd)
    (process/shell shell-opts cmd)))

(def ^:dynamic *runner* default-runner)

(defn generic-cmd [& {:keys [opts cmd key shell-opts]}]
  (let [shell-opts (merge {:continue true
                           :out :string
                           :err :string} shell-opts)
        proc (*runner* shell-opts cmd)
        opts (handle-cmd opts proc)]
    (if key
      (assoc opts key (-> (:out proc)
                          str/trim-newline))
      opts)))

(defn mktemp-create-dir [opts]
  (let [{:keys [::dir] :as opts} (generic-cmd :opts opts
                                              :cmd "bash -c 'readlink -f $(mktemp -d)'"
                                              :key ::dir)
        shell-opts {::shell-opts {:dir dir}}]
    (merge opts shell-opts)))

(defn mktemp-remove-dir [{:keys [::dir] :as opts}]
  (generic-cmd :opts opts
               :cmd ["rm" "-rf" dir]))

(defn run-cmd [{:keys [::bc/env ::shell-opts ::cmds] :as opts}]
  (let [shell-opts (assoc shell-opts :continue true)
        shell-opts (case env ; :lib :repl :shell
                     :lib (merge {:out :string
                                  :err :string}
                                 shell-opts)
                     (merge {:out :inherit
                             :err :inherit}
                            shell-opts))
        cmd (first cmds)
        proc (*runner* shell-opts cmd)]
    (handle-cmd opts proc)))

(defn push-nil [{:keys [::cmds] :as opts}]
  (let [cmds (if (seq cmds)
               (conj (seq cmds) nil)
               [nil])]
    (-> opts
        (assoc ::cmds cmds)
        ok)))

(def run-cmds
  (->workflow {:first-step ::start
               :wire-fn (fn [step _]
                          (case step
                            ::start [push-nil ::run-cmd]
                            ::run-cmd [run-cmd ::run-cmd]
                            ::end [identity]))
               :next-fn (fn [step _ {:keys [::bc/exit ::cmds] :as opts}]
                          (cond
                            (and (seq (rest cmds))
                                 (or (= exit 0)
                                     (nil? exit))) [::run-cmd (merge opts {::cmds (rest cmds)})]
                            (= step ::end) [nil opts]
                            :else [::end opts]))}))

(comment
  (run-cmds [(fn [f step opts]
               (println step)
               (f step opts))]
            {::bc/env :repl
             ::shell-opts {:continue true
                           :dir "big-infra"
                           :extra-env {"FOO" "BAR"}}
             ::cmds ["bash -c 'echo Error >&2 && exit 1"]}))
