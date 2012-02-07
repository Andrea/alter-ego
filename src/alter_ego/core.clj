(ns #^{:author "Nurullah Akkaya"}
  alter-ego.core
  (:refer-clojure :exclude [sequence]))

(defmulti exec 
  "Given a node dispatch to its exec implementation."
  (fn [node & [terminate?]]
    (cond (fn? node) :function
          :default (node :type))))

(defn- exec-action? [terminate?]
  (cond (nil? terminate?) true
        (not @terminate?) true
        :default false))

(defn- terminate [a]
  (swap! a (fn [_] (identity true))))

(defn- parse-children [children]
  (let [doc (if (string? (first children))
              (first children)
              "")
        children (if (string? (first children))
                   (next children)
                   children)
        id (if (symbol? (first children))
               (first children)
               nil)
        children (if (symbol? (first children))
                   (next children)
                   children)]

    [doc id children]))

;;
;; Leafs
;;

(defmethod exec :function [f & [terminate?]]
  (if (exec-action? terminate?)
    (boolean (f)) false))

(defmacro action [& body]
  (let [[doc id body] (parse-children body)]
    {:type :action :children `(fn [] ~@body) :doc doc}))

(defmethod exec :action
  [{children :children} & [terminate?]]
  (if (exec-action? terminate?)
    (boolean (children))
    false))

;;
;; Selectors
;;

(defn selector 
  "Tries to run all its children in sequence as soon as one succeeds 
   it also succeeds."
  [& children]
  (let [[doc id children] (parse-children children)]
    {:type :selector :children children :doc doc :id id}))

(defn non-deterministic-selector 
  "Same as selector, but shuffles all its children prior to execution."
  [& children]
  (let [[doc id children] (parse-children children)]
    {:type :non-deterministic-selector :children children :doc doc :id id}))

(defn- select [children terminate?]
  (if (exec-action? terminate?)
    (if-let[s (seq children)] 
      (if-not (exec (first s) terminate?)
        (recur (rest s) terminate?)
        true)
      false)
    false))

(defmethod exec :selector [{children :children} & [terminate?]]
  (if (not (true? (select children terminate?))) false true))

(defmethod exec :non-deterministic-selector [{children :children} & [terminate?]]
  (if (not (true? (select (shuffle children) terminate?))) false true))

;;
;; Sequences
;;

(defn sequence 
  "Runs all of its children in sequential order. If one of them fails, 
   it also fails. Once all of them succeeds, it also succeeds."
  [& children]
  (let [[doc id children] (parse-children children)]
    {:type :sequence :children children :doc doc :id id}))

(defn non-deterministic-sequence 
  "Same as sequence, but shuffles all its children prior to execution."
  [& children]
  (let [[doc id children] (parse-children children)]
    {:type :non-deterministic-sequence :children children :doc doc :id id}))

(defn- seq-run [children terminate?]
  (if (exec-action? terminate?)
    (if-let [s (seq children)]
      (if (exec (first s) terminate?)
        (recur (rest s) terminate?)
        false)
      true)
    false))

(defmethod exec :sequence [{children :children} & [terminate?]]
  (if (not (false? (seq-run children terminate?))) true false))

(defmethod exec :non-deterministic-sequence [{children :children} & [terminate?]]
  (if (not (false? (seq-run (shuffle children) terminate?))) true false))

(defn parallel
  [& xs]
  "Concurrently executes all its children. If policy is :sequence, it acts as a sequence.
   If the policy is :selector it acts as a selector."
  (let [[doc id [policy & children]] (parse-children xs)]
    {:type :parallel :policy policy :children children :doc doc :id id}))

(let [all-succeded? #(and (every? true? (map future-done? %))
                          (every? true? (map deref %)))
      
      all-failed? #(and (every? true? (map future-done? %))
                        (every? false? (map deref %)))

      some-succeded? #(some true? (map deref (filter future-done? %)))
      
      some-failed? #(some false? (map deref (filter future-done? %)))
      
      cancel (fn [fs atom return]
               (terminate atom)
               (doall (map deref fs)) return)]

  (defmethod exec :parallel [{children :children policy :policy} & [terminate?]]
    (if (exec-action? terminate?)
      (let [parent-terminate? terminate?
            self-terminate? (atom false)
            fs (map #(future (exec % self-terminate?)) children)]
        (if (= policy :sequence)
          (loop []
            (Thread/sleep 50)
            (cond (all-succeded? fs) true
                  (some-failed? fs)  (cancel fs self-terminate? false)
                  (not (exec-action? parent-terminate?)) (cancel fs self-terminate? false)
                  :default (recur)))

          (loop []
            (Thread/sleep 50)
            (cond (all-failed? fs) false
                  (some-succeded? fs) (cancel fs self-terminate? true)
                  (not (exec-action? parent-terminate?)) (cancel fs self-terminate? false)
                  :default (recur)))))
      false)))

;;
;; Decorators
;;

(defn forever
  "When its child task finishes, it runs it once more."
  [c]
  {:type :forever :children c})

(defmethod exec :forever [{children :children} & [terminate?]]
  (loop []
    (if (exec-action? terminate?)
      (do (exec children terminate?)
          (recur))
      false)))

(defn until-fail 
  "Runs its children until it returns false."
  [c]
  {:type :until-fail :children c})

(defmethod exec :until-fail [{children :children} & [terminate?]]
  (loop []
    (if (and (exec-action? terminate?)
             (exec children terminate?))
      (recur)
      true)))

(defn until-success
  "Runs its children until it returns true."
  [c]
  {:type :until-success :children c})

(defmethod exec :until-success [{children :children} & [terminate?]]
  (loop []
    (if (and (exec-action? terminate?)
             (not (exec children terminate?)))
      (recur)
      true)))

(defn limit 
  "Unless its children succeeds will keep running it at most i times."
  [i children]
  {:type :limit :children children :times i})

(defmethod exec :limit [{children :children times :times} & [terminate?]]
  (loop [i times]
    (if (and (pos? i)
             (exec-action? terminate?))
      (if (not (exec children terminate?)) 
        (recur (dec i))
        true)
      false)))

(defn inverter 
  "Inverts its childrens return value, succees becames failure and 
   vice versa."
  [c]
  {:type :inverter :children c})

(defmethod exec :inverter [{children :children} & [terminate?]]
  (not (exec children terminate?)))

(defn interrupter
  [w c p]
  "Lets its child node run normally. If the child returns a result,
   it passes that result on up the tree. But, if the child is still working,
   and watcher returns a result it will terminate the child and return the
   result of perform."
  {:type :interrupter :children c :watch w :perform p})

(defmethod exec :interrupter
  [{children :children watch :watch perform :perform} & [terminate?]]
  (if (exec-action? terminate?)
    (let [parent-terminate? terminate?
          terminate-children? (atom false)
          terminate-watch? (atom false)
          children (future (exec children terminate-children?))
          watch (future (exec watch terminate-watch?))]

      (loop []
        (Thread/sleep 50)
        (cond (future-done? children) (do (terminate terminate-watch?)
                                          (deref watch)
                                          (deref children))

              (and (future-done? watch)
                   (boolean @watch)) (do (terminate terminate-children?)
                                         (deref children)
                                         (exec perform))

              (not (exec-action? parent-terminate?)) (do (terminate terminate-children?)
                                                         (terminate terminate-watch?)
                                                         (deref watch)
                                                         (deref children)
                                                         (exec perform)
                                                         false)
              
              :default (recur))))
    false))

;;
;; Graphviz 
;;

(defn- default-doc [type]
  (let [capitalize #(str (Character/toUpperCase (.charAt % 0))
                         (.toLowerCase (subs % 1)))
        doc (map capitalize
                 (-> (name type)
                     (.split "-")))]
    (apply str (interpose \space doc))))

(defn graph
  ([tree f]
     (graph tree f #{}))
  ([tree f skip]
     (let [subgraphs (atom {})
           buffer (StringBuffer.)
           add (fn [& xs]
                 (.append buffer (apply str xs)))]
       (add "digraph bt {\n"
            "graph [ordering=\"out\",,rankdir=\"TB\"];"
            "node [fontname=Arial, style=\"rounded\", shape=box]"
            "edge [color=\"0.650 0.700 0.700\"]")
       (graph add skip subgraphs tree nil)
       (add "}")
       (spit f (.toString buffer))))
  
  ([add skip subgraphs node parent-id]
     (if (skip (:id node))
       (let [node-id (gensym "N_")]
         (add node-id " [label=\"" (:id node) "\",style=\"filled\",fillcolor=\"#00ff005f\"];\n")
         (add parent-id " -> "  node-id "\n"))
       (if (and (:id node)
                ((:id node) @subgraphs))
         (add parent-id " -> "  ((:id node) @subgraphs) "\n")
         (let [node-id (gensym "N_")
               {:keys [doc id children type policy watch perform times]} node
               children (->> (if (seq? children)
                               children
                               [children])
                             (filter #(not (fn? %))))
               extra-doc (cond
                          (= type :parallel) (str " - " (name policy))
                          (= type :limit) (str " - " times)
                          :default "")
               style (if (= type :action)
                       ",style=\"filled\",fillcolor=\"#CCFFFF\"" "")]

           (if (or (nil? doc)
                   (empty? doc))
             (add node-id " [label=\"" (default-doc type) extra-doc "\" " style "];\n")
             (add node-id " [label=\"" doc "\\n(" (name type) extra-doc ")\" " style "];\n"))
           
           (when parent-id
             (add parent-id " -> "  node-id "\n"))
           
           (when id
             (do (swap! subgraphs merge {id node-id})
                 (add "subgraph " (gensym "cluster_") " {\n"
                      "label = \"" id "\";\n"
                      "labeljust = \"l\";\n"
                      "style=dashed;"
                      "color=\"#B0B0B0\"")))

           (when watch
             (graph add skip subgraphs watch node-id))
           
           (doseq [c children]
             (graph add skip subgraphs c node-id))

           (when perform
             (graph add skip subgraphs perform node-id))
           
           (when id
             (add "}\n")))))))

(comment
  (let [common (non-deterministic-selector
                "Non Determine" 'common
                (until-success
                 (action "Kill"))
                (action 1))

        parallel (limit 3
                        (parallel "Paralel" 'parallel
                                  :sequence
                                  (action 1)
                                  (action 1)
                                  (action 1)))
        ]
    (graph (selector
            "Select from" 'top (action 2) common
            (interrupter
             (selector "Watch referee event" (action 1))
             (selector "Do Stuff" (sequence
                                   #(+ 1 3)
                                   common))
             (selector "Perform" (action 3)))
            
            (selector "Then try" common
                      parallel))
           "/Users/nakkaya/Desktop/test.gv"
           #{}))
  )
