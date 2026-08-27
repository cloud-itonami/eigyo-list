(ns eigyo-list.lead
  "観測 → リード。**リードは『連絡できる面を OSM 自身が公表している事業者』**で
  あって、営業してよい相手ではない。

  ## 名簿と接触は別の層

  この repo が答えるのは『誰が居て、何屋で、どの面が公表されているか』まで。
  **接触の可否は答えない** —— それは `kotoba-lang/noren` の
  `noren.prospect/eligible` と `noren.governor`（特定電子メール法 3 条 1 項の
  根拠、受信拒否、出所 URL と観測日）が持っており、この名簿から 1 件取り出して
  接触する経路は必ずそこを通る。名簿に載っていることを許可と読まない。

  ## 連絡先の**値**はここから先へ出さない

  リードが持つのは `has_email` / `has_phone` という**有無**と、`site`（事業者の
  web 面）と `osm_url` だけで、メールアドレスと電話番号の文字列は観測の境界で
  捨てる。OSM 側で公開されている事実ではあるが、**名簿の形に集めることは
  別の行為**で、集めた側の判断が要る。1 件について値が要るときは `osm_url` から
  取り直す —— そのとき値は今日の値になり、経路は `noren.governor` を通る。

  だからこの表は**ターゲティングの索引であって連絡先データベースではない**。
  「メールアドレスを一括で出したい」に対する答えは無い。

  ## 落ちたものを数える

  `observation->lead` は `nil` を返さない。落ちた観測には理由が付く
  （`{:refused :no-contact-channel}`）。理由なしで消えると、『この街に事業者が
  居ない』と『この街の事業者を写せなかった』が同じ 0 になる。"
  (:require [clojure.string :as str]
            [eigyo-list.crosswalk :as cw]))

(def ^:private site-keys ["website" "contact:website" "url" "contact:url"])
(def ^:private email-keys ["email" "contact:email"])
(def ^:private phone-keys ["phone" "contact:phone" "contact:mobile"])

(defn- first-tag [tags ks] (some (fn [k] (let [v (get tags k)] (when (seq (str v)) v))) ks))

(defn- http-url
  "`website` は `www.example.com` や `mailto:` や壊れた値が入っていることがある。
  **直せる形に整えず、http(s) で始まるものだけを通す** —— 補って通すと、
  補い方の誤りが『その事業者のサイト』として名簿に載る。"
  [v]
  (when (and v (re-find #"(?i)^https?://\S+$" (str/trim (str v)))) (str/trim (str v))))

(defn chain-outlet?
  "チェーンの 1 店舗か。**OSM 自身の宣言で判定する**（`brand:wikidata` / `brand` /
  `operator:wikidata`）—— 店名から推測しない。`noren.discovery/chain-outlet?` と
  同じ判定で、あちらは候補から外し、ここは**印を付けて残す**。営業の相手が
  本部になるだけで、リードでなくなるわけではない。"
  [tags]
  (boolean (or (get tags "brand:wikidata") (get tags "brand") (get tags "operator:wikidata"))))

(defn observation->lead
  "観測 → `{:lead/…}` か `{:refused <理由>}`。`cell` はその観測が取れた区画の宣言。"
  [{:obs/keys [source-id lat lon tags evidence-url] :as _obs} cell]
  (let [tags (or tags {})
        name (some-> (or (get tags "name") (get tags "operator")) str/trim not-empty)
        {:keys [isic isic-key isic-value]} (cw/tags->isic tags)
        site (http-url (first-tag tags site-keys))
        ;; 値は束縛するが**保存しない**。有無だけを持ち出す（ns docstring）。
        email? (boolean (first-tag tags email-keys))
        phone? (boolean (first-tag tags phone-keys))]
    (cond
      (nil? isic) {:refused :isic-not-declared :declared (cw/tags->declared-key tags)}
      (nil? name) {:refused :no-name :isic isic}
      (not (or site email? phone?)) {:refused :no-contact-channel :isic isic}
      :else
      {:lead/id source-id
       :lead/name name
       :lead/isic isic
       :lead/isic-key isic-key
       :lead/isic-value isic-value
       :lead/site site
       :lead/has-email? email?
       :lead/has-phone? phone?
       :lead/chain? (chain-outlet? tags)
       :lead/brand (get tags "brand")
       :lead/operator (get tags "operator")
       :lead/opening-hours? (boolean (get tags "opening_hours"))
       :lead/city (or (get tags "addr:city") (get tags "addr:suburb"))
       :lead/country (or (get tags "addr:country") (:cell/country cell))
       :lead/lat (when (number? lat) (/ (Math/round (* 1e5 (double lat))) 1e5))
       :lead/lon (when (number? lon) (/ (Math/round (* 1e5 (double lon))) 1e5))
       :lead/osm-url evidence-url
       :lead/cell (:cell/id cell)})))

(defn observations->leads
  "観測列 → `{:leads [...] :refused {理由 件数} :declared-misses {\"shop=x\" n}}`。"
  [observations cell]
  (let [rs (map #(observation->lead % cell) observations)
        leads (vec (keep #(when (:lead/id %) %) rs))
        refused (frequencies (keep :refused rs))
        misses (frequencies (keep :declared (filter #(= :isic-not-declared (:refused %)) rs)))]
    {:leads leads
     :refused refused
     :declared-misses (into (sorted-map) misses)}))

;; ── blueprint との突き合わせ ──────────────────────────────────────────────

(defn blueprint-for
  "ISIC → `cloud-itonami-isic-NNNN`。**在る符号だけ返す。**

  `blueprints` は `data/blueprints.edn`（west.yml から生成、出所つき）。名前から
  組み立てて『在るはず』としない —— 存在しない repo の URL を営業資料に載せると、
  相手が最初に押すリンクが 404 になる。"
  [isic blueprints]
  (when (contains? blueprints isic) (str "cloud-itonami-isic-" isic)))

(defn lead->row
  "リード → 表の 1 行（全列 string / nil）。

  **連絡先の値の列は無い**（`has_email` / `has_phone` だけ）。lake 側と git 側で
  同じ行が出る —— 片方だけが値を持つ形にすると、どちらを配ってよいかが
  読み手に見えなくなる。値が要る側は `osm_url` から 1 件ずつ取り直す。"
  [lead {:keys [blueprints harvested-at]}]
  (let [isic (:lead/isic lead)
        bp (blueprint-for isic blueprints)]
    {"lead_id" (:lead/id lead)
             "name" (:lead/name lead)
             "isic" isic
             "isic_section" (cw/isic->section isic)
             "isic_key" (:lead/isic-key lead)
             "isic_value" (:lead/isic-value lead)
             "blueprint_repo" bp
             "blueprint_url" (when bp (str "https://github.com/cloud-itonami/" bp))
             "site" (:lead/site lead)
             "has_site" (str (boolean (:lead/site lead)))
             "has_email" (str (boolean (:lead/has-email? lead)))
             "has_phone" (str (boolean (:lead/has-phone? lead)))
             "chain" (str (boolean (:lead/chain? lead)))
             "brand" (:lead/brand lead)
             "operator" (:lead/operator lead)
             "has_opening_hours" (str (boolean (:lead/opening-hours? lead)))
             "city" (:lead/city lead)
             "country" (:lead/country lead)
             "lat" (some-> (:lead/lat lead) str)
             "lon" (some-> (:lead/lon lead) str)
             "cell" (:lead/cell lead)
             "osm_url" (:lead/osm-url lead)
             "source" "openstreetmap"
             "license" "ODbL-1.0"
             "harvested_at" harvested-at}))
