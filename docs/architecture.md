# アーキテクチャ

## 概要

japanpost-history は、日本郵便が公開する KEN_ALL.CSV（最新フルデータ）と月次差分ファイル（ADD/DEL）から、**任意時点の郵便番号を引ける時系列辞書**を構築する Java ライブラリです。

```
KEN_ALL.CSV (最新)
ADD_YYMM.CSV × 222
DEL_YYMM.CSV × 222
        ↓  build()
HistoricalPostcodeDictionary
  snapshots: TreeMap<YearMonth, TreeMap<postcode, List<PostcodeEntry>>>
  postcodeTimeline: Map<postcode, TreeMap<YearMonth, List<PostcodeEntry>>>
  addressTimeline:  Map<addressKey, TreeMap<YearMonth, String(postcode)>>
        ↓  DictionarySnapshot.write()
japanpost-history.snapshot (2.6 MB, GZIP)
        ↓  DictionarySnapshot.load()
HistoricalPostcodeDictionary (218ms)
```

---

## データモデル

### PostcodeEntry

KEN_ALL.CSV の 1行に対応するイミュータブルレコード。

```java
record PostcodeEntry(
    String lgCode,           // 全国地方公共団体コード (5桁)
    String postcode,         // 郵便番号 (7桁、ハイフンなし)
    String prefectureKana,   // 都道府県名カナ（全角）
    String municipalityKana, // 市区町村名カナ（全角）
    String townKana,         // 町域名カナ（全角）
    String prefecture,       // 都道府県名
    String municipality,     // 市区町村名
    String town              // 町域名
)
```

**複数行分割のマージ**: KEN_ALL では、町域名が長い場合に括弧が閉じるまで複数行にわたって記載されます。`mergeContinuationRows()` が連続行を自動結合します。

### DictionarySnapshot

バイナリスナップショットの読み書きを担うクラス。

#### バイナリレイアウト（GZIP ラップ済み）

```
[ヘッダ]
  magic:              "JPHS" (4 bytes) — フォーマット識別子
  version:            uint16           — 現在 1
  baselineMonth.year: uint16           — ベースライン年
  baselineMonth.month: uint8           — ベースライン月
  baselineEntryCount: uint32           — ベースライン行数
  deltaMonthCount:    uint16           — デルタ月数 (**上限 65535**)

[StringPool]
  poolSize: uint32
  strings[]: uint16(バイト長) + UTF-8バイト列

[ベースラインエントリ]  (baselineEntryCount 件)
  各エントリ: postcodeIdx, prefectureIdx, municipalityIdx, townIdx,
              lgCodeIdx, prefKanaIdx, muniKanaIdx, townKanaIdx
              (すべて uint32 = StringPool インデックス)

[デルタ月]  (newest-first、deltaMonthCount 件)
  month.year:  uint16
  month.month: uint8
  addCount:    uint16  (**上限 65535** — 1ヶ月の追加件数)
  delCount:    uint16  (**上限 65535** — 1ヶ月の削除件数)
  [ADD エントリ] (addCount 件、ベースラインと同形式)
  [DEL エントリ] (delCount 件、ベースラインと同形式)
```

#### StringPool

全文字列（都道府県名・市区町村名・町域名・郵便番号など）を一元管理する文字列プール。エントリは StringPool インデックス（uint32）だけを保持するため、繰り返し出現する文字列の重複を排除できます。

- 約 12 万エントリ × 8 フィールド分の文字列を重複なしで保持
- 実測でプール内ユニーク文字列数は約 2 万件程度

#### uint16 制限に関する警告

`addCount`・`delCount`・`deltaMonthCount` はいずれも `uint16`（最大 **65535**）で格納しています。現在の日本郵便データでは月次変更件数は数十〜数百件、月数も 222 ヶ月であり制限に達することはありません。ただし、将来的に 1 ヶ月あたりの変更件数が 65535 件を超える場合や、スナップショットが累積して 65535 ヶ月を超える場合は、フォーマットのバージョンアップが必要です。

---

## newest-first デルタ格納の理由

デルタは**最新月から古い月へ（newest-first）**の順序でスナップショットファイルに格納されています。

### 復元アルゴリズム

```
ベースライン = 最新月の KEN_ALL（現在の状態）
state = baseline のコピー

for month in deltas.descendingOrder():   // 最新から順に過去へ
    state.removeAll(month.adds)           // この月に追加されたものを除く
    state.addAll(month.dels)              // この月に削除されたものを戻す
    snapshots[month] = state のコピー
```

つまり、**現在の状態から過去を逆算**しています。

### なぜ newest-first か

1. **ベースラインが最新の KEN_ALL**であるため、最新月に近いデルタから順に適用するほうが自然
2. **ファイル書き出し時**に `descendingSet()` でイテレートして書くので、読み出し時は単純に順番通り読むだけで newest-first になる
3. **復元時**（`buildFromSnapshot`）では `deltas.descendingMap()` で逆順適用するため、格納順序と復元順序が一致する

---

## HistoricalPostcodeDictionary の内部構造

```
snapshots
  TreeMap<YearMonth, TreeMap<String, List<PostcodeEntry>>>
  キー: スナップショット月（2007-10 〜 2026-04）
  値: その月時点の postcode → エントリ一覧

postcodeTimeline
  Map<postcode, TreeMap<YearMonth, List<PostcodeEntry>>>
  ある郵便番号の全期間の変遷

addressTimeline
  Map<"pref|muni|town", TreeMap<YearMonth, String(postcode)>>
  ある住所の全期間の郵便番号変遷
```

### 時点検索

`lookup(postcode, at)` は `snapshots.floorEntry(at)` で「指定時点以前の最も近いスナップショット」を取得します。これにより、スナップショットのない中間月でも正しく答えられます。

---

## optional 依存

以下の依存は `<optional>true</optional>` で宣言されており、利用者が明示的に追加しなければクラスパスに含まれません。

| 依存 | 用途 |
|------|------|
| `org.unlaxer:municipality-history` | `PostcodeWithContext` — 変遷に自治体合併コンテキストを付与 |
| `io.javalin:javalin` | `ApiServer` — REST API + 静的 UI |
| `com.fasterxml.jackson.*` | JSON シリアライズ（ApiServer 用） |
| `org.slf4j:slf4j-simple` | ログ出力（ApiServer 用） |

コアライブラリ（辞書構築・検索・スナップショット）は **Java 標準ライブラリのみ**で動作します。

---

## 関連プロジェクト

- [ABRUtils](https://github.com/opaopa6969/ABRUtils) — 住所基盤レジストリ検索ライブラリ（ABR準拠）
- [municipality-history](https://github.com/opaopa6969/municipality-history) — 自治体統廃合履歴（1970〜2028）
