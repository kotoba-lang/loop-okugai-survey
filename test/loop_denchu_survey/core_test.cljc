(ns loop-denchu-survey.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [loop-denchu-survey.core :as survey]))

(defn- obs [source id lat lon & [tags]]
  {:obs/source source :obs/source-id id :obs/lat lat :obs/lon lon
   :obs/kind :utility-pole :obs/tags (or tags {})})

(def areas [(survey/area {:id "test-area" :jurisdiction "JP-13"
                          :bbox {:south 35.68 :west 139.76 :north 35.69 :east 139.77}})])

(def observations
  [(obs :osm "node/1" 35.6812 139.7671 {"operator" "東京電力パワーグリッド"})
   (obs :mapillary "mly-1" 35.681205 139.767105)
   (obs :osm "node/2" 35.6850 139.7680)
   (obs :mapillary "mly-2" 35.6860 139.7690)])

(deftest evaluate-counts-source-contribution
  (let [{:keys [poles measurements]} (survey/evaluate observations {})]
    (is (= 3 (count poles)))
    (is (= 4 (:observations measurements)))
    (is (= {:osm 2 :mapillary 2} (:observations-by-source measurements)))
    (is (= 1 (:poles-multi-source measurements)))
    (testing "所有者不明の柱を数える（隠さない）"
      (is (= 2 (:poles-unknown-owner measurements))))))

(deftest decide-does-not-delete-below-threshold-poles
  (let [{:keys [poles]} (survey/evaluate observations {})
        d (survey/decide poles {:min-confidence 0.9})]
    (is (= 1 (count (:accepted d))))
    (is (= 2 (count (:below-threshold d))))
    (testing "落とした柱の id は evidence に残る"
      (let [e (survey/evidence {:areas areas :sources #{:osm :mapillary}
                                :measurements {} :decision d
                                :generated-at "2026-08-04" :radius-m 8.0})]
        (is (= 2 (:survey/below-threshold e)))
        (is (= 2 (count (:survey/below-threshold-ids e))))))))

(deftest unknown-owner-poles-are-kept
  (testing "所有者不明で落とすと二度と探されない"
    (let [{:keys [poles]} (survey/evaluate [(obs :osm "node/9" 35.70 139.80)] {})
          d (survey/decide poles {})]
      (is (= 1 (count (:accepted d))))
      (is (= :unknown (:pole/owner (first (:accepted d))))))))

(deftest run-produces-a-shard-with-coverage
  (let [{:keys [shard evidence]} (survey/run {:areas areas
                                              :observations observations
                                              :sources #{:osm :mapillary}
                                              :generated-at "2026-08-04T00:00:00Z"})
        cov (first (filter :denchu.coverage/poles shard))]
    (is (seq shard))
    (is (= ["test-area"] (:denchu.coverage/areas cov)))
    (is (= ["mapillary" "osm"] (:denchu.coverage/sources cov)))
    (is (seq (:survey/honesty evidence)))
    (testing "掲出可否は全件 unknown"
      (is (every? #(= "unknown" (:pole/ad-eligible %)) (filter :pole/id shard))))
    (testing "代理店カタログは area shard に混ぜない"
      (is (empty? (filter :agency/id shard))))))

(deftest jurisdiction-is-stamped-from-the-declared-area-not-guessed
  (let [{:keys [poles measurements]} (survey/evaluate observations {:areas areas})]
    (is (every? #(= "JP-13" (:pole/jurisdiction %)) poles))
    (is (every? #(= "test-area" (:pole/survey-area %)) poles))
    (testing "管轄が付くと所有者未確定でも問い合わせ先候補が出る"
      ;; 3 本のうち 1 本だけ operator タグを持つ = routable、残り 2 本が候補経路
      (is (= 1 (:poles-routable measurements)))
      (is (= 2 (:poles-candidate-routable measurements)))
      (is (= 2 (:poles-unknown-owner measurements)))
      (is (zero? (get (:poles-by-route-status measurements) :unknown-owner 0))))))

(deftest poles-outside-every-declared-area-get-no-jurisdiction
  (testing "bbox に入らない柱に管轄を付けない（逆ジオコードで推測しない）"
    (let [far (obs :osm "node/99" 10.0 10.0)
          {:keys [poles]} (survey/evaluate [far] {:areas areas})]
      (is (nil? (:pole/jurisdiction (first poles)))))))

(deftest run-is-deterministic
  (let [opts {:areas areas :observations observations :sources #{:osm :mapillary}
              :generated-at "2026-08-04T00:00:00Z"}]
    (is (= (:shard (survey/run opts)) (:shard (survey/run opts))))))
