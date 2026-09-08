(ns eigyo-list.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.lang.text :as str]
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

;; ── receipt から育てた分（2026-08-27）──────────────────────────────────────

(deftest values-added-from-receipts-are-mapped
  (testing "初回 1 周の declared-misses 上位が実際に写るようになった"
    (is (= "4721" (:isic (cw/tags->isic {"shop" "pastry"}))))
    (is (= "4742" (:isic (cw/tags->isic {"shop" "telecommunication"}))))
    (is (= "9420" (:isic (cw/tags->isic {"office" "union"}))))
    (is (= "7420" (:isic (cw/tags->isic {"craft" "photographic_laboratory"})))))
  (testing "宣言が『店だが業種は言っていない』ものは写さないまま"
    (is (nil? (cw/tags->isic {"shop" "yes"})))
    (is (nil? (cw/tags->isic {"shop" "vacant"})))
    (is (nil? (cw/tags->isic {"office" "vacant"})))
    (is (nil? (cw/tags->isic {"healthcare" "yes"})))))

(deftest diplomatic-is-foreign-affairs-not-public-order
  (testing "8423 は公共の秩序・安全。大使館・領事館は 8421（対外関係）"
    (is (= "8421" (:isic (cw/tags->isic {"office" "diplomatic"}))))
    (is (= "8423" (:isic (cw/tags->isic {"office" "police"}))))))

(deftest a-group-repo-is-not-claimed-as-the-segments-blueprint
  (testing "5613（持ち帰り飲食）に 4 桁の repo は無いが親群 561 は在る"
    (is (= ["cloud-itonami-isic-561" :group] (lead/nearest-blueprint "5613" #{"561" "5610"})))
    (is (nil? (lead/blueprint-for "5613" #{"561" "5610"})))
    (let [row (lead/lead->row {:lead/id "n/1" :lead/isic "5613" :lead/name "X"}
                              {:blueprints #{"561" "5610"} :harvested-at "t"})]
      (is (nil? (get row "blueprint_repo")) "群の repo を exact の列に書かない")
      (is (= "group" (get row "blueprint_match")))
      (is (= "cloud-itonami-isic-561" (get row "nearest_blueprint_repo")))))
  (testing "どの桁にも無ければ none"
    (is (= [nil :none] (lead/nearest-blueprint "9999" #{"5610"})))))

(deftest a-failed-cell-records-the-status-not-just-that-it-failed
  (testing "sci が ex-info を包むので、status は cause 側に在る"
    (let [inner (ex-info "overpass request failed" {:status 429 :endpoint "x"})
          wrapped (ex-info "overpass request failed" {:type :sci/error} inner)]
      (is (re-find #"http 429" (cov/error-detail wrapped)))
      (is (re-find #"http 429" (cov/error-detail inner)))))
  (testing "status がどこにも無ければ、在ることにしない"
    (is (re-find #"no status recovered|boom"
                 (cov/error-detail (ex-info "boom" {}))))))

(deftest isic-titles-say-which-revision-they-came-from
  (let [classes {"5610" {:rev5? true :title "Restaurants..." :rev4-mirror? true :mirror-title "Restaurants..."}
                 "9602" {:rev4-mirror? true :mirror-title "Hairdressing and other beauty treatment"}
                 "8559" {:rev5? true :title "Other education n.e.c."}}]
    (is (= "both" (:revision (lead/isic-facts "5610" classes))))
    (is (= "rev4-mirror" (:revision (lead/isic-facts "9602" classes))))
    (is (= "rev5" (:revision (lead/isic-facts "8559" classes))))
    (testing "mirror にしか無い符号の題名は mirror から取る（そうと分かる形で）"
      (is (= "Hairdressing and other beauty treatment" (:title (lead/isic-facts "9602" classes)))))
    (testing "権威に無い符号は none。空欄にしない"
      (is (= "none" (:revision (lead/isic-facts "5613" classes)))))
    (testing "権威を読んでいなければ unverified —— 『照合して分からなかった』と別"
      (is (= "unverified" (:revision (lead/isic-facts "5610" nil)))))))

;; ── 同じ店が 2 つの element として立っている分 ────────────────────────────

(defn- l [id name lat lon tags]
  {:lead/id id :lead/name name :lead/isic "5610" :lead/lat lat :lead/lon lon
   :lead/tag-count tags})

(deftest the-building-way-and-the-poi-node-are-one-business
  (let [[kept dropped] (lead/dedupe-leads
                        [(l "node/1" "そば処まる" 35.6800 139.7600 9)
                         (l "way/2"  "そば処まる" 35.68005 139.76002 4)])]
    (is (= 1 (count kept)))
    (is (= 1 dropped))
    (testing "残すのはタグの多いほう（連絡先を持っているのはたいていそちら）"
      (is (= "node/1" (:lead/id (first kept)))))))

(deftest chain-branches-are-not-one-business
  (let [[kept dropped] (lead/dedupe-leads
                        [(l "node/1" "スターバックス" 35.6800 139.7600 8)
                         (l "node/2" "スターバックス" 35.6830 139.7640 8)])]
    (is (= 2 (count kept)) "300 m 離れた同名は別の店")
    (is (zero? dropped))))

(deftest a-dropped-duplicate-is-counted-not-silently-removed
  (let [obs (fn [id lat lon extra]
              {:obs/source-id id :obs/lat lat :obs/lon lon
               :obs/evidence-url (str "https://www.openstreetmap.org/" id)
               :obs/tags (merge {"amenity" "restaurant" "name" "まる"
                                 "website" "https://maru.test/"} extra)})
        r (lead/observations->leads
           [(obs "node/1" 35.68 139.76 {"phone" "1"})
            (obs "way/2" 35.68001 139.76001 {})] cell)]
    (is (= 1 (count (:leads r))))
    (is (= 1 (get-in r [:refused :duplicate-of-another-element]))
        "畳んだことが receipt に出る —— 『居なかった』と区別できる形で")))

(deftest freshness-and-address-are-carried
  (let [o {:obs/source-id "node/9" :obs/lat 35.68 :obs/lon 139.76
           :obs/evidence-url "u" :obs/osm-version 7
           :obs/osm-timestamp "2015-06-01T00:00:00Z"
           :obs/tags {"amenity" "cafe" "name" "C" "website" "https://c.test/"
                      "addr:street" "本町" "addr:housenumber" "1-2"}}
        row (lead/lead->row (lead/observation->lead o cell)
                            {:blueprints #{"5610"} :harvested-at "t"})]
    (is (= "2015-06-01T00:00:00Z" (get row "osm_last_edit")))
    (is (= "7" (get row "osm_version")))
    (is (= "true" (get row "has_addr")))))
