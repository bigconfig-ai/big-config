(ns big-config.utils
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [com.rpl.specter :as s]))

(defn deep-merge
  "Recursively merges maps."
  [& maps]
  (letfn [(m [& xs]
            (if (some #(and (map? %) (not (record? %))) xs)
              (apply merge-with m xs)
              (last xs)))]
    (reduce m maps)))

(defn sort-nested-map [m]
  (cond
    (map? m) (into (sorted-map)
                   (for [[k v] m]
                     [k (cond
                          (map? v) (sort-nested-map v)
                          (or (vector? v)
                              (seq? v)) (mapv sort-nested-map v)
                          :else v)]))
    (or (vector? m)
        (seq? m)) (mapv sort-nested-map m)
    :else m))

(defn port-assigner [service]
  (-> (fs/cwd)
      (str service)
      hash
      abs
      (mod 64000)
      (+ 1024)))

(comment
  (port-assigner ["postgres"]))

(defmacro assert-args-present
  [& symbols]
  `(doseq [pair# ~(zipmap (map keyword symbols) symbols)]
     (when (nil? (val pair#))
       (throw (IllegalArgumentException. (format "Argument %s is nil" (key pair#)))))))

(defn ->fn
  "Coerce `v` into a function:

  * a function          -> returned unchanged
  * a symbol or string  -> resolved via `requiring-resolve`
  * any other `ifn?`    -> returned unchanged (e.g. keyword, set, var)
  * `nil`               -> `default`; with the 1-arity (no `default`
                           supplied) a nil `v` throws, since the value is
                           then required

  Throws `ex-info` with structured `{:big-config/err-kind ::not-a-fn}` data
  on anything that is not coercible. This is the single source of truth that
  replaces the ad-hoc fn-or-symbol `cond`s previously duplicated across
  `big-config.core`, `big-config.workflow` and `big-config.render`."
  ([v] (->fn v ::required))
  ([v default]
   (cond
     (fn? v)     v
     (symbol? v) (requiring-resolve v)
     (string? v) (requiring-resolve (symbol v))
     (ifn? v)    v
     (nil? v)    (if (= ::required default)
                   (throw (ex-info "Required value is nil; expected a function, symbol or string"
                                   {:big-config/err-kind ::not-a-fn :value v}))
                   default)
     :else       (throw (ex-info "Cannot coerce value to a function"
                                  {:big-config/err-kind ::not-a-fn
                                   :value v
                                   :type (type v)})))))

(defn keyword->path
  "Converts a keyword into a file path string. Namespaces are treated as
  directories and dots are converted into slashes.
  Example: `:big-config.core/foo` -> `\"big-config/core/foo\"`"
  [kw]
  (let [full-str (if-let [ns (namespace kw)]
                   (str ns "/" (name kw))
                   (name kw))]
    (-> full-str
        (str/replace "." "/"))))

(defn keyword->name
  "Converts a keyword into a file name string. Namespaces are treated as
  words and dots are converted into dashes.
  Example: `:big-config.core/foo` -> `\"big-config-core-foo\"`"
  [kw]
  (let [full-str (if-let [ns (namespace kw)]
                   (str ns "-" (name kw))
                   (name kw))]
    (-> full-str
        (str/replace "/" "-")
        (str/replace "." "-"))))


(def MAP-WALKER
  (s/recursive-path [] p
                    (s/if-path map?
                               (s/continue-then-stay s/MAP-VALS p)
                               (s/if-path vector?
                                          [s/ALL p]))))

(defn deep-sort-maps [data]
  (s/transform MAP-WALKER
               (fn [m] (into (sorted-map) m))
               data))

(defmacro debug
  "Executes `body`, capturing all `tap>` values emitted during execution.

  1. Returns the result of `body`. If the result is a map, the captured
     taps are `assoc`'d under the keyword `:(name sym)`.
  2. Binds the captured taps to the Var `sym` in the current namespace
     via `def` (essential for inspection if an exception occurs).

  Ensures the tap listener is removed even if `body` throws."
  [sym & body]
  (let [kw (keyword sym)]
    `(let [tap-values# (atom [])
           done# (promise)
           f# (fn [v#]
                (if (= v# :done)
                  (deliver done# true)
                  (swap! tap-values# conj v#)))]
       (add-tap f#)
       (try
         (let [res# (do ~@body)]
           (tap> :done)
           @done#
           (if (map? res#)
             (-> res#
                 deep-sort-maps
                 (assoc ~kw (deep-sort-maps @tap-values#)))
             res#))
         (finally
           (tap> :done)
           @done#
           (def ~sym (deep-sort-maps @tap-values#))
           (remove-tap f#))))))

(comment
  (debug tap-values
    (defn fn-wip
      []
      (tap> [:fn-wip {::b {::d 4 ::c 3} ::a 1 ::e [{::g 6 ::f 5}]}])
      #_(throw (Exception. "There is a bug"))
      {::b {::d 4 ::c 3} ::a 1 ::e [{::g 6 ::f 5}]})
    (fn-wip))
  (-> tap-values))
