(ns boot.main
  (:require
   [clojure.java.io             :as io]
   [clojure.string              :as string]
   [boot.pod                    :as pod]
   [boot.core                   :as core]
   [boot.file                   :as file]
   [boot.util                   :as util]
   [boot.from.clojure.tools.cli :as cli]))

(def cli-opts
  [["-a" "--asset-paths PATH"    "Add PATH to set of asset directories."
    :assoc-fn #(update-in %1 [%2] (fnil conj #{}) %3)]
   ["-b" "--boot-script"         "Print generated boot script for debugging."]
   ["-B" "--no-boot-script"      "Ignore build.boot file in current directory."]
   ["-C" "--no-colors"           "Remove ANSI escape codes from printed output."]
   ["-d" "--dependencies ID:VER" "Add dependency to project (eg. -d foo/bar:1.2.3)."
    :assoc-fn #(let [[p v] (string/split %3 #":" 2)]
                 (update-in %1 [%2] (fnil conj []) [(read-string p) (or v "RELEASE")]))]
   ["-e" "--set-env KEY=VAL"     "Add KEY => VAL to project env map."
    :assoc-fn #(let [[k v] (string/split %3 #"=" 2)]
                 (update-in %1 [%2] (fnil assoc {}) (keyword k) v))]
   ["-h" "--help"                "Print basic usage and help info."]
   ["-P" "--no-profile"          "Skip loading of profile.boot script."]
   ["-r" "--resource-paths PATH" "Add PATH to set of resource directories."
    :assoc-fn #(update-in %1 [%2] (fnil conj #{}) %3)]
   ["-q" "--quiet"               "Suppress output from boot itself."]
   ["-s" "--source-paths PATH"   "Add PATH to set of source directories."
    :assoc-fn #(update-in %1 [%2] (fnil conj #{}) %3)]
   ["-t" "--target-path PATH"    "Set the target directory to PATH."]
   ["-u" "--update"              "Update boot (see BOOT_CHANNEL env var below)."]
   ["-v" "--verbose"             "More error info (-vv more verbose, etc.)"
    :assoc-fn (fn [x y _] (update-in x [y] (fnil inc 0)))]
   ["-V" "--version"             "Print boot version info."]])

(defn- dep-ns-decls
  [jar]
  (binding [*print-meta* true]
    (pod/with-eval-worker
      (require '[clojure.tools.namespace.find :as nsf])
      (->> ~(.getPath (io/file jar))
        java.util.jar.JarFile. nsf/find-ns-decls-in-jarfile))))

(defn- export-tasks?
  [[_ name docstring? attr-map?]]
  (->> [docstring? attr-map?]
    (filter map?)
    first
    (merge (meta name))
    :boot/export-tasks))

(defn- export-task-namespaces
  [env]
  (-> #(->> (pod/resolve-dependency-jar env %)
         dep-ns-decls (filter export-tasks?) (map second))
    (mapcat (:dependencies env))))

(defn- parse-cli-opts [args]
  ((juxt :errors :options :arguments)
   (cli/parse-opts args cli-opts :in-order true)))

(defn- with-comments [tag forms]
  (concat
    [`(comment ~(format "start %s" tag))]
    forms
    [`(comment ~(format "end %s" tag))]))

(defn emit [boot? argv userscript bootscript import-ns]
  (let [boot-use '[boot.core boot.util boot.task.built-in]]
    `(~(list 'ns 'boot.user
         (list* :use (concat boot-use import-ns)))
      ~@(when userscript (with-comments "profile" userscript))
      ~@(with-comments "boot script" bootscript)
      (let [boot?# ~boot?]
        (if-not boot?#
          (when-let [main# (resolve 'boot.user/-main)] (main# ~@argv))
          (core/boot ~@(or (seq argv) ["boot.task.built-in/help"])))))))

(defn -main [pod-id worker-pod shutdown-hooks [arg0 & args :as args*]]
  (reset! pod/pod-id pod-id)
  (reset! pod/worker-pod worker-pod)
  (reset! pod/shutdown-hooks shutdown-hooks)

  (let [dotboot?         #(.endsWith (.getName (io/file %)) ".boot")
        script?          #(when (and % (.isFile (io/file %)) (dotboot? %)) %)
        bootscript       (io/file "build.boot")
        have-bootscript? (script? bootscript)
        [arg0 args]      (cond
                           (script? arg0)   [arg0 args]
                           have-bootscript? [bootscript args*]
                           :else            [nil args*])
        boot?            (contains? #{nil bootscript} arg0)
        [errs opts args] (if-not boot? [nil {} args] (parse-cli-opts args))
        arg0             (if (:no-boot-script opts) nil arg0)]

    (when (seq errs)
      (util/exit-error
        (println (apply str (interpose "\n" errs)))))

    (binding [*out*               (util/auto-flush *out*)
              *err*               (util/auto-flush *err*)
              core/*boot-opts*    opts
              core/*boot-version* (boot.App/getBootVersion)
              core/*app-version*  (boot.App/getVersion)]
      (util/exit-ok
        (let [userscript  (-> (System/getProperty "user.home")
                              (io/file ".profile.boot")
                              script?)
              verbosity   (if (:quiet opts)
                            (* -1 @util/*verbosity*)
                            (or (:verbose opts) 0))
              profile?    (not (:no-profile opts))
              bootforms   (some->> arg0 slurp util/read-string-all)
              userforms   (when profile?
                            (some->> userscript slurp util/read-string-all))
              initial-env (->> [:source-paths :resource-paths :asset-paths :target-path :dependencies]
                            (reduce #(if-let [v (opts %2)] (assoc %1 %2 v) %1) {})
                            (merge {} (:set-env opts)))
              import-ns   (export-task-namespaces initial-env)
              scriptforms (emit boot? args userforms bootforms import-ns)
              scriptstr   (binding [*print-meta* true]
                            (str (string/join "\n\n" (map pr-str scriptforms)) "\n"))]

          (reset! util/*colorize?* (not (:no-colors opts)))
          (swap! util/*verbosity* + verbosity)
          (pod/with-eval-in worker-pod
            (require '[boot.util :as util])
            (swap! util/*verbosity* + ~verbosity))

          (when (:boot-script opts) (util/exit-ok (print scriptstr)))

          (#'core/init!)

          (let [tmpf (.getPath (file/tmpfile "boot.user" ".clj"))]
            (pod/with-call-worker (boot.aether/load-wagon-mappings))
            (apply core/set-env! (->> initial-env (mapcat identity) seq))
            (try (doto tmpf (spit scriptstr) (load-file))
                 (catch clojure.lang.Compiler$CompilerException cx
                   (let [l (.-line cx)
                         s (->> (io/file (.-source cx))
                             (file/relative-to (io/file ".")))
                         c (.getCause cx)
                         m (.getMessage (or c cx))
                         x (or c cx)]
                     (throw (ex-info m (sorted-map :file s :line l) x)))))))))))
