#!/usr/bin/env nbb
;; loop-denchu-survey — 1 サイクル分の survey を実行して shard と evidence を書く。
;;
;;   nbb --classpath "src:../denchu/src:../org-openstreetmap-overpass/src:../com-mapillary-graph-api/src" \
;;       bin/survey.cljs --area-id "chiyoda-marunouchi" --jurisdiction JP-13 \
;;       --bbox 35.6790,139.7620,35.6830,139.7680 --sources osm --out data/
;;
;; --sources osm         Overpass のみ（認証不要）
;; --sources mapillary   Mapillary のみ（MAPILLARY_ACCESS_TOKEN が要る）
;; --sources both        両方（2 source 一致が信頼度に効く）
(ns survey
  (:require ["fs" :as fs]
            ["path" :as path]
            [clojure.string :as str]
            [com-mapillary-graph-api.fetch :as mly-fetch]
            [denchu.datoms :as denchu-datoms]
            [loop-denchu-survey.core :as survey]
            [org-openstreetmap-overpass.fetch :as osm-fetch]))

(defn- args->map [argv]
  (into {} (for [[k v] (partition 2 argv)
                 :when (str/starts-with? k "--")]
             [(keyword (subs k 2)) v])))

(defn- parse-bbox
  "--bbox south,west,north,east （Overpass の並び。人が地図から読む順）"
  [s]
  (let [[south west north east] (map js/parseFloat (str/split s #","))]
    (when (some js/isNaN [south west north east])
      (throw (ex-info "bbox must be south,west,north,east" {:given s})))
    {:south south :west west :north north :east east}))

(defn- ->mly-bbox [{:keys [south west north east]}]
  {:west west :south south :east east :north north})

(defn- now-iso [] (.toISOString (js/Date.)))

(defn- write-edn! [file data header]
  (fs/mkdirSync (path/dirname file) #js {:recursive true})
  (fs/writeFileSync file (str header "\n" (pr-str data) "\n"))
  (println "wrote" file))

(defn- collect
  "指定 source から観測を集める Promise。失敗した source は**握り潰さず**
  `:errors` に残す — 片方が落ちたのに全部揃ったように見えるのを防ぐ。"
  [bbox sources]
  (let [tasks (cond-> []
                (contains? sources :osm)
                (conj (-> (osm-fetch/fetch-poles bbox)
                          (.then (fn [r] {:source :osm :result r}))
                          (.catch (fn [e] {:source :osm :error (str e)}))))
                (contains? sources :mapillary)
                (conj (-> (mly-fetch/fetch-poles (->mly-bbox bbox))
                          (.then (fn [r] {:source :mapillary :result r}))
                          (.catch (fn [e] {:source :mapillary :error (str e)})))))]
    (-> (js/Promise.all (clj->js tasks))
        (.then (fn [rs]
                 (let [rs (js->clj rs)
                       ok (filter :result rs)
                       bad (filter :error rs)]
                   {:observations (vec (mapcat (comp :observations :result) ok))
                    :per-source (into {} (map (fn [r] [(:source r)
                                                       (dissoc (:result r) :observations)]) ok))
                    :errors (vec bad)}))))))

(defn -main [& argv]
  (let [{:keys [area-id jurisdiction bbox sources out radius-m min-confidence note]} (args->map argv)
        _ (when (or (nil? area-id) (nil? bbox) (nil? jurisdiction))
            (println "usage: survey.cljs --area-id <id> --jurisdiction <ISO-3166-2> --bbox s,w,n,e [--sources osm|mapillary|both] [--out dir]")
            (js/process.exit 2))
        bb (parse-bbox bbox)
        srcs (case (or sources "osm")
               "osm" #{:osm}
               "mapillary" #{:mapillary}
               "both" #{:osm :mapillary}
               (throw (ex-info "unknown --sources" {:given sources})))
        outdir (or out "data")
        a (survey/area {:id area-id :bbox bb :jurisdiction jurisdiction :note note})]
    (-> (collect bb srcs)
        (.then
         (fn [{:keys [observations per-source errors]}]
           (when (seq errors)
             (println "WARNING: source(s) failed —" (pr-str errors)))
           (let [generated-at (now-iso)
                 {:keys [shard evidence measurements decision]}
                 (survey/run {:areas [a]
                              :observations observations
                              :sources srcs
                              :generated-at generated-at
                              :radius-m (if radius-m (js/parseFloat radius-m) 8.0)
                              :min-confidence (if min-confidence
                                                (js/parseFloat min-confidence)
                                                survey/default-min-confidence)})
                 evidence (assoc evidence
                                 :survey/per-source per-source
                                 :survey/source-errors errors)]
             (write-edn! (path/join outdir (str "denchu-inventory-" area-id ".datoms.edn"))
                         shard
                         (str ";; 生成物 — 手で編集しない。再生成: loop-denchu-survey bin/survey.cljs\n"
                              ";; area=" area-id " jurisdiction=" jurisdiction
                              " sources=" (str/join "," (map name (sort srcs)))
                              " generated-at=" generated-at "\n"
                              ";; ⚠ この shard は上記 area の中だけを見ている。"
                              "面の外に柱が無いのではなく、見ていない。"))
             (write-edn! (path/join outdir (str "survey-evidence-" area-id ".edn"))
                         evidence
                         ";; survey の証跡 — 入力・測定・閾値・落としたものを保持する。")
             ;; 代理店・面種別の静的カタログ。area shard に混ぜると area 数だけ
             ;; 重複するので、同じ dataset の中で 1 ファイルに固定して出す。
             (write-edn! (path/join outdir "denchu-catalog.datoms.edn")
                         (denchu-datoms/catalog-shard)
                         (str ";; 生成物 — 手で編集しない。代理店/面種別の静的カタログ。\n"
                              ";; 正本は denchu.media/agencies・denchu.slot/slot-kinds。"))
             (println "observations:" (:observations measurements)
                      "poles:" (:poles measurements)
                      "accepted:" (count (:accepted decision))
                      "below-threshold:" (count (:below-threshold decision))
                      "unknown-owner:" (:poles-unknown-owner measurements)
                      "routable:" (:poles-routable measurements)))))
        (.catch (fn [e]
                  (println "survey failed:" (str e))
                  (js/process.exit 1))))))

(apply -main *command-line-args*)
