(defproject com.taoensso.forks/codox "0.10.11"
  :description "Generate documentation from Clojure source files"
  :url "https://github.com/weavejester/codox"
  :scm {:dir ".."}
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [org.clojure/tools.namespace "1.4.4"]
                 [org.clojure/clojurescript "1.11.60"]
                 [hiccup "1.0.5"]
                 [enlive "1.1.6"]
                 [com.vladsch.flexmark/flexmark "0.64.8"]
                 [com.vladsch.flexmark/flexmark-profile-pegdown "0.64.8"]
                 [com.vladsch.flexmark/flexmark-util-misc"0.64.8"]
                 [org.ow2.asm/asm-all "5.2"]])
