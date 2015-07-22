(ns planck.core
  (:require [cljs.js :as cljs]
            [cljs.pprint :refer [pprint]]
            [cljs.tagged-literals :as tags]
            [cljs.tools.reader :as r]
            [cljs.tools.reader.reader-types :refer [string-push-back-reader]]
            [cljs.analyzer :as ana]
            [cljs.repl :as repl]
            [clojure.string :as s]
            [cljs.env]))

(def DEBUG true)

(def st (cljs/empty-state))

(defn ^:export setup-cljs-user []
  (js/eval "goog.provide('cljs.user')")
  (js/eval "goog.require('cljs.core')"))

(def app-env (atom nil))

(defn map-keys [f m]
  (reduce-kv (fn [r k v] (assoc r (f k) v)) {} m))

(defn ^:export init-app-env [app-env]
  (reset! planck.core/app-env (map-keys keyword (cljs.core/js->clj app-env))))

(defn repl-read-string [line]
  (r/read-string {:read-cond :allow :features #{:cljs}} line))

(defn ^:export is-readable? [line]
  (binding [r/*data-readers* tags/*cljs-data-readers*]
    (try
      (repl-read-string line)
      true
      (catch :default _
        false))))

(def current-ns (atom 'cljs.user))

(defn ns-form? [form]
  (and (seq? form) (= 'ns (first form))))

(def repl-specials '#{in-ns doc})

(defn repl-special? [form]
  (and (seq? form) (repl-specials (first form))))

(def repl-special-doc-map
  '{in-ns {:arglists ([name])
           :doc      "Sets *cljs-ns* to the namespace named by the symbol, creating it if needed."}
    doc   {:arglists ([name])
           :doc      "Prints documentation for a var or special form given its name"}})

(defn- repl-special-doc [name-symbol]
  (assoc (repl-special-doc-map name-symbol)
    :name name-symbol
    :repl-special-function true))

;; Copied from cljs.analyzer.api (which hasn't yet been converted to cljc)
(defn resolve
  "Given an analysis environment resolve a var. Analogous to
   clojure.core/resolve"
  [env sym]
  {:pre [(map? env) (symbol? sym)]}
  (try
    (ana/resolve-var env sym
      (ana/confirm-var-exists-throw))
    (catch :default _
      (ana/resolve-macro-var env sym))))

(defn ^:export print-prompt []
  (print (str @current-ns "=> ")))

(defn ^:export read-eval-print [line]
  (binding [ana/*cljs-ns* @current-ns
            *ns* (create-ns @current-ns)
            r/*data-readers* tags/*cljs-data-readers*]
    (let [env (assoc (ana/empty-env) :context :expr
                                     :ns {:name @current-ns})]
      (try
        (let [_ (when DEBUG (prn "line:" line))
              form (repl-read-string line)]
          (if (repl-special? form)
            (case (first form)
              in-ns (reset! current-ns (second (second form)))
              doc (if (repl-specials (second form))
                    (repl/print-doc (repl-special-doc (second form)))
                    (repl/print-doc
                      (let [sym (second form)
                            var (resolve env sym)]
                        (:meta var)))))
            (try
              (cljs/eval-str*
                {:*compiler*     st
                 :*cljs-ns*      @current-ns
                 :*ns*           *ns*
                 :*data-readers* tags/*cljs-data-readers*
                 :*analyze-deps* true
                 :*load-macros*  true
                 :*load-fn*      (fn [name cb])
                 :*eval-fn*      (fn [source]
                                   (try
                                     {:result (js/eval (:source source))}
                                     (catch :default e {:exception e})))
                 :*sm-data*      nil}
                line
                nil
                {:verbose       true
                 :context       :expr
                 :def-emits-var true}
                (fn [{:keys [ns value] :as ret}]
                  (prn ret)
                  (prn value)
                  (when-not
                    (or ('#{*1 *2 *3 *e} form)
                      (ns-form? form))
                    (set! *3 *2)
                    (set! *2 *1)
                    (set! *1 value))
                  (when (ns-form? form)
                    (reset! current-ns ns))))
              (catch js/Error e
                (set! *e e)
                (print (.-message e) "\n" (first (s/split (.-stack e) #"eval code")))))))
        (catch js/Error e
          (println (.-message e)))))))
