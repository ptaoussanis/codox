(ns codox.writer.html
  "Documentation writer that outputs HTML."
  (:use [hiccup core page element])
  (:import [java.net URLEncoder]
           [com.vladsch.flexmark.ast Link LinkRef]
           [com.vladsch.flexmark.ext.wikilink WikiLink WikiLinkExtension]
           [com.vladsch.flexmark.html HtmlRenderer
            HtmlRenderer$HtmlRendererExtension LinkResolver LinkResolverFactory]
           [com.vladsch.flexmark.html.renderer LinkResolverBasicContext
            LinkStatus ResolvedLink]
           [com.vladsch.flexmark.parser Parser]
           [com.vladsch.flexmark.profile.pegdown Extensions
            PegdownOptionsAdapter]
           [com.vladsch.flexmark.util.misc Extension])
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [net.cgrand.enlive-html :as enlive-html]
            [net.cgrand.jsoup :as jsoup]
            [codox.utils :as util]))

(def enlive-operations
  {:append     enlive-html/append
   :prepend    enlive-html/prepend
   :after      enlive-html/after
   :before     enlive-html/before
   :substitute enlive-html/substitute})

(defn- enlive-transformer [[op & args]]
  (apply (enlive-operations op) (map enlive-html/html args)))

(defn- enlive-transform [nodes transforms]
  (reduce
   (fn [out [s t]]
     (enlive-html/transform out s (enlive-transformer t)))
   nodes
   (partition 2 transforms)))

(defn- enlive-emit [nodes]
  (apply str (enlive-html/emit* nodes)))

(defn- enlive-parse [s]
  (let [stream (io/input-stream (.getBytes s "UTF-8"))]
    (enlive-html/html-resource stream {:parser jsoup/parser})))

(defn- transform-html [project s]
  (-> (enlive-parse s)
      (enlive-transform (-> project :html :transforms))
      (enlive-emit)))

(defn- var-id [var]
  (str "var-" (-> var name URLEncoder/encode (str/replace "%" "."))))

(def ^:private url-regex
  #"((https?|ftp|file)://[-A-Za-z0-9+()&@#/%?=~_|!:,.;]+[-A-Za-z0-9+()&@#/%=~_|])")

(defn- extract-anchors! [state_ text]
  (if text
    (str/replace text url-regex
      (fn [[url]]
        (let [new-state (swap! state_ (fn [m] (assoc m (str (count m)) url)))]
          (str "__CODOX_ANCHOR__" (dec (count new-state)) "__"))))))

(comment (let [s_ (atom nil)] [(extract-anchors! s_ "foo http://x.com <https://y.com/path> bar") @s_]))

(defn- replace-anchors [state_ text]
  (if text
    (let [state @state_]
      (str/replace text #"__CODOX_ANCHOR__(\d+)__"
        (fn [[_ idx]]
          (let [url (get state idx)]
            (str "<a href=\"" url "\">" url "</a>")))))))

(comment (let [s_ (atom nil)] (replace-anchors s_ (h (extract-anchors! s_ "foo http://x.com <https://y.com/path> bar")))))

(defmulti format-docstring
  "Format the docstring of a var or namespace into HTML."
  (fn [project ns var] (:doc/format var))
  :default :plaintext)

(defmethod format-docstring :plaintext [_ _ metadata]
  (let [s_ (atom nil)]
    [:pre.plaintext (replace-anchors s_ (h (extract-anchors! s_ (:doc metadata))))]))

(defn- find-wiki-link [project ns text]
  (let [ns-strs (map (comp str :name) (:namespaces project))]
    (if (contains? (set ns-strs) text)
      (str text ".html")
      (if-let [var (util/search-vars (:namespaces project) text (:name ns))]
        (str (namespace var) ".html#" (var-id var))))))

(defn- absolute-url? [url]
  (re-find #"^([a-z]+:)?//" url))

(defn- fix-markdown-url [url]
  (if-not (absolute-url? url)
    (str/replace url #"\.(md|markdown)$" ".html")
    url))

(defn- update-link-url [^ResolvedLink link f]
  (-> link
      (.withStatus LinkStatus/VALID)
      (.withUrl (f (.getUrl link)))))

(defn- correct-internal-links [node link project ns]
  (condp instance? node
    WikiLink (update-link-url link #(find-wiki-link project ns %))
    LinkRef  (update-link-url link fix-markdown-url)
    Link     (update-link-url link fix-markdown-url)
    link))

(defn- make-renderer-extension
  [project ns]
  (reify HtmlRenderer$HtmlRendererExtension
    (rendererOptions [_ _])
    (extend [_ htmlRendererBuilder _]
      (.linkResolverFactory
       htmlRendererBuilder
       (reify LinkResolverFactory
         (getAfterDependents [_] nil)
         (getBeforeDependents [_] nil)
         (affectsGlobalScope [_] false)
         (^LinkResolver apply [_ ^LinkResolverBasicContext _]
          (reify LinkResolver
            (resolveLink [_ node _ link]
              (correct-internal-links node link project ns)))))))))

(defn- make-flexmark-options
  [project ns]
  (-> (PegdownOptionsAdapter/flexmarkOptions
       (bit-or Extensions/AUTOLINKS
               Extensions/QUOTES
               Extensions/SMARTS
               Extensions/STRIKETHROUGH
               Extensions/TABLES
               Extensions/FENCED_CODE_BLOCKS
               Extensions/WIKILINKS
               Extensions/DEFINITIONS
               Extensions/ABBREVIATIONS
               Extensions/ATXHEADERSPACE
               Extensions/RELAXEDHRULES
               Extensions/EXTANCHORLINKS)
       (into-array Extension [(make-renderer-extension project ns)]))
      (.toMutable)
      (.set WikiLinkExtension/LINK_FIRST_SYNTAX true)
      (.toImmutable)))

(defn- markdown-to-html
  ([doc project]
   (markdown-to-html doc project nil))
  ([doc project ns]
   (let [options  (make-flexmark-options project ns)
         parser   (.build (Parser/builder options))
         renderer (.build (HtmlRenderer/builder options))]
     (->> doc (.parse parser) (.render renderer)))))

(defmethod format-docstring :markdown [project ns metadata]
  [:div.markdown
   (if-let [doc (:doc metadata)]
     (markdown-to-html doc project ns))])

(defn- language-info
  ([language]
   (when language
     (case language
       :clojure       {:sort 1, :ext "clj",  :filename-suffix ".clj",  :name "Clojure"}
       :clojurescript {:sort 2, :ext "cljs", :filename-suffix ".cljs", :name "ClojureScript"}
       (ex-info (str "Unexpected language: `" language "`")
         {:language language}))))
         
  ([language base-language]
   (when-let [info (language-info language)]
     (if (= language base-language)
       (assoc info :filename-suffix "")
       info))))

(defn- sorted-languages [languages]
  (sort-by #(:sort (language-info %)) languages))

(comment (sorted-languages #{:clojurescript :clojure}))

(defn- index-filename [language]
  (str "index" (:filename-suffix (language-info language)) ".html"))

(defn- ns-filename [namespace]
  (str (:name namespace)
    (:filename-suffix (language-info (:language namespace) (:base-language namespace)))
    ".html"))

(comment
  (ns-filename {:name 'my-ns :language :clojure}))

(defn- ns-filepath [output-dir namespace]
  (str output-dir "/" (ns-filename namespace)))

(defn- doc-filename [doc]
  (str (:name doc) ".html"))

(defn- doc-filepath [output-dir doc]
  (str output-dir "/" (doc-filename doc)))

(defn- var-uri [namespace var]
  (str (ns-filename namespace) "#" (var-id (:name var))))

(defn- get-source-uri [source-uris path]
  (some (fn [[re f]] (if (re-find re path) f)) source-uris))

(defn- uri-basename [path]
  (second (re-find #"/([^/]+?)$" path)))

(defn- force-replace [^String s match replacement]
  (if (.contains s match)
    (str/replace s match (force replacement))
    s))

(defn- var-source-uri
  [{:keys [source-uri version git-commit]}
   {:keys [path file line]}]
  (let [path (util/uri-path path)
        uri  (if (map? source-uri) (get-source-uri source-uri path) source-uri)]
    (-> uri
        (str/replace   "{filepath}"   path)
        (str/replace   "{classpath}"  (util/uri-path file))
        (str/replace   "{basename}"   (uri-basename path))
        (str/replace   "{line}"       (str line))
        (str/replace   "{version}"    (str version))
        (force-replace "{git-commit}" git-commit))))

(defn- split-ns [namespace]
  (str/split (str namespace) #"\."))

(defn- namespace-parts [namespace]
  (->> (split-ns namespace)
       (reductions #(str %1 "." %2))
       (map symbol)))

(defn- add-depths [namespaces]
  (->> namespaces
       (map (juxt identity (comp count split-ns)))
       (reductions (fn [[_ ds] [ns d]] [ns (cons d ds)]) [nil nil])
       (rest)))

(defn- add-heights [namespaces]
  (for [[ns ds] namespaces]
    (let [d (first ds)
          h (count (take-while #(not (or (= d %) (= (dec d) %))) (rest ds)))]
      [ns d h])))

(defn- add-branches [namespaces]
  (->> (partition-all 2 1 namespaces)
       (map (fn [[[ns d0 h] [_ d1 _]]] [ns d0 h (= d0 d1)]))))

(defn- namespace-hierarchy [namespaces]
  (->> (map :name namespaces)
       (sort)
       (mapcat namespace-parts)
       (distinct)
       (add-depths)
       (add-heights)
       (add-branches)))

(defn- index-by [f m]
  (into {} (map (juxt f identity) m)))

;; The values in ns-tree-part are chosen for aesthetic reasons, based
;; on a text size of 15px and a line height of 31px.

(defn- ns-tree-part [height]
  (if (zero? height)
    [:span.tree [:span.top] [:span.bottom]]
    (let [row-height 31
          top        (- 0 21 (* height row-height))
          height     (+ 0 30 (* height row-height))]
      [:span.tree {:style (str "top: " top "px;")}
       [:span.top {:style (str "height: " height "px;")}]
       [:span.bottom]])))

(defn- index-link [project on-index?]
  (when-not (:cross-platform? project)
    (list
      [:h3.no-link [:span.inner "Project"]]
      [:ul.index-link
       [:li.depth-1 {:class (if on-index? "current")}
        (link-to "index.html" [:div.inner "Index"])]])))

(defn- topics-menu [project current-doc]
  (if-let [docs (seq (:documents project))]
    (list
     [:h3.no-link [:span.inner "Topics"]]
     [:ul
      (for [doc docs]
        [:li.depth-1
         {:class (if (= doc current-doc) " current")}
         (link-to (doc-filename doc) [:div.inner [:span (h (:title doc))]])])])))

(defn- nested-namespaces [namespaces current-ns]
  (let [ns-map (index-by :name namespaces)]
    [:ul
      (for [[name depth height branch?] (namespace-hierarchy namespaces)]
        (let [class  (str "depth-" depth (if branch? " branch"))
              short  (last (split-ns name))
              inner  [:div.inner (ns-tree-part height) [:span (h short)]]]
          (if-let [ns (ns-map name)]
            (let [class (str class (if (= ns current-ns) " current"))]
              [:li {:class class} (link-to (ns-filename ns) inner)])
            [:li {:class class} [:div.no-link inner]])))]))

(defn- flat-namespaces [namespaces current-ns]
  [:ul
   (for [ns (sort-by :name namespaces)]
     [:li.depth-1
      {:class (if (= ns current-ns) "current")}
      (link-to (ns-filename ns) [:div.inner [:span (h (:name ns))]])])])

(defn- namespace-list-type [project]
  (let [default (if (> (-> project :namespaces count) 1) :nested :flat)]
    (get-in project [:html :namespace-list] default)))

(defn- namespaces-menu [project current-ns]
  (when (:show-namespaces? project)
    (let [namespaces (:namespaces project)]
      (list
        [:h3.no-link [:span.inner "Namespaces"]]
        (case (namespace-list-type project)
          :flat   (flat-namespaces namespaces current-ns)
          :nested (nested-namespaces namespaces current-ns))))))

(defn- platforms-menu [project]
  (when (:show-platforms? project)
    (list
      [:h3.no-link [:span.inner "Platforms"]]
      [:ul.index-link
       (for [language (sorted-languages (:languages project))]
         [:li.depth-1
          (link-to (index-filename language)
            [:div.inner (:name (language-info language))])])])))

(defn- primary-sidebar [project & [current]]
  [:div.sidebar.primary
   (index-link project (nil? current))
   (platforms-menu project)
   (topics-menu project current)
   (namespaces-menu project current)])

(defn- sorted-public-vars [namespace]
  (sort-by (comp str/lower-case :name) (:publics namespace)))

(defn- vars-sidebar [namespace]
  [:div.sidebar.secondary
   [:h3 (link-to "#top" [:span.inner "Public Vars"])]
   [:ul
    (for [var (sorted-public-vars namespace)]
      (list*
       [:li.depth-1
        (link-to (var-uri namespace var) [:div.inner [:span (h (:name var))]])]
      (for [mem (:members var)]
        (let [branch? (not= mem (last (:members var)))
              class   (if branch? "depth-2 branch" "depth-2")
              inner   [:div.inner (ns-tree-part 0) [:span (h (:name mem))]]]
          [:li {:class class}
           (link-to (var-uri namespace mem) inner)]))))]])

(def ^:private default-meta
  [:meta {:charset "UTF-8"}])

(defn- project-title [project]
  [:span.project-title
   [:span.project-name (h (:name project))] " "
   [:span.project-version (h (:version project))]])

(defn- header-platforms [project]
  (when (:cross-platform? project)
    (let [{:keys [language languages]} project]
      [:div#langs
       (for [language* (sorted-languages languages)]
         (if (= language language*)
           [:div.lang.current (:ext (language-info language*))]
           [:div.lang (link-to (index-filename language*) (:ext (language-info language*)))]))])))

(defn- header [project]
  [:div#header
   [:h2 "Generated by " (link-to "https://github.com/weavejester/codox" "Codox")]
   [:h1 (link-to "index.html" (project-title project))]
   (header-platforms project)])

(defn- package [project]
  (if-let [p (:package project)]
    (if (= (namespace p) (name p))
      (symbol (name p))
      p)))

(defn- add-ending [^String s ^String ending]
  (if (.endsWith s ending) s (str s ending)))

(defn- strip-prefix [s prefix]
  (if s (str/replace s (re-pattern (str "(?i)^" prefix)) "")))

(defn- index-page [project]
  (html5
   [:head
    default-meta
    [:title (h (:name project)) " " (h (:version project))]]
   [:body
    (header project)
    (primary-sidebar project)
    [:div#content.namespace-index
     [:h1 (project-title project)]
     (if-let [license (-> (get-in project [:license :name]) (strip-prefix "the "))]
       [:h5.license
        "Released under the "
        (if-let [url (get-in project [:license :url])]
          (link-to url license)
          license)])
     (if-let [description (:description project)]
       [:div.doc [:p (h (add-ending description "."))]])
     (if-let [package (package project)]
       (list
        [:h2 "Installation"]
        [:p "To install, add the following dependency to your project or build file:"]
        [:pre.deps (h (str "[" package " " (pr-str (:version project)) "]"))]))
     (if-let [docs (seq (:documents project))]
       (list
        [:h2 "Topics"]
        [:ul.topics
         (for [doc docs]
           [:li (link-to (doc-filename doc) (h (:title doc)))])]))

     (when (:show-platforms? project)
       (list
         [:h2 "Platforms"]
         [:p "This project includes code for multiple platforms, please "
          [:strong "choose a platform"] " to view its documentation:"]
         [:ul
          (for [language (sorted-languages (:languages project))]
            [:li (link-to (index-filename language) (:name (language-info language)))])]))

     (when (:show-namespaces? project)
       (list
         [:h2 "Namespaces"]
         (for [namespace (sort-by :name (:namespaces project))]
           [:div.namespace
            [:h3 (link-to (ns-filename namespace) (h (:name namespace)))]
            [:div.doc (format-docstring project nil (update-in namespace [:doc] util/summary))]
            [:div.index
             [:p "Public variables and functions:"]
             (unordered-list
               (for [var (sorted-public-vars namespace)]
                 (list " " (link-to (var-uri namespace var) (h (:name var))) " ")))]])))]]))

(defmulti format-document
  "Format a document into HTML."
  (fn [project doc] (:format doc)))

(defmethod format-document :markdown [project doc]
  [:div.markdown (markdown-to-html (:content doc) project)])

(defn- document-page [project doc]
  (html5
   [:head
    default-meta
    [:title (h (:title doc))]]
   [:body
    (header project)
    (primary-sidebar project doc)
    [:div#content.document
     [:div.doc (format-document project doc)]]]))

(defn- var-usage [var]
  (for [arglist (:arglists var)]
    (list* (:name var) arglist)))

(defn- added-and-deprecated-docs [var]
  (list
   (if-let [added (:added var)]
     [:h4.added "added in " added])
   (if-let [deprecated (:deprecated var)]
     [:h4.deprecated "deprecated" (if (string? deprecated) (str " in " deprecated))])))

(defn- remove-namespaces [x namespaces]
  (if (and (symbol? x) (contains? namespaces (namespace x)))
    (symbol (name x))
    x))

(defn- normalize-types [types]
  (read-string (pr-str types)))

(defn- pprint-str [x]
  (with-out-str (pp/pprint x)))

(defn- type-sig [namespace var]
  (let [implied-namespaces #{(str (:name namespace)) "clojure.core.typed"}]
    (->> (:type-sig var)
         (normalize-types)
         (walk/postwalk #(remove-namespaces % implied-namespaces))
         (pprint-str))))

(defn- var-docs [project namespace var]
  [:div.public.anchor {:id (h (var-id (:name var)))}
   [:h3 (h (:name var))]
   (if-not (= (:type var) :var)
     [:h4.type (name (:type var))])
   (if (:dynamic var)
     [:h4.dynamic "dynamic"])

   (if (:cross-platform? project)
     (let [var-langs (get-in project [:var-langs (:name namespace) (:name var)])
           {:keys [language languages]} project]

       (for [language* (sorted-languages languages)]
         (when (contains? var-langs language*)
           (if (= language language*)
             [:h4.lang.current (:ext (language-info language*))]
             [:h4.lang (link-to (var-uri (assoc namespace :language language*) var)
                         (:ext (language-info language*)))])))))

   (added-and-deprecated-docs var)
   (if (:type-sig var)
     [:div.type-sig
      [:pre (h (type-sig namespace var))]])
   [:div.usage
    (for [form (var-usage var)]
      [:code (h (pr-str form))])]
   [:div.doc (format-docstring project namespace var)]
   (if-let [members (seq (:members var))]
     [:div.members
      [:h4 "members"]
      [:div.inner
       (let [project (dissoc project :source-uri)]
         (map (partial var-docs project namespace) members))]])
   (if (:source-uri project)
     (if (:path var)
       [:div.src-link (link-to (var-source-uri project var) "view source")]
       (println "Could not generate source link for" (:name var))))])

(defn- namespace-page [project namespace]
  (html5
   [:head
    default-meta
    [:title (h (:name namespace)) " documentation"]]
   [:body
    (header project)
    (primary-sidebar project namespace)
    (vars-sidebar namespace)
    [:div#content.namespace-docs
     [:h1#top.anchor (h (:name namespace))]
     (added-and-deprecated-docs namespace)
     [:div.doc (format-docstring project namespace namespace)]
     (for [var (sorted-public-vars namespace)]
       (var-docs project namespace var))]]))

(defn- mkdirs [output-dir & dirs]
  (doseq [dir dirs]
    (.mkdirs (io/file output-dir dir))))

(defn- cross-platform-namespaces
  [namespaces language base-language]
  (map
    #(assoc %
       :language language
       :base-language base-language)
    (get namespaces language)))

(defn- write-index [output-dir project]
  (let [{:keys [namespaces cross-platform?]} project]

    (when cross-platform?
      ;; Write an index file for each language
      (doseq [language (:languages project)]
        (let [namespaces (cross-platform-namespaces namespaces language (:base-language project))
              project
              (assoc project
                :namespaces namespaces
                :language   language
                :show-platforms?  false
                :show-namespaces? true)]
          (spit (io/file output-dir (index-filename language))
            (transform-html project (index-page project))))))

    ;; Always write a main index file
    (let [project (assoc project
                    :show-platforms?  cross-platform?
                    :show-namespaces? (not cross-platform?))]
      (spit (io/file output-dir (index-filename nil))
        (transform-html project (index-page project))))))

(defn- write-namespaces [output-dir project]
  (let [{:keys [namespaces cross-platform?]} project]

    (if cross-platform?
      ;; Write namespace files for each language
      (doseq [language (:languages project)]
        (let [namespaces (cross-platform-namespaces namespaces language (:base-language project))]
          (doseq [namespace namespaces]
            (let [project (assoc project
                            :namespaces namespaces
                            :language   language
                            :show-platforms?  false
                            :show-namespaces? true)]
              (spit (ns-filepath output-dir namespace)
                (transform-html project (namespace-page project namespace)))))))

      ;; Write namespace files for only language
      (doseq [namespace namespaces]
        (let [project (assoc project
                        :show-platforms? false
                        :show-namespaces? true)]
          (spit (ns-filepath output-dir namespace)
            (transform-html project (namespace-page project namespace))))))))

(defn- write-documents [output-dir project]
  (doseq [document (:documents project)]
    (spit (doc-filepath output-dir document)
          (transform-html project (document-page project document)))))

(defn- theme-path [theme]
  (let [theme-name (if (vector? theme) (first theme) theme)]
    (str "codox/theme/" (name theme-name))))

(defn- insert-params [theme-data theme]
  (let [params   (if (vector? theme) (or (second theme) {}) {})
        defaults (:defaults theme-data {})]
    (assert (map? params) "Theme parameters must be a map")
    (assert (map? defaults) "Theme defaults must be a map")
    (->> (dissoc theme-data :defaults)
         (walk/postwalk #(if (keyword? %) (params % (defaults % %)) %)))))

(defn- read-theme [theme]
  (some-> (theme-path theme)
          (str "/theme.edn")
          io/resource slurp
          edn/read-string
          (insert-params theme)))

(defn- make-parent-dir [file]
  (-> file io/file .getParentFile .mkdirs))

(defn- copy-resource [resource output-path]
  (io/copy (io/input-stream (io/resource resource)) output-path))

(defn- copy-theme-resources [output-dir project]
  (doseq [theme (:themes project)]
    (let [root (theme-path theme)]
      (doseq [path (:resources (read-theme theme))]
        (let [output-file (io/file output-dir path)]
          (make-parent-dir output-file)
          (copy-resource (str root "/" path) output-file))))))

(defn- apply-one-theme [project theme]
  (if-let [{:keys [transforms]} (read-theme theme)]
    (update-in project [:html :transforms] concat transforms)
    (throw (IllegalArgumentException. (format "Could not find Codox theme: %s" theme)))))

(defn- apply-theme-transforms [{:keys [themes] :as project}]
  (reduce apply-one-theme project themes))

(defn write-docs
  "Take raw documentation info and turn it into formatted HTML."
  [{:keys [output-path] :as project}]
  (let [project (apply-theme-transforms project)]
    (doto output-path
      (copy-theme-resources project)
      (write-index project)
      (write-namespaces project)
      (write-documents project))))
