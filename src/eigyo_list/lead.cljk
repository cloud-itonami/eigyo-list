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
  (:require [kotoba.lang.text :as str]
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
       :lead/tag-count (count tags)
       :lead/osm-version (:obs/osm-version _obs)
       :lead/osm-last-edit (:obs/osm-timestamp _obs)
       :lead/has-addr? (boolean (and (get tags "addr:street")
                                     (or (get tags "addr:housenumber")
                                         (get tags "addr:housename"))))
       :lead/cell (:cell/id cell)})))

(def ^:private dup-metres 40)

(defn- metres-apart
  "緯度経度の差 → おおよその距離 m。**厳密である必要はない** —— 40 m という
  閾値自体が経験的で、Haversine にしても閾値の恣意性は消えない。"
  [a b]
  (let [dlat (- (:lead/lat a) (:lead/lat b))
        dlon (* (- (:lead/lon a) (:lead/lon b))
                (Math/cos (* (/ Math/PI 180) (:lead/lat a))))]
    (* 111320 (Math/sqrt (+ (* dlat dlat) (* dlon dlon))))))

(defn- richer
  "同じ店の 2 つの element のうち残すほう。**タグの多いほう** —— 建物の way と
  その中の POI node が両方立っていることが実際に多く、連絡先を持っているのは
  たいてい node のほうだが、常にではない。同数なら id 文字列で決める（決定的）。"
  [a b]
  (let [ta (:lead/tag-count a 0) tb (:lead/tag-count b 0)]
    (cond (> ta tb) a (< ta tb) b
          (neg? (compare (:lead/id a) (:lead/id b))) a :else b)))

(defn dedupe-leads
  "同じ事業者が 2 つの element として立っているぶんを畳む。

  **名前と業種が同じで、40 m 以内**を同一とみなす。OSM では建物の way と
  その中の POI node が両方 tag を持つことが多く、畳まないと**同じ店を 2 回
  数える**（営業リストとしては同じ相手に 2 回当たることになる）。

  返すのは `[残ったもの 落とした数]`。**落とした数を返すのは、畳んだこと自体を
  receipt に出すため** —— 件数が減った理由が『居なかった』なのか『畳んだ』
  なのかは、出力から区別できなければならない。

  チェーンの支店は名前が同じでも 40 m 離れているので畳まれない。"
  [leads]
  (let [by (group-by (juxt :lead/name :lead/isic) leads)]
    (reduce (fn [[kept dropped] [_ group]]
              (if (= 1 (count group))
                [(conj kept (first group)) dropped]
                (let [clusters (reduce (fn [cs l]
                                         (if-let [i (first (keep-indexed
                                                            (fn [i c] (when (< (metres-apart (first c) l) dup-metres) i))
                                                            cs))]
                                           (update cs i conj l)
                                           (conj cs [l])))
                                       [] (sort-by :lead/id group))]
                  [(into kept (map #(reduce richer %) clusters))
                   (+ dropped (- (count group) (count clusters)))])))
            [[] 0]
            by)))

(defn observations->leads
  "観測列 → `{:leads [...] :refused {理由 件数} :declared-misses {\"shop=x\" n}}`。"
  [observations cell]
  (let [rs (map #(observation->lead % cell) observations)
        [leads dropped] (dedupe-leads (vec (keep #(when (:lead/id %) %) rs)))
        leads (vec (sort-by :lead/id leads))
        refused (cond-> (frequencies (keep :refused rs))
                  (pos? dropped) (assoc :duplicate-of-another-element dropped))
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

(defn nearest-blueprint
  "4 桁に repo が無ければ 3 桁（群）→ 2 桁（部門）へ落ちる。返すのは
  `[repo-name match]`、match は `:exact` / `:group` / `:division` / `:none`。

  **`blueprint_repo` に群の repo を書かない。** 書くと『この業種の blueprint が
  在る』と読めてしまう。実例: ISIC 5613（持ち帰り飲食、実測 6,567 件）に 4 桁の
  repo は無いが、親群の `cloud-itonami-isic-561` は在る —— 提案できるものが
  在ることと、その業種の blueprint が在ることは別の主張なので、列を分ける。

  ISIC の符号は桁が階層そのものなので、ここは表ではなく導出でよい。"
  [isic blueprints]
  (let [s (str isic)
        ;; `at` という名前にしてある。`try` にすると **special form が優先され**、
        ;; `(try s :exact)` が「s を評価して :exact を返す」になる（実測 2026-08-27、
        ;; テストが 5613 に対して :exact を返して落ちた）。
        at (fn [code m] (when (contains? blueprints code) [(str "cloud-itonami-isic-" code) m]))]
    (or (at s :exact)
        (when (<= 4 (count s)) (at (subs s 0 3) :group))
        (when (<= 3 (count s)) (at (subs s 0 2) :division))
        [nil :none])))

(defn isic-facts
  "符号 → `{:title .. :revision ..}`。`classes` は `data/isic.edn` の `:classes`。

  題名は **pin 済みの Rev.5 を優先**し、そこに無ければ Rev.4 mirror の題名を使う。
  `revision` がどちらから来たかを言うので、読む側は係争中の表から来た文字列を
  そうと分かって読める（org-un-isic: mirror は pin 無しで、UN の legacy 構造
  ファイルと 414 中 33 件で題名が食い違う）。

  `classes` が無い（= 権威を読んでいない）ときは **`:unverified`**。空文字でも
  `nil` でもなく、そう書く —— 『照合していない』と『照合して版が分からなかった』を
  同じ顔にしない。"
  [isic classes]
  (if-not classes
    {:title nil :revision "unverified"}
    (let [k (get classes isic)]
      {:title (or (:title k) (:mirror-title k))
       :revision (cond (nil? k) "none"
                       (and (:rev5? k) (:rev4-mirror? k)) "both"
                       (:rev5? k) "rev5"
                       :else "rev4-mirror")})))

(defn lead->row
  "リード → 表の 1 行（全列 string / nil）。

  **連絡先の値の列は無い**（`has_email` / `has_phone` だけ）。lake 側と git 側で
  同じ行が出る —— 片方だけが値を持つ形にすると、どちらを配ってよいかが
  読み手に見えなくなる。値が要る側は `osm_url` から 1 件ずつ取り直す。"
  [lead {:keys [blueprints classes harvested-at]}]
  (let [isic (:lead/isic lead)
        bp (blueprint-for isic blueprints)
        [near match] (nearest-blueprint isic blueprints)
        {:keys [title revision]} (isic-facts isic classes)]
    {"lead_id" (:lead/id lead)
             "name" (:lead/name lead)
             "isic" isic
             "isic_section" (cw/isic->section isic)
             "isic_title" title
             "isic_revision" revision
             "isic_key" (:lead/isic-key lead)
             "isic_value" (:lead/isic-value lead)
             "blueprint_repo" bp
             "blueprint_url" (when bp (str "https://github.com/cloud-itonami/" bp))
             "blueprint_match" (name match)
             "nearest_blueprint_repo" near
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
             "osm_version" (some-> (:lead/osm-version lead) str)
             "osm_last_edit" (:lead/osm-last-edit lead)
             "has_addr" (str (boolean (:lead/has-addr? lead)))
             "source" "openstreetmap"
             "license" "ODbL-1.0"
             "harvested_at" harvested-at}))
