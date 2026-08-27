(ns eigyo-list.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [eigyo-list.crosswalk :as cw]
            [eigyo-list.lead :as lead]
            [eigyo-list.coverage :as cov]))

;; ── crosswalk ─────────────────────────────────────────────────────────────

(deftest tags-decide-the-isic-not-the-selector
  (is (= "5610" (:isic (cw/tags->isic {"amenity" "restaurant"}))))
  (is (= "4721" (:isic (cw/tags->isic {"shop" "bakery"}))))
  (testing "shop と craft の両方を持つパン屋は shop 側（売る面を持つ）"
    (is (= "4721" (:isic (cw/tags->isic {"shop" "bakery" "craft" "bakery"}))))
    (is (= "shop" (:isic-key (cw/tags->isic {"shop" "bakery" "craft" "bakery"})))))
  (testing "healthcare は amenity より細かいので先に見る"
    (is (= "8690" (:isic (cw/tags->isic {"amenity" "clinic"
                                         "healthcare" "physiotherapist"}))))))

(deftest unmapped-values-return-nil-and-stay-visible
  (is (nil? (cw/tags->isic {"shop" "spaceship_parts"})))
  (is (= "shop=spaceship_parts" (cw/tags->declared-key {"shop" "spaceship_parts"})))
  (testing "shop=yes は『店だが業種は宣言されていない』。47 に丸めない"
    (is (nil? (cw/tags->isic {"shop" "yes"})))))

(deftest amenity-is-value-scoped-but-shop-is-key-scoped
  (let [sels (cw/selectors)
        by-key (into {} (map (fn [s] [(first s) (second s)]) sels))]
    (testing "shop はキー存在形 —— 表に無い値も返らせて数える"
      (is (contains? by-key "shop"))
      (is (nil? (get by-key "shop"))))
    (testing "amenity は値集合形 —— ベンチもゴミ箱も amenity だから"
      (is (seq (:any-of (get by-key "amenity"))))
      (is (some #{"restaurant"} (:any-of (get by-key "amenity"))))
      (is (not (some #{"bench"} (:any-of (get by-key "amenity"))))))))

(deftest crosswalk-only-targets-existing-blueprint-granularity
  (testing "写した符号はすべて 4 桁（blueprint repo の粒度）"
    (let [codes (into #{} (remove nil?)
                      (mapcat vals [cw/shop->isic cw/craft->isic cw/office->isic
                                    cw/amenity->isic cw/tourism->isic
                                    cw/leisure->isic cw/healthcare->isic]))]
      (is (pos? (count codes)))
      (is (every? #(re-matches #"[0-9]{4}" %) codes)))))

;; ── lead ──────────────────────────────────────────────────────────────────

(def cell {:cell/id "jp-test" :cell/country "JP" :cell/center [35.69 139.70]})

(defn- obs [tags]
  {:obs/source-id "node/1" :obs/lat 35.69 :obs/lon 139.70 :obs/tags tags
   :obs/evidence-url "https://www.openstreetmap.org/node/1"})

(deftest a-poi-without-a-contact-channel-is-refused-with-a-reason
  (let [r (lead/observation->lead (obs {"amenity" "restaurant" "name" "そば処"}) cell)]
    (is (= :no-contact-channel (:refused r)))
    (is (nil? (:lead/id r)))))

(deftest unmapped-tags-are-refused-as-not-declared-not-as-absent
  (let [r (lead/observation->lead (obs {"shop" "spaceship_parts" "name" "X"
                                        "website" "https://x.test/"}) cell)]
    (is (= :isic-not-declared (:refused r)))
    (is (= "shop=spaceship_parts" (:declared r)))))

(deftest a-lead-needs-a-name
  (is (= :no-name (:refused (lead/observation->lead
                             (obs {"amenity" "cafe" "website" "https://c.test/"}) cell)))))

(deftest contact-values-never-leave-the-observation-boundary
  (testing "メールアドレスと電話番号は**値を持ち出さない**。有無だけ"
    (let [l (lead/observation->lead
             (obs {"amenity" "restaurant" "name" "まる"
                   "contact:email" "info@maru.test" "phone" "+81-3-0000-0000"}) cell)
          row (lead/lead->row l {:blueprints #{"5610"} :harvested-at "2026-08-27"})]
      (is (true? (:lead/has-email? l)))
      (is (true? (:lead/has-phone? l)))
      (is (not-any? #(str/includes? (pr-str %) "info@maru.test") (vals l)))
      (is (nil? (get row "email")))
      (is (nil? (get row "phone")))
      (is (= "true" (get row "has_email")))
      (testing "行の全値を通しても、値そのものは 1 つも出ない"
        (is (not (str/includes? (pr-str row) "info@maru.test")))
        (is (not (str/includes? (pr-str row) "0000-0000")))))))

(deftest a-broken-website-tag-is-not-repaired
  (testing "www. 始まりを http:// で補って通さない —— 補い方の誤りが名簿に載る"
    (is (= :no-contact-channel
           (:refused (lead/observation->lead
                      (obs {"amenity" "cafe" "name" "C" "website" "www.c.test"}) cell))))))

(deftest chains-are-flagged-by-osm-declaration-not-by-the-name
  (is (true? (lead/chain-outlet? {"brand:wikidata" "Q37158"})))
  (is (false? (lead/chain-outlet? {"name" "Starbucks っぽい何か"}))))

(deftest blueprint-is-only-claimed-when-the-repo-exists
  (is (= "cloud-itonami-isic-5610" (lead/blueprint-for "5610" #{"5610"})))
  (is (nil? (lead/blueprint-for "5610" #{})))
  (testing "行に載る URL は blueprint が在るときだけ"
    (let [row (lead/lead->row {:lead/id "node/1" :lead/isic "9999" :lead/name "X"}
                              {:blueprints #{"5610"} :harvested-at "x"})]
      (is (nil? (get row "blueprint_repo")))
      (is (nil? (get row "blueprint_url"))))))

;; ── coverage ──────────────────────────────────────────────────────────────

(deftest a-cell-at-a-high-latitude-gets-a-wider-longitude-box
  (let [sg (cov/cell->bbox {:cell/center [1.28 103.85]})
        se (cov/cell->bbox {:cell/center [59.33 18.06]})
        w (fn [b] (- (:east b) (:west b)))]
    (is (> (w se) (* 1.9 (w sg))) "経度 1 度は緯度で長さが違う")
    (testing "面積は揃う（件数の比較が緯度の関数にならない）"
      (is (< (Math/abs (- (cov/cell-km2 {:cell/center [1.28 103.85]})
                          (cov/cell-km2 {:cell/center [59.33 18.06]})))
             3)))))

(deftest planned-is-not-zero
  (let [c {:cell/id "x" :cell/country "JP" :cell/center [35.0 139.0]}
        planned (cov/coverage-row c {:status :planned})
        measured (cov/coverage-row c {:status :measured :raw-count 100 :leads 0
                                      :refused {} :at "2026-08-27"})]
    (is (= "planned" (get planned "status")))
    (is (nil? (get planned "leads")) "引いていない区画は 0 件ではなく空")
    (is (= "0" (get measured "leads")) "引いて 0 件だった区画は 0 と書く")))

(deftest a-failed-cell-is-not-a-measured-one
  (let [rows [(cov/coverage-row {:cell/id "a" :cell/center [0 0]} {:status :measured :leads 3 :at "t"})
              (cov/coverage-row {:cell/id "b" :cell/center [0 0]} {:status :failed :error "504"})
              (cov/coverage-row {:cell/id "c" :cell/center [0 0]} {:status :planned})]
        s (cov/summarize rows)]
    (is (= {:cells 3 :measured 1 :failed 1 :planned 1 :disabled 0 :leads 3} s))
    (is (str/includes? (cov/plan-note rows) "FAILED"))
    (is (str/includes? (cov/plan-note rows) "not yet read"))))
