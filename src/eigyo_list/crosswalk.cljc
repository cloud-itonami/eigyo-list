(ns eigyo-list.crosswalk
  "OSM のタグ → ISIC。**表であって導出ではない。**

  誰かが現地を見て `shop=bakery` と付けた。それは宣言であって、こちらの推測では
  ない。この ns がやるのはその宣言を ISIC の符号へ写すことだけで、店名・URL・
  住所からの業種推測は一切しない（`noren.discovery` が同じ規律で 7 行の部分集合を
  持っている。こちらはその一般形）。

  ## 3 つの規律

  1. **どの選択子で引かれたかではなくタグそのものから決める。** 1 つの POI が
     `shop=bakery` と `craft=bakery` の両方を持つことがあり、どちらの節で
     返ってきたかで分類が揺れてはいけない。
  2. **表に無いタグ値は `nil` を返して数に出す。** OSM の `shop` は 500 以上の値を
     持ち、この表はその一部しか写していない。写せなかったものを『事業者でない』
     として黙って捨てると、カバレッジが実際より良く見える。
  3. **1 つの符号に丸めない。** `shop=yes` は『店であることは分かるが業種は
     宣言されていない』であって retail 一般（47）ではない。4 桁に丸めると、
     blueprint との突き合わせが嘘になる。

  ## 版

  ISIC の 4 桁 class。**版は符号ごとに違う** —— `data/isic.edn`（org-un-isic
  から生成）が、その符号を pin 済みの Rev.5 が宣言しているか、係争中の Rev.4
  mirror が宣言しているか、両方かを持つ。**どちらにも無い符号は表に置けない**
  （`scripts/verify-crosswalk.cljs` が落とす）。

  版をまたいだ対応表は作らない —— UN が publish しておらず、org-un-isic は
  「Rev.5 の符号を Rev.4 に写して解決済みと呼ぶな」と明記している。

  **Rev.5 の符号を混ぜない** —— 2026-08-27 の初回 1 周で
  `6522`（再保険、Rev.5）・`8899`・`8559` の 3 つを写しており、どれも blueprint が
  無い区分として出た。Rev.4 では順に `6622`（保険代理店）・`8890`・`8549` で、
  **3 つとも blueprint が在る**。blueprint との突き合わせが版ずれを見つけた ——
  それがこの join の効き目そのもの。

  `cloud-itonami` の blueprint repo（`cloud-itonami-isic-NNNN`、
  実測 456 件）がこの粒度なので、突き合わせられる粒度で写す。Rev.5 で符号が
  動いた区分（小売の 47 系など）は Rev.4 側を採る —— **blueprint に無い符号へ
  写すと、リードが『提案するものが無い』状態で出来上がる。**"
  (:require [kotoba.lang.text :as str]))

;; ── 表 ────────────────────────────────────────────────────────────────────
;;
;; key ごとに {タグ値 → ISIC}。key の優先順は `tags->isic` が持つ。

(def shop->isic
  "`shop=*`。小売（47 系）が中心だが、自動車 (45 系)・修理 (95 系)・
  対個人サービス (96 系) へ出るものがある —— OSM の `shop` は『店舗の面を
  持つもの』を広く取るので、ISIC 的には小売以外に着地する値が実在する。"
  {"supermarket" "4711" "convenience" "4711" "grocery" "4711" "general" "4711"
   "department_store" "4719" "mall" "4719" "kiosk" "4719" "variety_store" "4719"
   "bakery" "4721" "butcher" "4721" "seafood" "4721" "greengrocer" "4721"
   "confectionery" "4721" "chocolate" "4721" "cheese" "4721" "deli" "4721"
   "dairy" "4721" "farm" "4721" "spices" "4721" "nuts" "4721" "health_food" "4721"
   "pasta" "4721" "rice" "4721" "coffee" "4721" "tea" "4721" "frozen_food" "4721"
   "alcohol" "4722" "wine" "4722" "beverages" "4722" "brewing_supplies" "4722"
   "tobacco" "4723" "e-cigarette" "4723"
   "computer" "4741" "electronics" "4742" "hifi" "4742" "radiotechnics" "4742"
   "mobile_phone" "4742" "camera" "4742" "video_games" "4764"
   "fabric" "4751" "sewing" "4751" "haberdashery" "4751" "wool" "4751"
   "doityourself" "4752" "hardware" "4752" "paint" "4752" "trade" "4752"
   "building_materials" "4752" "glaziery" "4752" "fireplace" "4752"
   "tool_hire" "7730" "swimming_pool" "4752"
   "carpet" "4753" "curtain" "4753" "flooring" "4753" "tiles" "4753"
   "furniture" "4759" "kitchen" "4759" "bed" "4759" "appliance" "4759"
   "lighting" "4759" "houseware" "4759" "interior_decoration" "4759"
   "bathroom_furnishing" "4759" "window_blind" "4759"
   "books" "4761" "newsagent" "4761" "stationery" "4761" "copyshop" "8219"
   "music" "4762" "video" "4762" "musical_instrument" "4762"
   "sports" "4763" "outdoor" "4763" "bicycle" "4763" "fishing" "4763"
   "hunting" "4763" "scuba_diving" "4763" "golf" "4763" "ski" "4763"
   "toys" "4764" "games" "4764" "model" "4764" "collector" "4764"
   "clothes" "4771" "shoes" "4771" "boutique" "4771" "bag" "4771"
   "fashion_accessories" "4771" "leather" "4771" "watches" "4771"
   "jewelry" "4771" "fabric_shop" "4751" "baby_goods" "4771" "wedding" "4771"
   "chemist" "4772" "cosmetics" "4772" "perfumery" "4772" "herbalist" "4772"
   "medical_supply" "4772" "optician" "4772" "hearing_aids" "4772"
   "nutrition_supplements" "4772" "pharmacy" "4772"
   "florist" "4773" "garden_centre" "4773" "pet" "4773" "photo" "4773"
   "gift" "4773" "art" "4773" "craft" "4773" "frame" "4773" "party" "4773"
   "religion" "4773" "erotic" "4773" "candles" "4773" "trophy" "4773"
   "agrarian" "4773" "hairdresser_supply" "4773" "printer_ink" "4773"
   "second_hand" "4774" "charity" "4774" "antiques" "4774" "pawnbroker" "6492"
   "car" "4510" "truck" "4510" "car_parts" "4530" "tyres" "4530"
   "car_repair" "4520" "motorcycle" "4540" "motorcycle_repair" "4540"
   "caravan" "4510" "boat" "4763" "atv" "4510"
   "hairdresser" "9602" "beauty" "9602" "nail_salon" "9602" "tattoo" "9609"
   "massage" "9609" "laundry" "9601" "dry_cleaning" "9601" "funeral_directors" "9603"
   "travel_agency" "7911" "estate_agent" "6820" "insurance" "6622" "bank" "6419"
   "money_lender" "6492" "bookmaker" "9200" "lottery" "9200"
   "storage_rental" "5210" "rental" "7729" "locksmith" "8020"
   "ticket" "7990" "photo_studio" "7420" "tailor" "1410" "dressmaker" "1410"
   "shoe_repair" "9523" "watchmaker" "9529" "electronics_repair" "9521"
   "computer_repair" "9511" "vacant" nil "yes" nil
   ;; ── 2026-08-27 追加。**出所は receipt の `declared-misses`**（初回 1 周で
   ;; 実際に返ってきて表に無かった値の上位）。思いつきで広げていない。
   "pastry" "4721" "food" "4721" "tortilla" "4721" "ice_cream" "5610"
   "water" "4722" "cannabis" "4773" "pottery" "4773" "weapons" "4773"
   "gas" "4773" "anime" "4773" "gold_buyer" "4774"
   "telecommunication" "4742" "mobile_phone_accessories" "4742"
   "electrical" "4752" "doors" "4752" "household_linen" "4751"
   "accessories" "4771" "wigs" "4771" "hobby" "4764"
   "printing" "1811" "wholesale" "4690" "repair" "9529" "outpost" "5229"})

(def craft->isic
  "`craft=*`。職人・工房。製造 (10-33)・建設 (41-43)・修理 (33/95) に散る。"
  {"bakery" "1071" "brewery" "1103" "winery" "1102" "distillery" "1101"
   "confectionery" "1073" "butcher" "1010" "caterer" "5621" "beekeeper" "0149"
   "carpenter" "4330" "joiner" "1622" "cabinet_maker" "3100" "upholsterer" "3100"
   "plumber" "4322" "electrician" "4321" "hvac" "4322" "painter" "4330"
   "plasterer" "4330" "tiler" "4330" "floorer" "4330" "roofer" "4390"
   "scaffolder" "4390" "insulation" "4329" "well_drilling" "4312"
   "builder" "4100" "stonemason" "2396" "glaziery" "2310" "metal_construction" "2511"
   "window_construction" "2511" "blacksmith" "2599" "locksmith" "8020"
   "sawmill" "1610" "boatbuilder" "3011" "musical_instrument" "3220"
   "jeweller" "3211" "goldsmith" "3211" "watchmaker" "9529" "clockmaker" "9529"
   "pottery" "2393" "basket_maker" "1629" "saddler" "1512" "shoemaker" "1520"
   "tailor" "1410" "dressmaker" "1410" "embroiderer" "1399" "weaver" "1312"
   "printer" "1811" "bookbinder" "1812" "sign_maker" "3290" "engraver" "3290"
   "photographer" "7420" "gardener" "8130" "handicraft" "3290"
   "electronics_repair" "9521" "car_repair" "4520" "key_cutter" "9529"
   "agricultural_engines" "3312" "sculptor" "9000" "artist" "9000"
   "brewer" "1103" "chimney_sweeper" "8129" "cleaning" "8121" "yes" nil
   ;; ── 2026-08-27 追加（同上、receipt 由来）
   "photographic_laboratory" "7420" "photo_studio" "7420" "atelier" "9000"
   "signmaker" "3290" "print_shop" "1811" "jam" "1079" "mobile_phone" "9512"})

(def office->isic
  "`office=*`。ここは B2B の入口で、`website` の充足率が小売より高い。"
  {"lawyer" "6910" "notary" "6910" "accountant" "6920" "tax_advisor" "6920"
   "architect" "7110" "engineer" "7110" "surveyor" "7110" "geodesist" "7110"
   "it" "6201" "consulting" "7020" "company" "7020" "advertising_agency" "7310"
   "marketing" "7310" "graphic_design" "7410" "interior_design" "7410"
   "research" "7210" "estate_agent" "6820" "property_management" "6820"
   "insurance" "6622" "financial" "6619" "financial_advisor" "6619"
   "employment_agency" "7810" "recruitment" "7810" "temp_agency" "7820"
   "travel_agent" "7911" "logistics" "5229" "moving_company" "4923"
   "forwarding" "5229" "courier" "5320" "telecommunication" "6110"
   ;; ── 2026-08-27 追加（receipt 由来）
   "union" "9420" "university" "8530" "construction_company" "4100"
   "police" "8423" "bank" "6419" "medical" "8620" "chamber" "9411"
   "vacant" nil
   "newspaper" "5813" "publisher" "5811" "coworking" "6810" "diplomatic" "8421"  ;; 対外関係。8423 は公共の秩序・安全（誤りを訂正）
   "government" "8411" "administrative" "8411" "tax" "8411" "employment_office" "8412"
   "ngo" "9499" "association" "9499" "political_party" "9492" "charity" "8890"
   "religion" "9491" "educational_institution" "8549" "therapist" "8690"
   "physician" "8620" "veterinary" "7500" "water_utility" "3600" "energy_supplier" "3510"
   "security" "8010" "guide" "7990" "translator" "7490" "insurance_agency" "6622"
   "quango" "8411" "foundation" "9499" "yes" nil})

(def amenity->isic
  "`amenity=*` のうち **事業者に当たる値だけ**。ベンチもゴミ箱も `amenity` なので、
  ここに書いていない値は表に無い（`nil`）—— 引く側も `:any-of` でこのキー集合に
  絞るので、クエリと表が同じ集合を指す。"
  {"restaurant" "5610" "cafe" "5610" "ice_cream" "5610"
   ;; `fast_food` は **5610**。2026-08-27 まで `5613`（持ち帰り）に写していたが、
   ;; **5613 は ISIC のどの版にも存在しない**（org-un-isic の pin 済み Rev.5 463
   ;; class にも、Rev.4 mirror 428 class にも無い）。両版とも 5610 の題名は
   ;; 「Restaurants and mobile food service activities」で、持ち帰りはそこに入る。
   ;; 出所は `kotoba-lang/noren` の同じ表で、そちらにも同じ誤りが在る。
   "fast_food" "5610"
   "food_court" "5629" "bar" "5630" "pub" "5630" "biergarten" "5630"
   "nightclub" "9329" "casino" "9200" "gambling" "9200" "cinema" "5914"
   "theatre" "9000" "arts_centre" "9000" "library" "9101" "museum" "9102"
   "bank" "6419" "bureau_de_change" "6619" "pharmacy" "4772" "hospital" "8610"
   "clinic" "8620" "doctors" "8620" "dentist" "8620" "veterinary" "7500"
   "childcare" "8890" "kindergarten" "8510" "school" "8510" "college" "8530"
   "university" "8530" "driving_school" "8550" "language_school" "8550"
   "music_school" "8550" "prep_school" "8550" "dancing_school" "8550"
   "training" "8549" "car_rental" "7710" "car_wash" "4520" "fuel" "4730"
   "vehicle_inspection" "7120" "driving_range" "9311" "coworking_space" "6810"
   "post_office" "5310" "internet_cafe" "6312" "studio" "5911"
   "animal_boarding" "9609" "animal_shelter" "9499" "crematorium" "9603"
   "funeral_hall" "9603" "marketplace" "4781" "stripclub" "9329"
   "brothel" "9609" "spa" "9602" "public_bath" "9602"})

(def tourism->isic
  {"hotel" "5510" "motel" "5510" "guest_house" "5510" "hostel" "5510"
   "apartment" "5510" "chalet" "5510" "alpine_hut" "5510" "wilderness_hut" "5510"
   "camp_site" "5520" "caravan_site" "5520" "gallery" "9102" "museum" "9102"
   "theme_park" "9321" "zoo" "9103" "aquarium" "9103"})

(def leisure->isic
  {"fitness_centre" "9311" "sports_centre" "9311" "sports_hall" "9311"
   "golf_course" "9311" "horse_riding" "9311" "swimming_pool" "9311"
   "ice_rink" "9311" "bowling_alley" "9329" "amusement_arcade" "9329"
   "adult_gaming_centre" "9200" "escape_game" "9329" "dance" "9329"
   "trampoline_park" "9329" "water_park" "9321" "marina" "5222"
   "hackerspace" "9499" "resort" "5510"})

(def healthcare->isic
  "`healthcare=*` は `amenity` と重なるが、`amenity=clinic` が付いていない
  専門職（理学療法士・助産師など）はこちらにしか出ない。"
  {"hospital" "8610" "clinic" "8620" "doctor" "8620" "dentist" "8620"
   "centre" "8620" "physiotherapist" "8690" "psychotherapist" "8690"
   "occupational_therapist" "8690" "speech_therapist" "8690" "podiatrist" "8690"
   "optometrist" "8690" "midwife" "8690" "nurse" "8690" "laboratory" "8690"
   "sample_collection" "8690" "blood_donation" "8690" "alternative" "8690"
   "rehabilitation" "8610" "nursing_home" "8710" "hospice" "8710"
   "pharmacy" "4772" "birthing_centre" "8610" "dialysis" "8620"
   "vaccination_centre" "8690" "counselling" "8890"})

(def key-order
  "同じ POI に複数のキーが付いていたときの優先順。**宣言する。**

  `healthcare` を `amenity` より先に見るのは、`amenity=clinic` +
  `healthcare=physiotherapist` のような組み合わせで後者の方が細かいから。
  `shop` を `craft` より先に見るのは、両方を持つパン屋（`shop=bakery` +
  `craft=bakery`）が『売る面』を持っているから —— 営業の相手は店舗である。"
  [["healthcare" healthcare->isic]
   ["shop" shop->isic]
   ["craft" craft->isic]
   ["office" office->isic]
   ["amenity" amenity->isic]
   ["tourism" tourism->isic]
   ["leisure" leisure->isic]])

(defn tags->isic
  "タグ map → `{:isic \"5610\" :isic-key \"amenity\" :isic-value \"restaurant\"}`、
  または `nil`。

  **`nil` は『事業者でない』ではなく『この表が写していない』**。呼び出し側は
  それを数えて申告する（`eigyo-list.coverage`）。"
  [tags]
  (some (fn [[k table]]
          (when-let [v (get tags k)]
            (when-let [isic (get table v)]
              {:isic isic :isic-key k :isic-value v})))
        key-order))

(defn tags->declared-key
  "業種の表には無いが、どのキーで拾われたか。**取りこぼしの中身を見るため**の値で、
  分類には使わない。空なら選択子に当たらない何かが返ってきている。"
  [tags]
  (some (fn [[k _]] (when (get tags k) (str k "=" (get tags k)))) key-order))

(defn selectors
  "`org-openstreetmap-overpass.core/ql` に渡す選択子。

  `shop` / `craft` / `office` / `healthcare` は **キー存在形**で引く —— 表に無い
  値も返らせて `:unclassified` として数えるため。`amenity` / `tourism` / `leisure`
  は非事業者の値（ベンチ・ゴミ箱・公園）が支配的なので **表の値だけ**に絞る。

  この非対称は意図したもので、`amenity` をキー存在で引くと 1 セルの応答が
  1 桁増え、その大半がベンチになる（実測 2026-08-27・神楽坂: 事業者 649 に対し
  `amenity` 全体はその数倍）。"
  []
  (into [["shop"] ["craft"] ["office"] ["healthcare"]]
        (for [[k table] [["amenity" amenity->isic]
                         ["tourism" tourism->isic]
                         ["leisure" leisure->isic]]]
          [k {:any-of (sort (keys table))}])))

(defn tables
  "全部の表を宣言順に。**版の digest を取るための唯一の入口** —— 表が増えたら
  ここも直さないと、digest が変わらないまま中身が変わる。"
  []
  [shop->isic craft->isic office->isic amenity->isic tourism->isic
   leisure->isic healthcare->isic])

(defn coverage
  "表が写している値の数。README と receipt に書く数の出どころを 1 つにする。"
  []
  {:shop (count shop->isic) :craft (count craft->isic) :office (count office->isic)
   :amenity (count amenity->isic) :tourism (count tourism->isic)
   :leisure (count leisure->isic) :healthcare (count healthcare->isic)
   :isic-codes (count (into #{} (remove nil?)
                            (mapcat vals [shop->isic craft->isic office->isic
                                          amenity->isic tourism->isic
                                          leisure->isic healthcare->isic])))})

(defn isic->section
  "ISIC 4 桁 → 大分類の 2 桁。セグメント集計の見出しに使う（表ではなく先頭 2 桁で、
  ここだけは導出でよい —— ISIC の符号は桁が階層そのものである）。"
  [isic]
  (when (and isic (<= 2 (count (str isic)))) (subs (str isic) 0 2)))
