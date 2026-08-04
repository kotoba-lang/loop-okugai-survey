# loop-denchu-survey

**電柱在庫の継続 survey orchestrator** — observe → evaluate → decide → act →
record-evidence。写真・地図データから電柱を特定し、`denchu` の在庫台帳
（datom 面に載る `*.datoms.edn`）に落とす。

`loop-*` は continuous orchestrator であって domain truth の持ち主ではない
（`manifest/repository-rules.edn` `:name-prefix`）。したがってこの repo は
**柱の定義も、窓口も、料金も、法令も持たない**:

| 委譲先 | 何を任せるか |
|---|---|
| [`denchu`](../denchu) | 柱の identity・束ね方・信頼度・面・窓口・料金・法令・出稿 |
| [`org-openstreetmap-overpass`](../org-openstreetmap-overpass) | OSM の QL と応答正規化（鍵なしで引ける） |
| [`com-mapillary-graph-api`](../com-mapillary-graph-api) | Mapillary の写真検出（token 必須、bbox タイル分割） |

ここが所有するのは『どの面をどの順で見て、何を証跡として残すか』だけ。

## 走らせる

```bash
nbb --classpath "src:../denchu/src:../org-openstreetmap-overpass/src:../com-mapillary-graph-api/src" \
    bin/survey.cljs --area-id setagaya --jurisdiction JP-13 \
    --bbox 35.6200,139.6200,35.6600,139.6800 --sources osm --out data
```

`--bbox` は `south,west,north,east`（地図から読む順）。`--sources` は
`osm` / `mapillary` / `both`。`--jurisdiction` は **呼び出し側が宣言する** —
座標から逆引きして推測しない。

出力は 3 種:

```
data/denchu-inventory-<area>.datoms.edn   柱 + カバレッジ申告（area ごと）
data/denchu-catalog.datoms.edn            代理店・面種別の静的カタログ（共通1本）
data/survey-evidence-<area>.edn           入力・測定・閾値・落としたものの証跡
```

カタログを area shard に混ぜないのは、面をまたいで数えたときに代理店が area 数
だけ増えて見えるため（datom 面に upsert キーは無い）。同じ `denchu-inventory`
dataset なので join は従来どおりできる。

## 初回 survey の実測（2026-08-04、OSM のみ）

| area | 管轄 | 観測 | 柱 | 所有者判明 | 窓口到達可 |
|---|---|---:|---:|---:|---:|
| chiyoda-marunouchi | JP-13 | 0 | 0 | 0 | 0 |
| setagaya | JP-13 | 8 | 5 | 0 | 0 |
| kyoto-nakagyo | JP-26 | 55 | 55 | 0 | 0 |
| sapporo-chuo | JP-01 | 205 | 201 | 0 | 0 |

**この表の右2列が 0 なのが、今この loop が明らかにした最大のギャップ。**
OSM に柱はマッピングされていても `operator` タグはほぼ付いていないので、
所有者が決まらず、したがって申込先の代理店も決まらない。`denchu` は所有者を
推測しない設計なので、これは黙って埋まらず `:pole/owner "unknown"` /
`:pole/routable false` として台帳に出る。

丸の内が 0 件なのも同じく実測値であって障害ではない（無電柱化の進んだ区域 +
中心部の柱マッピングが薄い）。**area の外に柱が無いのではなく、見ていない。**

## 次に効く一手

1. **所有者の解決**: 現地の柱番号札（電力会社の柱には番号札が付く）を
   Mapillary の画像から読む経路。`com-mapillary-graph-api` の `/images` +
   `detections` は既に叩ける。
2. **Mapillary の併用**: `--sources both` で 2 source 一致が信頼度 +0.30。
   `MAPILLARY_ACCESS_TOKEN` が要る。
3. **代理店への実問い合わせ**: `denchu.order` の `:inquiry-proposed` →
   `:inquiry-sent`（`:external-send` risk、承認キュー経由）。

## テスト

```bash
nbb --classpath "src:test:../denchu/src" test/run.cljs    # 5 tests / 18 assertions
```

MIT。
