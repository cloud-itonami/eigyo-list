#!/usr/bin/env nbb
(ns resident
  "常駐の 1 日ぶん —— いちばん古い区画を引き直し、3 表を lake に載せ直す。

    nbb --classpath src:../../kotoba-lang/org-openstreetmap-overpass/src bin/resident.cljs

  launchd が 1 日 1 回呼ぶ（`ops/cloud.itonami.eigyo-list-tick.plist`）。

  ## なぜ全区画を毎日引かないか

  Overpass は無償の共有インフラで、48 区画 1 周は実測 25 分・50 万 element を
  読む。毎日それを投げる理由は無い —— 事業者の開店と閉店は日単位で動かない。
  1 日 **12 区画**（`--stale 12`）にすると、127 区画に対して再訪は約 11 日。
  実測 2026-08-27: 1 日のうちに 60 区画ほど引いたところで Overpass が明らかに
  絞り始めた（1 区画 10 分）。12 はその 1/5 で、粘らずに済む数。選ぶのは receipt の `:harvested-at` が古い順で、乱数では
  ない（乱数だと、ある区画が何周も選ばれないことが起こりうる）。

  ## なぜ plist から直接 `harvest` を呼ばないか

  3 つ、plist に書けないことがある。

  **`harvest` の exit 2 は人間には正しく、タイマーには誤り。** 1 区画でも
  読めなければ 2 で、たとえば Overpass が 1 本だけ 504 を返した日に
  『job が壊れた』と記録される。ここでは **1 区画も読めなかったときだけ** 2 に
  する —— 一部が読めなかったのは coverage 表の `failed` として残り、それが
  正しい置き場所。

  **export と sync は harvest の後でしか意味が無い。** 順序を plist で表せない。

  **資格情報を自分で読まない。** Iceberg の commit は superproject の
  `scripts/datalake-sync.py` に委ね、token はその中の `datalake_catalog` が
  Keychain から名指しで 1 件だけ取る。ここは持ち回らない。

  ## 空きが無いときは走らない

  2026-08-27 の初回収集はマシンの空きが尽きて 13 区画を落とし、**3 つの名簿を
  途中まで書いた**（`write-edn!` は tmp+rename に直したが、書けないこと自体は
  防げない）。常駐は毎日 120 MB を書き直すので、**空きを先に測って、足りなければ
  走らずに 2 で終わる** —— 走って半分書くより、走らないほうがよい。

    nbb … bin/resident.cljs --check   何をするかだけ出して終わる（収集しない）

  exit: 0 進んだ / 1 拒否された（loader が居ない・0 行）/ 2 答えられなかった
  （1 区画も読めなかった・空きが足りない）"
  (:require ["child_process" :as cp]
            ["fs" :as fs]
            ["path" :as path]))

(def min-free-gb
  "これを下回ったら走らない。1 周 120 MB + Iceberg の一時ファイルに対する床で、
  余裕を持たせてある（このマシンは並行して多数の agent が走る）。"
  3)

(def cells-per-day 12)

(def repo-root
  (path/resolve (path/dirname (path/dirname
    (or (first (filter #(re-find #"\.cljs$" %) (vec js/process.argv))) ".")))))

(def classpath
  (str "src:" (path/resolve repo-root ".." ".." "kotoba-lang"
                            "org-openstreetmap-overpass" "src")))

(defn- run [args]
  (let [r (cp/spawnSync "nbb" (clj->js (into ["--classpath" classpath] args))
                        #js {:stdio "inherit" :cwd repo-root})]
    (if (nil? (.-status r)) 2 (.-status r))))

(defn- free-gb []
  (try (let [s (fs/statfsSync repo-root)]
         (/ (* (.-bavail s) (.-bsize s)) 1e9))
       (catch :default _ nil)))

(def check? (some #{"--check"} (vec js/process.argv)))

(println (str "eigyo-list resident " (.toISOString (js/Date.))))

(let [g (free-gb)]
  (println (str "  free " (if g (str (.toFixed g 1) " GB") "UNKNOWN")
                "  floor " min-free-gb " GB"
                "  cells/day " cells-per-day))
  (when (and g (< g min-free-gb))
    (println (str "REFUSING: free space " (.toFixed g 1) " GB is below the floor. "
                  "A run that fills the disk writes half a corpus."))
    (js/process.exit 2))
  (when (nil? g)
    (println "cannot measure free space -- that is not the same as having enough")
    (js/process.exit 2)))

(when check?
  (println "  --check: 収集しない。stale の並びだけ出す")
  (js/process.exit (run ["bin/eigyo.cljs" "cells"])))

(let [h (run ["bin/eigyo.cljs" "harvest" "--stale" (str cells-per-day)])]
  (cond
    ;; 2 は「1 区画も読めなかった」か「一部が failed」の両方で返る。前者だけを
    ;; 答えられなかったとして扱いたいので、coverage 側の数で判定し直す。
    (= 2 h)
    (let [c (run ["bin/eigyo.cljs" "stats"])]
      (println (str "harvest exit 2 -- stats exit " c
                    "\n  一部の区画が読めなかった。coverage 表の failed に残る。"
                    "\n  1 区画も読めなかった場合はここで止まる（下の export に行かない）"))
      (js/process.exit (if (zero? c) (run ["bin/eigyo.cljs" "sync" "--out-dir" "/tmp/eigyo-out"]) 2)))

    (pos? h)
    (do (println (str "harvest refused (exit " h ")")) (js/process.exit h))

    :else
    (js/process.exit (run ["bin/eigyo.cljs" "sync" "--out-dir" "/tmp/eigyo-out"]))))
