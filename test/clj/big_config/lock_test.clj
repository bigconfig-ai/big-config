(ns big-config.lock-test
  "Unit tests enabled by the `run/*runner*` seam (Finding #2). These exercise
  command construction for the security-sensitive lock workflow without
  spawning git or touching a real repository."
  (:require
   [big-config :as bc]
   [big-config.lock :as lock]
   [big-config.run :as run]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(defn- recording-runner
  "A fake `*runner*` that records every invocation and returns a proc-like map."
  [log]
  (fn [shell-opts cmd]
    (swap! log conj {:shell-opts shell-opts :cmd cmd})
    {:exit 0 :out "" :err "" :cmd cmd}))

(defn- last-cmd [opts]
  (-> opts ::bc/procs peek :cmd))

(deftest lock-commands-are-argv-vectors-not-format-strings
  (let [opts {::lock/lock-name "LOCK-DEAD"}
        log  (atom [])]
    (binding [run/*runner* (recording-runner log)]
      (testing "delete-tag (public)"
        (is (= ["git" "tag" "-d" "LOCK-DEAD"]
               (last-cmd (lock/delete-tag opts)))))
      (testing "push-tag (private)"
        (is (= ["git" "push" "origin" "LOCK-DEAD"]
               (last-cmd (#'lock/push-tag opts)))))
      (testing "delete-remote-tag (public)"
        (is (= ["git" "push" "--delete" "origin" "LOCK-DEAD"]
               (last-cmd (lock/delete-remote-tag opts)))))
      (testing "get-remote-tag (private)"
        (is (= ["git" "fetch" "origin" "tag" "LOCK-DEAD" "--no-tags"]
               (last-cmd (#'lock/get-remote-tag opts)))))
      (testing "read-tag (private)"
        (is (= ["git" "cat-file" "-p" "LOCK-DEAD"]
               (last-cmd (#'lock/read-tag opts)))))
      (testing "check-remote-tag (public) — value is a single argv element"
        (is (= ["git" "ls-remote" "--exit-code" "origin" "refs/tags/LOCK-DEAD"]
               (last-cmd (lock/check-remote-tag opts))))))))

(deftest create-tag-passes-lock-details-via-stdin-not-the-command-line
  (let [opts {::lock/lock-name "LOCK-DEAD"
              ::lock/lock-details {::lock/owner "alberto"}}
        log  (atom [])]
    (binding [run/*runner* (recording-runner log)]
      (#'lock/create-tag opts)
      (let [{:keys [shell-opts cmd]} (last @log)]
        (is (= ["git" "tag" "-a" "LOCK-DEAD" "-F" "-"] cmd))
        (is (str/starts-with? (:in shell-opts) ">>>")
            "lock-details flow through stdin, never interpolated into the command")))))

(deftest a-malicious-lock-name-cannot-break-out-of-the-argv
  (testing "with argv vectors, shell metacharacters are inert data, not syntax"
    (let [evil "LOCK-x; rm -rf / #"
          log  (atom [])]
      (binding [run/*runner* (recording-runner log)]
        (is (= ["git" "tag" "-d" evil]
               (last-cmd (lock/delete-tag {::lock/lock-name evil})))
            "the dangerous string stays a single, opaque argument")))))
