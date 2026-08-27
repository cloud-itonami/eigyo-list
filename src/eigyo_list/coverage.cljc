(ns eigyo-list.coverage
  "区画（cell）と、その区画について何が測れたか。

  **『リードが 0 件』と『まだ引いていない』と『引いたが失敗した』を、出力で
  区別できる形にするための ns。** 名簿だけを見ると 3 つは同じ空白に見える。

  ## 面積を揃える

  区画は中心を宣言し、bbox は導出する。経度 1 度は緯度によって長さが違うので、
  同じ度数の箱をシンガポールとストックホルムに置くと**後者の面積が半分以下**に
  なり、件数の比較が緯度の関数になる。`half-lon = half-lat / cos(lat)` で
  実距離を揃える —— これは規約ではなく地球の形の話なので導出してよい。"
  (:require [clojure.string :as str]))

(def ^:private deg->rad (/ Math/PI 180.0))

(defn cell->bbox
  "`{:cell/center [lat lon] :cell/half-lat 0.035}` → Overpass の bbox。"
  [{:cell/keys [center half-lat] :or {half-lat 0.035}}]
  (let [[lat lon] center
        c (Math/max 0.2 (Math/cos (* deg->rad lat)))
        half-lon (/ half-lat c)]
    {:south (- lat half-lat) :west (- lon half-lon)
     :north (+ lat half-lat) :east (+ lon half-lon)}))

(defn cell-km2
  "宣言した区画の概算面積 km²。件数を面積で割れるようにするために出す
  （密度を比べたいのに箱の大きさが違う、という読み違いを防ぐ）。"
  [cell]
  (let [{:keys [south west north east]} (cell->bbox cell)
        mid (/ (+ south north) 2.0)]
    (Math/round (* (* 111.32 (- north south))
                   (* 111.32 (Math/cos (* deg->rad mid)) (- east west))))))

(defn enabled-cells [cells] (filterv #(not (false? (:cell/enabled? %))) cells))

(defn coverage-row
  "1 区画 = 1 行。`status` は 4 値:

     measured   引いて、応答を読んだ
     failed     引いたが読めなかった（理由つき）。**0 件ではない**
     planned    宣言してあるが、まだ引いていない
     disabled   宣言のうえで対象外にしてある

  `planned` を 0 件の `measured` と同じ形にしないことがこの表の存在理由。"
  [cell {:keys [status raw-count leads refused declared-misses error at]}]
  (let [bbox (cell->bbox cell)]
    {"cell" (:cell/id cell)
     "label" (:cell/label cell)
     "country" (:cell/country cell)
     "region" (:cell/region cell)
     "status" (name (or status :planned))
     "south" (str (:south bbox)) "west" (str (:west bbox))
     "north" (str (:north bbox)) "east" (str (:east bbox))
     "km2" (str (cell-km2 cell))
     "elements_seen" (some-> raw-count str)
     "leads" (some-> leads str)
     "refused_no_contact" (some-> (get refused :no-contact-channel) str)
     "refused_isic_not_declared" (some-> (get refused :isic-not-declared) str)
     "refused_no_name" (some-> (get refused :no-name) str)
     "top_unmapped_tag" (some->> declared-misses (sort-by (comp - val)) first key)
     "error" (some-> error str)
     "measured_at" at
     "source" "openstreetmap"
     "license" "ODbL-1.0"}))

(defn summarize
  "全区画の状態 → 1 つの申告。**未測定が 0 でないかぎり clean と言わない。**"
  [rows]
  (let [by (frequencies (map #(get % "status") rows))]
    {:cells (count rows)
     :measured (get by "measured" 0)
     :failed (get by "failed" 0)
     :planned (get by "planned" 0)
     :disabled (get by "disabled" 0)
     :leads (reduce + 0 (keep #(some-> (get % "leads") parse-long) rows))}))

(defn plan-note
  "この名簿が『全世界』について何を言えるか。README と receipt に同じ文が出る。"
  [rows]
  (let [{:keys [measured planned failed cells leads]} (summarize rows)]
    (str "measured " measured "/" cells " cells"
         (when (pos? failed) (str ", FAILED " failed))
         (when (pos? planned) (str ", not yet read " planned))
         " -- " leads " leads. "
         "This is metro-centre sampling, not a survey of the world: "
         "a cell that was never read is 'planned', never 'zero'.")))
