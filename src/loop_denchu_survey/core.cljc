(ns loop-denchu-survey.core
  "電柱在庫の継続 survey — observe → evaluate → decide → act → record-evidence。

  `loop-*` の役割（`manifest/repository-rules.edn` :name-prefix）は continuous
  orchestrator であって domain truth の持ち主ではない。したがってこの ns は:

  - **柱の定義・束ね方・信頼度を持たない** → `denchu.pole` に委譲
  - **窓口・料金・法令を持たない** → `denchu.media` / `pricing` / `facts` に委譲
  - **API の作法を持たない** → `org-openstreetmap-overpass` /
    `com-mapillary-graph-api` に委譲

  ここが所有するのは『どの面をどの順で見て、何を証跡として残すか』だけ。

  **カバレッジの申告がこの loop の成果物の一部である。** 見た面の外に柱が
  無いのではなく、見ていないだけ — その差を出力に必ず書く。"
  (:require [denchu.datoms :as datoms]
            [denchu.media :as media]
            [denchu.pole :as pole]))

(defn area
  "survey 対象面。`:id` は人が読む名前（行政区名など）で、`:bbox` が実体。
  `:jurisdiction` は ISO 3166-2 相当を**呼び出し側が宣言する** — 座標から
  逆引きして推測しない。"
  [{:keys [id bbox jurisdiction note]}]
  {:area/id id :area/bbox bbox :area/jurisdiction jurisdiction :area/note note})

;; ── evaluate ────────────────────────────────────────────────────────

(defn- in-bbox?
  [{:keys [south west north east]} {:keys [pole/lat pole/lon]}]
  (and (<= south lat north) (<= west lon east)))

(defn stamp-jurisdiction
  "柱に管轄を刻む。**座標から逆引きしない** —— survey が宣言した area の
  bbox に入っているかだけを見て、その area の `:area/jurisdiction` を写す。
  どの area にも入らない柱には付けない（推測しない）。

  管轄が付くと `denchu.media/contact-route` が区域から候補所有者を出せる
  ようになり、所有者未確定でも問い合わせを組める（`denchu.area` の設計）。"
  [poles areas]
  (mapv (fn [p]
          (if-let [a (some (fn [a] (when (and (:area/jurisdiction a)
                                              (in-bbox? (:area/bbox a) p))
                                     a))
                           areas)]
            (assoc p :pole/jurisdiction (:area/jurisdiction a)
                     :pole/survey-area (:area/id a))
            p))
        poles))

(defn evaluate
  "観測列 → 柱列 + 測定値。source ごとの寄与を残すのは、後から
  『Mapillary を足して何本増えたか』を答えられるようにするため。"
  [observations {:keys [radius-m areas] :or {radius-m 8.0}}]
  (let [{:keys [poles rejected]} (pole/fuse observations {:radius-m radius-m})
        poles (stamp-jurisdiction poles (or areas []))
        by-source (frequencies (map :obs/source observations))
        multi (count (filter #(> (count (:pole/sources %)) 1) poles))
        unknown-owner (count (filter #(= :unknown (:pole/owner %)) poles))
        route-status (frequencies (map #(:route/status (media/contact-route %)) poles))]
    {:poles poles
     :rejected rejected
     :measurements
     {:observations (count observations)
      :observations-by-source by-source
      :poles (count poles)
      :poles-multi-source multi
      :poles-unknown-owner unknown-owner
      ;; 所有者が確定していて窓口も分かる柱
      :poles-routable (get route-status :routable 0)
      ;; 所有者は未確定だが、区域から候補が出るので問い合わせは組める柱
      :poles-candidate-routable (get route-status :candidate-by-area 0)
      :poles-by-route-status route-status
      :rejected-observations (count rejected)}}))

;; ── decide ──────────────────────────────────────────────────────────

(def ^:const default-min-confidence
  "在庫候補として台帳に載せる下限。下回った柱は捨てずに `:below-threshold`
  として数える — 消えると『調べた結果いなかった』に化けるため。"
  0.45)

(defn decide
  "どの柱を在庫候補として採るか。判断は信頼度のみで、所有者不明でも
  落とさない（所有者は後から埋まる。落とすと二度と探されない）。"
  [poles {:keys [min-confidence] :or {min-confidence default-min-confidence}}]
  (let [{accepted true below false} (group-by #(>= (:pole/confidence %) min-confidence) poles)]
    {:accepted (vec (or accepted []))
     :below-threshold (vec (or below []))
     :min-confidence min-confidence}))

;; ── act ─────────────────────────────────────────────────────────────

(defn shard
  "採った柱 → datom 面に載る entity map の vector（`denchu.datoms` に委譲）。"
  [{:keys [accepted areas sources rejected generated-at]}]
  (datoms/inventory-shard {:poles accepted
                           :areas (mapv :area/id areas)
                           :sources sources
                           :rejected rejected
                           :generated-at generated-at}))

;; ── record-evidence ─────────────────────────────────────────────────

(defn evidence
  "1 回の survey の証跡。**入力・測定・閾値・落としたもの**を全部持つので、
  同じ入力から同じ shard が再現できるかを後から検査できる。"
  [{:keys [areas sources measurements decision generated-at radius-m]}]
  {:survey/generated-at generated-at
   :survey/areas (mapv (fn [a] (select-keys a [:area/id :area/bbox :area/jurisdiction])) areas)
   :survey/sources (vec (sort (map name sources)))
   :survey/fuse-radius-m radius-m
   :survey/min-confidence (:min-confidence decision)
   :survey/measurements measurements
   :survey/accepted (count (:accepted decision))
   :survey/below-threshold (count (:below-threshold decision))
   :survey/below-threshold-ids (mapv :pole/id (:below-threshold decision))
   :survey/media-coverage (media/coverage)
   :survey/honesty
   (str "この survey は列挙した area の中だけを見ている。area の外に柱が"
        "無いのではなく、見ていない。閾値未満の柱は捨てずに id を残してある。"
        "掲出可否は全件 unknown —— 所有者/代理店の回答だけがそれを動かせる。")})

;; ── 1 回分 ──────────────────────────────────────────────────────────

(defn run
  "観測が揃っている前提で 1 サイクル回す純関数。ネットワークは CLI 側。
  `{:areas [...] :observations [...] :sources #{:osm} :generated-at \"...\"}`"
  [{:keys [areas observations sources generated-at radius-m min-confidence]
    :or {radius-m 8.0 min-confidence default-min-confidence}}]
  (let [{:keys [poles rejected measurements]} (evaluate observations {:radius-m radius-m
                                                                      :areas areas})
        decision (decide poles {:min-confidence min-confidence})]
    {:shard (shard {:accepted (:accepted decision) :areas areas :sources sources
                    :rejected (count rejected) :generated-at generated-at})
     :evidence (evidence {:areas areas :sources sources :measurements measurements
                          :decision decision :generated-at generated-at
                          :radius-m radius-m})
     :decision decision
     :measurements measurements}))
