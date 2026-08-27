# eigyo-list

**世界の事業者の名簿を、OpenStreetMap が公表している宣言だけから組み、
cloud-itonami の blueprint と突き合わせて、R2 Data Catalog の Iceberg 表に置く。**

営業の相手を探すときに最初に要るのは「誰が居て、何屋で、どの面を公表して
いるか」で、それは OSM に**現地の人が付けた宣言**として既に在る。ここが
やるのはその宣言を ISIC の符号へ写し、`cloud-itonami-isic-NNNN` の
blueprint と突き合わせることだけ。**店名や URL から業種を推測しない。**

## 隣の repo との境界

| repo | 何を持つか |
|---|---|
| **`cloud-itonami/eigyo-list`**（ここ） | **名簿**。誰が居て、何屋で、どの面が公表されているか |
| `cloud-itonami/eigyo` | 営業パイプラインの状態機械（lead → deal → 決済）。名簿は持たない |
| `kotoba-lang/noren` | **接触してよいかの判定**（適格性・受信拒否・根拠・処方） |
| `cloud-itonami/loop-noren` | 1 件ずつ診断して承認キューへ提案する常駐 loop |
| `kotoba-lang/org-openstreetmap-overpass` | Overpass QL の組み立てと応答の正規化（タクソノミーは持たない） |

**名簿に載っていることは、接触してよいことではない。** 適格性・特定電子メール法
3 条 1 項の根拠・受信拒否・出所 URL と観測日は `noren.prospect/eligible` と
`noren.governor` が持っており、ここから 1 件取り出して接触する経路は必ずそこを通る。

## 連絡先の**値**は持ち出さない

行が持つのは `has_email` / `has_phone` という**有無**と、`site`（事業者の web 面）と
`osm_url` だけ。メールアドレスと電話番号の文字列は観測の境界で捨てる。
OSM 側で公開されている事実ではあるが、**名簿の形に集めることは別の行為**で、
集めた側の判断が要る。1 件について値が要るときは `osm_url` から取り直す ——
そのとき値は今日の値になり、経路は governor を通る。

したがってこの表は**ターゲティングの索引であって連絡先データベースではない**。
「メールアドレスを一括で出したい」に対する答えはここには無い。

## 「全世界」と言えること・言えないこと

収集は Overpass API（無償の共有インフラ）で、区画は `data/cells.edn` に**宣言**
してある。だからこの名簿が言えるのは**宣言した都市中心部について測った結果**で
あって、世界の網羅ではない。coverage 表は 4 値を持ち、**引いていない区画は
`planned` として出る —— 0 件ではない**。

網羅を主張したい日が来たら、それは Overpass ではなく planet 抽出
（Geofabrik の `.osm.pbf`）の仕事で、この repo にその読み手は無い。無いものを
在ることにしないために書いておく。

## 実測 2026-08-27（初回の 1 周）

| | |
|---|---|
| 区画 | **48 / 48 measured**、failed 0、planned 0（6 大陸 / 48 か国） |
| リード | **155,953** |
| ISIC 区分 | **138**、うち blueprint が在るもの **134** |
| web 面を持つ | 123,984（79%） |
| 電話がある | 106,407（68%） |
| メールがある | 33,996（22%） |
| チェーン店舗 | 27,358（18%。除外せず印を付けてある） |

上位区分: 5610 飲食店 36,860 / 5510 宿泊 7,821 / 4771 衣料 7,497 /
5630 バー 7,111 / 8510 初中等教育 6,884。

**blueprint が無い 4 区分**は `5613`（持ち帰り飲食、**6,567 件**）・`6522`（再保険 348）・
`8899`（その他社会事業 66）・`8559`（その他教育 65）。リードは在るのに提案するものが
無い、が数として出ている —— 欠測ではなく品揃えの穴。

⚠ **48 か国というのは 48 区画がそれぞれ別の国に在るという意味**で、その国を
測ったという意味ではない（1 国 = 都市中心部 61 km² 1 枚）。

## 表（`cloud_itonami` namespace / bucket `cloud-itonami-datalake`）

| 表 | 1 行 | 何に使うか |
|---|---|---|
| `eigyo_lead` | 1 事業者 | 名簿そのもの。ISIC・国・都市・web 面の有無・blueprint |
| `eigyo_coverage` | 1 区画 | 何を測って何を測っていないか（measured / failed / planned / disabled）|
| `eigyo_segment` | 1 ISIC 符号 | 営業の入口。どの業種に何件居て、その blueprint が在るか |

**projection であって正本ではない。** 3 表を全部消しても `harvest` → `export` →
`sync` で作り直せる。正本は OpenStreetMap 側にあり、こちらは receipt
（`data/receipts/<cell>.edn`、件数・落とした理由・集合の digest）を git に持つ。

## 使う

```bash
CP="src:../../kotoba-lang/org-openstreetmap-overpass/src"

nbb --classpath "$CP" bin/eigyo.cljs cells      # 区画の宣言と、いま何が測れているか
nbb --classpath "$CP" bin/eigyo.cljs harvest --cell gb-london-city
nbb --classpath "$CP" bin/eigyo.cljs stats      # 手元の名簿を数える
nbb --classpath "$CP" bin/eigyo.cljs export --out-dir /tmp
FLEET_ROOT=<superproject> nbb --classpath "$CP" bin/eigyo.cljs sync --out-dir /tmp

nbb --classpath "src:test:../../kotoba-lang/org-openstreetmap-overpass/src" run_tests.cljs
FLEET_ROOT=<superproject> nbb scripts/gen-blueprints.cljs --check
```

`harvest` の exit code は **0 / 1 / 2**。2 は「区画を読めなかった」で、
0 件だった周とは別に数える。`sync` は superproject の
`scripts/datalake-sync.py`（認証は `datalake_catalog.load_token`）に委ねる ——
Iceberg の commit を書く 2 本目を持たない。

## 分類は表であって推測ではない

`src/eigyo_list/crosswalk.cljc` が OSM のタグ → ISIC を**表として**持つ。
`shop` / `craft` / `office` / `healthcare` は**キー存在形**で引き（表に無い値も
返らせて数える）、`amenity` / `tourism` / `leisure` は**表の値だけ**に絞る
（ベンチもゴミ箱も `amenity` なので）。

表に無い値は `nil` を返し、`:isic-not-declared` として数え、receipt の
`declared-misses` に上位が残る。**それが表を育てるときの唯一の入力**で、
「取れなかったもの」を見ずに表を広げると、広げた先が実在するか分からない。

## データの出所とライセンス

OpenStreetMap contributors, ODbL 1.0。詳細と share-alike の及ぶ範囲は `NOTICE`。
