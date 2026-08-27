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

## 実測 2026-08-27

| | |
|---|---|
| 区画 | **48 / 48 measured**、failed 0、planned 0（6 大陸 / 48 か国） |
| リード | **156,202** |
| ISIC 区分 | **143**、4 桁の blueprint が在るもの **139** |
| web 面を持つ | 79% / 電話 68% / メール 22% |
| チェーン店舗 | 18%（除外せず印を付けてある） |

上位区分: 5610 飲食店 36,860 / 5510 宿泊 7,821 / 4771 衣料 7,497 /
5630 バー 7,111 / 8510 初中等教育 6,884。

**4 桁の blueprint が無い 4 区分**と、その群までの落ち先:

| ISIC | リード | nearest | match |
|---|---|---|---|
| 5613 持ち帰り飲食 | 6,567 | `cloud-itonami-isic-561` | group |
| 6522 再保険 | 326 | — | **none** |
| 8899 | 66 | `cloud-itonami-isic-889` | group |
| 8559 | 56 | `cloud-itonami-isic-855` | group |

⚠ **`6522` は分類表の版ずれの残りかす。** blueprint は ISIC Rev.4 の粒度で、
Rev.5 の `6522`（再保険）に対応する repo は無い。Rev.4 では保険代理店は `6622` で、
そちらは在る。表は 2026-08-27 に直したが、**まだ直す前の表で分類された区画が
残っている**（下記）。常駐が 1 週間で置き換える。

⚠ **今日の名簿は分類表の版が混ざっている**: 48 区画のうち **20** が最終版
（`shop=pastry` 等 38 値を足し、Rev.5 の 3 符号を Rev.4 に直したもの）、
28 はそれ以前の版。Overpass が 1 周 25 分の再収集の途中で絞り始めたので
（無償の共有インフラなので粘らない）、収束は常駐に任せてある。
以後の収集は receipt と coverage 表に `crosswalk` の digest を書く ——
**混ざっていること自体は平常運転（1 日 7 区画）だが、どの区画がどの版かが
出力から分からないのは別の話**。

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

## 常駐（1 日 7 区画）

```bash
nbb --classpath "$CP" bin/resident.cljs --check   # 何をするかだけ見る（収集しない）
cp ops/cloud.itonami.eigyo-list-tick.plist ~/Library/LaunchAgents/
launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/cloud.itonami.eigyo-list-tick.plist
tail -40 /tmp/eigyo-list-tick.log
```

毎日 05:40、**receipt がいちばん古い 7 区画だけ**を引き直して 3 表を載せ直す
（48 区画なので再訪は約 1 週間）。毎日 48 区画を舐めない —— Overpass は無償の
共有インフラで、1 周は 50 万 element を読む。事業者の開店と閉店は日単位で動かない。

**空きが足りなければ走らずに exit 2。** 初回収集はマシンの空きが尽きて 13 区画を
落とし、名簿 3 つを途中まで書いた。走って半分書くより、走らないほうがよい。

## blueprint が無い区分は、群まで落ちて答える

`blueprint_repo` は **4 桁が一致したときだけ**入る。無いときは
`nearest_blueprint_repo` に親群（3 桁）か部門（2 桁）が入り、`blueprint_match` が
`exact` / `group` / `division` / `none` を言う。

実例: ISIC **5613**（持ち帰り飲食）に 4 桁の repo は無いが、親群
`cloud-itonami-isic-561` は在る。**提案できるものが在ることと、その業種の
blueprint が在ることは別の主張**なので、列を分けてある。

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

## 符号は権威に当てる（ISIC）

`data/isic.edn` は `cloud-itonami/org-un-isic` から生成した投影で、符号ごとに
**pin 済みの Rev.5（463 class）が宣言しているか、係争中の Rev.4 mirror（428 class）が
宣言しているか、両方か**を持つ。行の `isic_revision` はそれをそのまま言う。

```bash
nbb --classpath src scripts/verify-crosswalk.cljs   # 0 全符号が実在 / 1 実在しない符号 / 2 権威を読めなかった
```

**どちらの版にも無い符号を表に置かない。** 置くと、その符号のリードは
「blueprint が無い区分」として積み上がり、品揃えの穴に見える —— 実際はこちらの
写し間違いである。実測 2026-08-27: `5613`（持ち帰り飲食）がまさにこれで、
**ISIC のどの版にも存在しない符号に 6,567 件が載っていた**（出所は
`kotoba-lang/noren` の同じ表）。いまは `amenity=fast_food` → `5610`。

⚠ **版をまたいだ対応表は作らない。** UN が publish しておらず、org-un-isic は
「Rev.5 の符号を Rev.4 に写して解決済みと呼ぶな」と明記している。

## 同じ店が 2 つ立っている分は畳む

OSM では建物の way とその中の POI node が両方 tag を持つことが多い。畳まないと
**同じ相手に 2 回営業する**ことになる。名前と業種が同じで 40 m 以内を同一とみなし、
タグの多いほうを残す（連絡先を持っているのはたいていそちら）。チェーンの支店は
名前が同じでも離れているので畳まれない。**落とした数は receipt の
`:duplicate-of-another-element` に出る** —— 件数が減った理由が「居なかった」なのか
「畳んだ」なのかは、出力から区別できなければならない。

## その事実は最後にいつ触られたか

`osm_last_edit` / `osm_version`（`out meta`）。2014 年から動いていない POI と
先月更新された POI は、営業リストとして同じ重みではない。**`user` / `uid` は
取らない** —— 誰が編集したかは事業者についての事実ではなく、編集者個人の情報。

⚠ この 2 列と `has_addr` は **2026-08-27 の第 3 波から**。それ以前に収集した
区画の行では空で、それは「OSM に timestamp が無い」ではなく「こちらが訊く前に
収集した」である。常駐が置き換える。

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
