# japanpost-history 仕様書

バージョン: 1.0.1  
作成日: 2026-04-19  
対象リポジトリ: https://github.com/opaopa6969/japanpost-history

---

## 目次

1. [概要](#1-概要)
2. [機能仕様](#2-機能仕様)
3. [データ永続化層](#3-データ永続化層)
4. [ステートマシン](#4-ステートマシン)
5. [ビジネスロジック](#5-ビジネスロジック)
6. [API・外部境界](#6-api外部境界)
7. [UI](#7-ui)
8. [設定](#8-設定)
9. [依存関係](#9-依存関係)
10. [非機能要件](#10-非機能要件)
11. [テスト戦略](#11-テスト戦略)
12. [デプロイ・運用](#12-デプロイ運用)

---

## 1. 概要

### 1.1 プロジェクト概要

japanpost-history は、日本郵便が公開する郵便番号データ（KEN_ALL.CSV）と月次更新ファイル（ADD/DEL）から、**任意時点の郵便番号を引ける時系列辞書**を構築する Java ライブラリです。

Maven Central (`org.unlaxer:japanpost-history`) で公開されています。

### 1.2 解決する課題

- 20年前に契約した住所の郵便番号を**当時の値**で引きたい
- ある住所の郵便番号が**いつ変わったか**を追跡したい
- 過去の郵便番号から**当時の住所名**を逆引きしたい
- 市町村合併などの統廃合イベントと郵便番号変更の**関係を把握**したい

### 1.3 対象データ

| 項目 | 値 |
|------|-----|
| カバー期間 | 2007年10月 ～ 現在（2026年4月時点で223ヶ月） |
| スナップショット数 | 223 |
| ユニーク郵便番号数（全期間） | 121,282件 |
| ユニーク住所数（全期間） | 130,136件 |
| KEN_ALL 最新件数 | 約12.4万件 |
| ADD/DEL 月次ファイル | 222ヶ月分 |

### 1.4 Maven 座標

```xml
<dependency>
    <groupId>org.unlaxer</groupId>
    <artifactId>japanpost-history</artifactId>
    <version>1.0.1</version>
</dependency>
```

### 1.5 ライセンス

MIT License。日本郵便は郵便番号データについて著作権を主張せず、自由に配布・利用可能です。

---

## 2. 機能仕様

### 2.1 辞書の構築（build）

辞書の構築には3つの方法があります。

#### 2.1.1 バイナリスナップショットからロード（推奨）

```java
var dict = HistoricalPostcodeDictionary.loadSnapshot(Path.of("japanpost-history.snapshot"));
```

- 所要時間: 約218ms
- ファイルサイズ: 2.6 MB
- 外部依存: なし

#### 2.1.2 ダウンロード済みCSVから構築

```java
var dict = HistoricalPostcodeDictionary.build(Path.of("/tmp/japanpost-history"));
```

- 所要時間: 約60秒
- データ量: 29 MB（CSVファイル群）
- 前提: `dataDir` 内に `KEN_ALL.CSV`、`ADD_YYMM.CSV`、`DEL_YYMM.CSV` が存在すること

#### 2.1.3 ダウンロード + 構築を一括実行

```java
var dict = HistoricalPostcodeDictionary.downloadAndBuild(Path.of("/tmp/japanpost-history"));
```

- 内部で `JapanPostDownloader.downloadAll()` を呼び出してから `build()` を実行
- 初回実行時のセットアップに適している

### 2.2 検索 API（lookup）

#### 2.2.1 郵便番号 → 住所（時点指定）

```java
List<PostcodeEntry> entries = dict.lookup("0613601", YearMonth.of(2015, 4));
// → [{postcode=0613601, prefecture=北海道, municipality=石狩市, town=厚田区厚田}]
```

- 戻り値: `List<PostcodeEntry>`（1つの郵便番号に複数の住所が対応する場合がある）
- 存在しない場合: 空リスト `List.of()`
- 時点の解釈: `floorEntry(at)` により「指定時点以前で最も近いスナップショット」を使用

#### 2.2.2 住所 → 郵便番号（時点指定）

```java
String zip = dict.lookupByAddress("北海道", "石狩市", "厚田区厚田", YearMonth.of(2020, 1));
// → "0613601"
```

- 引数: 都道府県名、市区町村名、町域名、時点
- 戻り値: 郵便番号文字列、または存在しない場合 `null`

#### 2.2.3 郵便番号の前方一致検索

```java
var results = dict.lookupByPrefix("1450", YearMonth.of(2026, 4), 200);
// → [{1450061, 東京都, 大田区, 石川町}, {1450062, 東京都, 大田区, 北千束}, ...]
```

- 内部実装: `TreeMap.subMap()` による O(log n) 検索
- 上限件数: `limit` パラメータで指定（上限を超えた場合は切り捨て）

#### 2.2.4 全月の住所→郵便番号マップ

```java
NavigableMap<YearMonth, String> history =
    dict.addressPostcodeHistory("北海道", "石狩市", "厚田区厚田");
// → {2007-10=0613601, 2007-11=0613601, ..., 2026-03=0613601}
```

- 戻り値: 月 → 郵便番号のマップ（全スナップショット月分）
- 存在しない住所の場合: `emptyNavigableMap()`

### 2.3 変遷 API（delta）

#### 2.3.1 住所の郵便番号変遷（変化点のみ）

```java
var periods = dict.postcodePeriods("高知県", "高知市", "桟橋通");
// → [7808010 (2007-10 ~ 2019-06), 7818010 (2019-06 ~ present)]
```

- 戻り値: `List<PostcodePeriod>` — 変化点のみ（全月ではなく「いつ変わったか」）
- `PostcodePeriod` フィールド: `from`（開始月）、`to`（終了月、現在まで有効なら `null`）、`postcode`

#### 2.3.2 郵便番号の住所変遷（変化点のみ）

```java
var periods = dict.postcodeAddressPeriods("0613601");
// → [北海道石狩市厚田区厚田 (2007-10 ~ 2026-04),
//    北海道石狩市厚田 (2026-04 ~ present)]
```

- 戻り値: `List<AddressPeriod>` — 住所名が変わった変化点のみ
- `AddressPeriod` フィールド: `from`、`to`（`null` = 現在まで）、`entries`（その期間の `PostcodeEntry` リスト）

#### 2.3.3 2時点間の差分

```java
var diff = dict.diff(YearMonth.of(2026, 3), YearMonth.of(2026, 4));
// diff.addedCount()   → 69
// diff.removedCount() → 69
// diff.added()        → {0613601: [北海道石狩市厚田], ...}
// diff.removed()      → {0613601: [北海道石狩市厚田区厚田], ...}
```

- `Diff` フィールド: `from`、`to`、`added`（郵便番号 → エントリリスト）、`removed`（同）
- 比較単位: `PostcodeEntry.addressKey()` （`pref|muni|town` の複合キー）

### 2.4 ダウンロード（download）

```java
JapanPostDownloader.downloadAll(Path.of("/tmp/japanpost-history"));
// または期間指定
JapanPostDownloader.downloadAll(dataDir, YearMonth.of(2020, 1), YearMonth.of(2026, 4));
```

- KEN_ALL.ZIP および ADD/DEL 月次 ZIP を HTTP で取得・展開
- 既存ファイルのスキップ（増分ダウンロード対応）
- 利用可能な期間: 2007年10月以降

### 2.5 変更検出 API

```java
// 郵便番号が変わった住所の一覧
List<AddressChange> changes = dict.findChangedAddresses();
// → [高知県高知市桟橋通: 7808010 -> 7818010 (2019-06), ...]

// 住所名が変わった郵便番号の一覧
List<PostcodeChange> changes = dict.findChangedPostcodes();
// → [0613601: 2 periods, ...]  (4,821件)
```

### 2.6 メタ情報 API

```java
dict.earliestMonth();       // 2007-10
dict.latestMonth();         // 2026-04
dict.snapshotCount();       // 223
dict.uniquePostcodeCount(); // 121,282
dict.uniqueAddressCount();  // 130,136
```

### 2.7 CLI コマンド

`HistoricalPostcodeDictionary.main()` から以下のコマンドが利用可能です。

| コマンド | 説明 |
|---------|------|
| `download [dir]` | KEN_ALL + 全 ADD/DEL をダウンロード |
| `build [dir]` | CSV から辞書を構築してデモ出力 |
| `run [dir]` | ダウンロード + 構築 + デモを一括実行 |
| `snapshot-write [dir] [output]` | バイナリスナップショットを書き出す |
| `snapshot-load [file]` | スナップショットからロードしてデモ出力 |

```bash
# 実行例
mvn -q compile exec:java -Dexec.args="snapshot-load /tmp/japanpost-history.snapshot"
```

---

## 3. データ永続化層

### 3.1 バイナリスナップショット概要

`DictionarySnapshot` クラスがバイナリスナップショットの読み書きを担当します。

設計方針:
- ベースライン（最新月のフルデータ）+ 月次デルタ（ADD/DEL）の差分格納方式
- 全体を GZIP 圧縮
- 文字列はすべて StringPool で重複排除

### 3.2 バイナリレイアウト

GZIP で圧縮された以下の構造で格納されます。

```
[ヘッダ]
  magic:               "JPHS" (4 bytes) — フォーマット識別子
  version:             uint16           — 現在: 1
  baselineMonth.year:  uint16           — ベースライン年
  baselineMonth.month: uint8            — ベースライン月
  baselineEntryCount:  uint32           — ベースライン行数
  deltaMonthCount:     uint16           — デルタ月数 (上限 65535)

[StringPool]
  poolSize:  uint32
  strings[]: uint16(バイト長) + UTF-8バイト列

[ベースラインエントリ]  (baselineEntryCount 件)
  各エントリ:
    postcodeIdx:      uint32 (StringPool インデックス)
    prefectureIdx:    uint32
    municipalityIdx:  uint32
    townIdx:          uint32
    lgCodeIdx:        uint32
    prefKanaIdx:      uint32
    muniKanaIdx:      uint32
    townKanaIdx:      uint32

[デルタ月]  (newest-first、deltaMonthCount 件)
  month.year:   uint16
  month.month:  uint8
  addCount:     uint16  (上限 65535 — 1ヶ月の追加件数)
  delCount:     uint16  (上限 65535 — 1ヶ月の削除件数)
  [ADD エントリ]  (addCount 件、ベースラインと同形式)
  [DEL エントリ]  (delCount 件、ベースラインと同形式)
```

### 3.3 magic / version チェック

ロード時に以下のチェックを行います。

1. 先頭4バイトが `"JPHS"` でない場合: `IOException("Invalid snapshot magic: ...")`
2. `version != 1` の場合: `IOException("Unsupported snapshot version: ...")`

フォーマット変更時は `VERSION` を 2 以上にインクリメントし、`load()` 内で `version` 値に応じた分岐処理を実装すること。

### 3.4 StringPool

全文字列（都道府県名・市区町村名・町域名・郵便番号・lgCode・カナ）を一元管理する文字列プールです。

```java
static class StringPool {
    void add(String s);       // 追加（重複は無視）
    void freeze();            // 配列を確定
    int indexOf(String s);    // インデックス取得
    String get(int idx);      // 文字列取得
    int size();               // プールサイズ
    void writeTo(DataOutputStream dos);
    static StringPool readFrom(DataInputStream dis);
}
```

特性:
- 約12万エントリ × 8フィールド分の文字列を重複なしで保持
- 実測: ユニーク文字列数は約2万件
- `add()` は `LinkedHashMap` ベース（追加順序を保持）
- `freeze()` 後に `indexOf()` / `get()` が正しく動作する

### 3.5 デルタの newest-first 格納

デルタは**最新月から古い月へ（newest-first）**の順序でファイルに書き込まれます。

書き込み時:
```java
for (YearMonth month : allMonths.descendingSet()) {
    // newest-first でシリアライズ
}
```

読み込み時は単純に順番通りに読み出すため、自動的に newest-first になります。

復元時は `deltas.descendingMap()` を使って逆順（oldest-first）で適用します。

### 3.6 スナップショットの復元アルゴリズム

```
ベースライン = 最新月の KEN_ALL（現在の状態）
state = baseline のコピー

for month in deltas.descendingMap():  // newest-first → 逆順適用
    state.removeAll(month.adds)        // この月に追加されたものを除く
    state.addAll(month.dels)           // この月に削除されたものを戻す
    snapshots[month] = state のコピー
```

このアルゴリズムにより、現在の状態から過去を逆算します。ベースラインが最新の KEN_ALL であるため、最新月に近いデルタから順に適用するほうが自然です。

### 3.7 HistoricalPostcodeDictionary の内部データ構造

```java
// 月 → (郵便番号 → エントリ一覧)
TreeMap<YearMonth, TreeMap<String, List<PostcodeEntry>>> snapshots

// 郵便番号 → (月 → エントリ一覧)
Map<String, TreeMap<YearMonth, List<PostcodeEntry>>> postcodeTimeline

// "pref|muni|town" → (月 → 郵便番号)
Map<String, TreeMap<YearMonth, String>> addressTimeline
```

`buildTimelines()` が全スナップショットを走査して `postcodeTimeline` と `addressTimeline` を横断的に構築します。

---

## 4. ステートマシン

N/A。このライブラリに明示的なステートマシンは存在しません。

辞書オブジェクト（`HistoricalPostcodeDictionary`）は構築後にイミュータブルな状態で使用されます。構築フロー（`build()` / `loadSnapshot()` / `downloadAndBuild()`）は単方向の処理パイプラインです。

---

## 5. ビジネスロジック

### 5.1 newest-first デルタ格納の設計理由

**問題**: バイナリスナップショットにデルタ月を格納する際、どの順序で並べるべきか。

**決定**: newest-first（最新月から古い月への順）。

**理由**:

1. ベースラインが最新の KEN_ALL であるため、最新月に近いデルタから順に適用するほうが直感的
2. 書き出し時に `descendingSet()` でイテレートして書くため、読み出し時は単純に順番通りに読むだけで newest-first になる
3. 復元時（`buildFromSnapshot`）では `deltas.descendingMap()` で逆順適用するため、格納順序と復元順序が一致する

### 5.2 uint16 overflow guard

**問題**: `addCount`・`delCount`・`deltaMonthCount` は `uint16`（最大 65535）で格納されます。これを超えるデータを書き込もうとすると silent overflow（データ破損）が発生します。

**対策**: `DictionarySnapshot.write()` で以下のガードを実装しています。

```java
if (adds.size() > 65535) {
    throw new IllegalStateException(
        "ADD entry count " + adds.size() + " for " + month +
        " exceeds uint16 limit of 65535. Cannot write snapshot without data corruption.");
}
if (dels.size() > 65535) {
    throw new IllegalStateException(
        "DEL entry count " + dels.size() + " for " + month +
        " exceeds uint16 limit of 65535. Cannot write snapshot without data corruption.");
}
```

**現実的なリスク**: 日本郵便の月次変更件数は数十〜数百件程度。65535件を1ヶ月で超えることはない。ただし、将来的にフォーマット変更が必要になった場合は `VERSION` を 2 以上にインクリメントして `uint32` に移行すること。

### 5.3 Zip Slip 防御

**問題**: ZIP 展開時にエントリのパスに `../` などのパストラバーサルが含まれている場合、出力ディレクトリ外にファイルを書き出す「Zip Slip 攻撃」が発生する可能性があります。

**対策**: `JapanPostDownloader.downloadAndExtract()` で以下のガードを実装しています。

```java
Path target = outputDir.resolve(name).normalize();
if (!target.startsWith(outputDir.toAbsolutePath().normalize())) {
    throw new SecurityException(
        "Zip Slip detected: entry '" + entry.getName() +
        "' would extract outside target directory");
}
```

**リスク評価**: 現状では信頼済みドメイン（`https://www.post.japanpost.jp/`）からのみ ZIP を取得し、HTTPS で通信するため中間者攻撃も防止されています。ガードは「将来の拡張」に備えた予防的実装です。

### 5.4 KEN_ALL 複数行分割のマージ

**問題**: KEN_ALL.CSV では町域名が長い場合、括弧が閉じるまで複数行に分割されて記載されます。

**対策**: `PostcodeEntry.mergeContinuationRows()` が連続行を自動結合します。

マージ条件 (`shouldMerge`):
1. 直前のエントリの `town` に未閉じ括弧がある（`hasUnclosedParen(prev.town)` が `true`）
2. `lgCode`、`postcode`、`prefecture`、`municipality` が一致する

```java
private static boolean hasUnclosedParen(String text) {
    int open = 0;
    for (char c : text.toCharArray()) {
        if (c == '（' || c == '(') open++;
        if (c == '）' || c == ')') open--;
    }
    return open > 0;
}
```

### 5.5 半角カタカナ → 全角カタカナ変換

`PostcodeEntry.normalizeKana()` / `PostcodeEntry.halfToFullKana()` が KEN_ALL の半角カタカナフィールドを全角に変換します。

- 濁点（`ﾞ`）の結合: `カ + ﾞ → ガ`（`+1` で対応する濁音）
- 半濁点（`ﾟ`）の結合: `ハ + ﾟ → パ`（`+2` で対応する半濁音）
- 対応文字: カ行・サ行・タ行・ハ行（濁点）、ハ行（半濁点）

### 5.6 時点検索の floorEntry ロジック

`lookup(postcode, at)` は `snapshots.floorEntry(at)` を使用します。

これにより、スナップショットが存在しない中間月（例: スナップショットが 2-monthly の場合）でも、「指定時点以前で最も近いスナップショット」を返すことができます。

### 5.7 前方一致検索の upperBound 計算

`lookupByPrefix(prefix, at, limit)` は `TreeMap.subMap()` で O(log n) の前方一致検索を実現します。

```java
private static String prefixUpperBound(String prefix) {
    if (prefix.isEmpty()) return null;
    char last = prefix.charAt(prefix.length() - 1);
    if (last == Character.MAX_VALUE) return null;
    return prefix.substring(0, prefix.length() - 1) + (char) (last + 1);
}
```

例: `"1450"` → upperBound `"1451"`

---

## 6. API・外部境界

### 6.1 Public API 一覧

`HistoricalPostcodeDictionary` が公開する API です。

#### ファクトリメソッド

| メソッド | 説明 |
|---------|------|
| `build(Path dataDir)` | CSV ディレクトリから辞書を構築 |
| `downloadAndBuild(Path dataDir)` | ダウンロード + 構築を一括実行 |
| `loadSnapshot(Path snapshotFile)` | バイナリスナップショットからロード |

#### 検索 API

| メソッド | 説明 |
|---------|------|
| `lookup(String postcode, YearMonth at)` | 郵便番号 → 住所（時点指定） |
| `lookupByAddress(String pref, String muni, String town, YearMonth at)` | 住所 → 郵便番号（時点指定） |
| `lookupByPrefix(String prefix, YearMonth at, int limit)` | 前方一致検索 |
| `postcodeHistory(String postcode)` | 郵便番号の全月変遷マップ |
| `addressPostcodeHistory(String pref, String muni, String town)` | 住所の全月→郵便番号マップ |
| `postcodePeriods(String pref, String muni, String town)` | 住所の郵便番号変遷（変化点のみ） |
| `postcodeAddressPeriods(String postcode)` | 郵便番号の住所変遷（変化点のみ） |
| `snapshotAt(YearMonth at)` | 指定時点の全郵便番号辞書 |
| `diff(YearMonth from, YearMonth to)` | 2時点間の差分 |

#### 変更検出 API

| メソッド | 説明 |
|---------|------|
| `findChangedAddresses()` | 郵便番号が変わった住所の一覧 |
| `findChangedPostcodes()` | 住所名が変わった郵便番号の一覧 |

#### メタ情報 API

| メソッド | 説明 |
|---------|------|
| `earliestMonth()` | 最古のスナップショット月 |
| `latestMonth()` | 最新のスナップショット月 |
| `snapshotCount()` | スナップショット数 |
| `uniquePostcodeCount()` | ユニーク郵便番号数（全期間） |
| `uniqueAddressCount()` | ユニーク住所数（全期間） |

### 6.2 公開レコード型

#### PostcodeEntry

```java
public record PostcodeEntry(
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

補助メソッド:
- `addressKey()` — `"pref|muni|town"` の複合キー
- `isCatchAll()` — 「以下に掲載がない場合」エントリかどうか
- `normalizeKana()` — 半角カタカナ → 全角カタカナ変換
- `townWithoutParens()` — 括弧内を除去した町域名

#### PostcodePeriod

```java
public record PostcodePeriod(YearMonth from, YearMonth to, String postcode)
```

`to == null` は現在まで有効であることを示します。

#### AddressPeriod

```java
public record AddressPeriod(YearMonth from, YearMonth to, List<PostcodeEntry> entries)
```

`to == null` は現在まで有効であることを示します。

#### Diff

```java
public record Diff(
    YearMonth from, YearMonth to,
    Map<String, List<PostcodeEntry>> added,
    Map<String, List<PostcodeEntry>> removed
) {
    public int addedCount();
    public int removedCount();
}
```

#### AddressChange

```java
public record AddressChange(
    String prefecture, String municipality, String town,
    String oldPostcode, String newPostcode, YearMonth changedAt
)
```

#### PostcodeChange

```java
public record PostcodeChange(String postcode, List<AddressPeriod> periods)
```

### 6.3 JapanPostDownloader

日本郵便サーバーから KEN_ALL + ADD/DEL 月次更新ファイルをダウンロードするクラスです。

```java
public class JapanPostDownloader {
    public static void downloadAll(Path outputDir) throws Exception;
    public static void downloadAll(Path outputDir, YearMonth from, YearMonth to) throws Exception;
}
```

- ベース URL: `https://www.post.japanpost.jp/zipcode/dl/kogaki/zip/`
- HTTP クライアント: Java 標準 `HttpClient`
- ZIP をメモリ上で展開し、CSV ファイルを `outputDir` に書き出す
- 既存ファイルはスキップ（増分ダウンロード対応）
- Zip Slip 防御: 展開先が `outputDir` 外を指す場合 `SecurityException` を投げる

### 6.4 DictionarySnapshot（公開 API）

```java
public class DictionarySnapshot {
    public static void write(Path csvDir, Path outputFile) throws IOException;
    public static HistoricalPostcodeDictionary load(Path snapshotFile) throws IOException;
}
```

### 6.5 REST API エンドポイント

`ApiServer` クラスが提供する REST API です（Javalin 依存が必要）。

| エンドポイント | 説明 |
|---------------|------|
| `GET /api/info` | 辞書メタ情報（期間、スナップショット数等） |
| `GET /api/lookup/{postcode}?at=YYYY-MM` | 郵便番号 → 住所（時点指定） |
| `GET /api/prefix/{prefix}?at=YYYY-MM&limit=N` | 前方一致検索 |
| `GET /api/address/lookup?prefecture=&municipality=&town=&at=` | 住所 → 郵便番号 |
| `GET /api/postcode/{postcode}/history` | 郵便番号の住所変遷（変化点のみ） |
| `GET /api/postcode/{postcode}/periods` | 郵便番号の住所期間リスト |
| `GET /api/address/history?prefecture=&municipality=&town=` | 住所の郵便番号変遷 |
| `GET /api/address/periods?prefecture=&municipality=&town=` | 住所の郵便番号期間リスト |
| `GET /api/diff?from=YYYY-MM&to=YYYY-MM&limit=N` | 2時点間の差分 |
| `GET /api/stats` | 変更統計 |
| `GET /api/postcode/{postcode}/explain` | 郵便番号変遷 + 統廃合コンテキスト |
| `GET /api/address/explain?prefecture=&municipality=&town=` | 住所変遷 + 統廃合コンテキスト |

`/explain` エンドポイントは `municipality-history` 依存が存在しない場合 `503 Service Unavailable` を返します。

---

## 7. UI

### 7.1 静的検索 UI

`src/main/resources/static/index.html` に搭載された単一 HTML ファイルの検索 UI です。

- Javalin の `config.staticFiles.add("/static", Location.CLASSPATH)` でサービング
- デフォルトポート: `7070`
- 機能: 郵便番号検索、前方一致検索、住所検索

### 7.2 UI の制約

N/A。UI は静的ファイルのみで構成されており、複雑なフロントエンドビルドや特別な設定は不要です。

---

## 8. 設定

### 8.1 ESTAT_APP_ID 環境変数

e-Stat API の appId を環境変数から設定できます。

```bash
export ESTAT_APP_ID=your_app_id_here
```

`EstatConfig.getAppId()` が以下の優先順位で appId を返します:

1. 環境変数 `ESTAT_APP_ID` が設定されていればその値を使用
2. 未設定の場合はデフォルト値（後方互換のためのハードコード値）にフォールバック

```java
public static String getAppId() {
    String envValue = System.getenv("ESTAT_APP_ID");
    if (envValue != null && !envValue.isBlank()) {
        return envValue;
    }
    return DEFAULT_APP_ID;
}
```

本番環境や高頻度の利用では、e-Stat（https://www.e-stat.go.jp/）で個人の appId を取得して環境変数に設定してください。

### 8.2 データディレクトリ

CLI 実行時のデフォルトデータディレクトリ: `/tmp/japanpost-history`

コマンドライン引数で変更可能:
```bash
mvn -q compile exec:java -Dexec.args="build /path/to/csv-dir"
```

### 8.3 API サーバーポート

デフォルトポート: `7070`

`ApiServer.main()` の第2引数で変更可能:
```java
new ApiServer(dict).start(8080);
```

---

## 9. 依存関係

### 9.1 コアライブラリの依存

**ゼロ依存**（Java 標準ライブラリのみ）。

辞書構築・検索・スナップショット読み書きは以下の標準 API のみで動作します:
- `java.nio.file` — ファイル I/O
- `java.net.http` — HTTP クライアント（JapanPostDownloader）
- `java.util.zip` — GZIP / ZIP 展開
- `java.time.YearMonth` — 時点管理
- `java.util.TreeMap` / `HashMap` / `LinkedHashMap` — データ構造

### 9.2 オプション依存

以下の依存は `<optional>true</optional>` で宣言されています。利用者が明示的に追加しない限りクラスパスに含まれません。

| 依存 | バージョン | 用途 |
|------|-----------|------|
| `org.unlaxer:municipality-history` | 1.0.0 | `PostcodeWithContext` — 変遷に自治体合併コンテキストを付与 |
| `io.javalin:javalin` | 5.6.3 | `ApiServer` — REST API + 静的 UI |
| `com.fasterxml.jackson.core:jackson-databind` | 2.18.3 | JSON シリアライズ（ApiServer 用） |
| `com.fasterxml.jackson.datatype:jackson-datatype-jsr310` | 2.18.3 | `YearMonth` など Java Time の JSON シリアライズ |
| `org.slf4j:slf4j-simple` | 2.0.9 | ログ出力（ApiServer 用） |

### 9.3 ABRUtils パターンの踏襲

japanpost-history は ABRUtils と共通のスナップショット戦略を採用しています。

1. CSV (生データ) からインメモリ辞書を構築
2. String Pool + GZIP でバイナリスナップショットに書き出し
3. スナップショットから高速ロード（DB 不要）

### 9.4 municipality-history との連携

`PostcodeWithContext` クラスが `municipality-history` との連携点です。

```java
var dict = HistoricalPostcodeDictionary.loadSnapshot(snapshotPath);
var muni = MunicipalityHistory.loadBundled();
var ctx = new PostcodeWithContext(dict, muni);

// 郵便番号変遷 + 統廃合理由
PostcodeExplanation explanation = ctx.explainPostcodeChanges("0613601");

// 住所変遷 + 統廃合理由
AddressExplanation explanation = ctx.explainAddressChanges("北海道", "石狩市", "厚田区厚田");

// 指定期間の変更 + 同時期の統廃合を紐づけ
List<PostcodeExplanation> results = ctx.findChangesWithContext(YearMonth.of(2026, 3), YearMonth.of(2026, 4));
```

`MunicipalityHistory.findByName(municipalityName)` で自治体名から関連する統廃合イベントを検索し、郵便番号の住所変遷と紐づけます。

`ApiServer` は起動時に `MunicipalityHistory.loadBundled()` の呼び出しを試みます。失敗した場合（`municipality-history` がクラスパスにない場合）は `context = null` となり、`/explain` エンドポイントは 503 を返します。

### 9.5 テスト依存

| 依存 | バージョン | scope |
|------|-----------|-------|
| `org.junit.jupiter:junit-jupiter-api` | 5.10.3 | test |
| `org.junit.jupiter:junit-jupiter-engine` | 5.10.3 | test |
| `maven-surefire-plugin` | 3.2.5 | build |

---

## 10. 非機能要件

### 10.1 スナップショットサイズ

| 方式 | サイズ | ロード時間 |
|------|--------|-----------|
| CSV (222ファイル) | 29 MB | 約60秒 |
| バイナリスナップショット | **2.6 MB** | **218 ms** |

バイナリスナップショットは CSV の約 **11分の1** のサイズを実現します。圧縮効果の内訳:
- StringPool による文字列の重複排除（約12万エントリ × 8フィールドを約2万ユニーク文字列に集約）
- デルタ格納方式（222ヶ月分の全スナップショットではなく、ベースライン + 差分のみ保持）
- GZIP 圧縮

### 10.2 スループット・メモリ

- ロード後の検索レイテンシ: 単体クエリは O(log n)
- インメモリフットプリント: 全スナップショット展開後のメモリ使用量（実測値は未記載）
- `buildTimelines()` は全 223 スナップショット × 全郵便番号をスキャンするため、初回構築時に数分を要する可能性がある

### 10.3 Java バージョン要件

- Java 21 以上が必要
- Record、Sealed クラス、Pattern Matching 等を活用

### 10.4 データカバレッジの制約

- **2007年10月以前のデータなし**: 日本郵便サーバーおよび Wayback Machine にも残っていない
- **UTF-8 版未対応**: `utf_all.csv`（2023年6月以降）は現在非対応（MS932 版のみ）
- **KEN_ALL の複数行分割**: 括弧が閉じるまで複数行に及ぶ場合は自動マージ対応済み

### 10.5 スレッド安全性

辞書オブジェクトは構築後イミュータブルです。複数スレッドからの読み取りは安全です。構築処理（`build()`、`loadSnapshot()`）はスレッドセーフではありません（通常は単一スレッドで実行します）。

### 10.6 uint16 上限

`addCount`・`delCount`・`deltaMonthCount` は uint16（最大 65535）です。現在のデータ規模では問題ありません。将来的に 1ヶ月の変更件数が 65535 件を超える場合は、フォーマット `version` を上げて `uint32` に移行する必要があります。

---

## 11. テスト戦略

### 11.1 テストクラス一覧

| クラス | テスト数 | 対象 |
|--------|---------|------|
| `PostcodeEntryTest` | 9 | CSV パース、カナ変換、マージ、補助メソッド |
| `DictionarySnapshotTest` | 4 | StringPool、uint16 overflow guard |
| `HistoricalPostcodeDictionaryTest` | 4 | 辞書構築・検索・デルタ復元 |
| `EstatConfigTest` | 2 | 環境変数 fallback |
| **合計** | **19** | |

### 11.2 PostcodeEntryTest

テスト対象: `PostcodeEntry` クラスの単体テスト

| テスト名 | 検証内容 |
|---------|---------|
| `fromCsvLine_parsesStandardLine` | 標準 CSV 行の正しいパース（全フィールド検証） |
| `fromCsvLine_returnsNullForShortLine` | カラム数不足の CSV 行は `null` を返す |
| `isCatchAll_trueForCatchAllTown` | 「以下に掲載がない場合」で `true` |
| `isCatchAll_falseForNormalTown` | 通常の町域名で `false` |
| `normalizeKana_convertsHalfToFullKatakana` | 半角カタカナ → 全角変換（3フィールド、他フィールドは不変） |
| `townWithoutParens_removesParentheses` | 括弧内文字列の除去 |
| `addressKey_format` | `"pref|muni|town"` フォーマットの確認 |
| `mergeContinuationRows_mergesUnclosedParens` | 未閉じ括弧の連続行マージ |
| `halfToFullKana_dakutenCombination` | 濁点・半濁点の結合処理（`ｶﾞ→ガ`、`ﾊﾟ→パ`） |
| `parseCsvLine_handlesQuotedFields` | クォート内のカンマを正しく処理 |

### 11.3 DictionarySnapshotTest

テスト対象: `DictionarySnapshot` クラスの StringPool および overflow guard

| テスト名 | 検証内容 |
|---------|---------|
| `stringPool_addAndGet` | 重複追加は無視、インデックスの正確性 |
| `stringPool_writeAndRead_roundTrip` | バイナリシリアライズ → デシリアライズの完全性（ASCII + 日本語） |
| `stringPool_emptyStringRoundTrip` | 空文字列のラウンドトリップ |
| `write_throwsWhenAddCountExceedsUint16` | 65536件の ADD エントリで `IllegalStateException`、メッセージに "65535" を含む |

### 11.4 HistoricalPostcodeDictionaryTest

テスト対象: `HistoricalPostcodeDictionary` の構築・検索・デルタ適用

| テスト名 | 検証内容 |
|---------|---------|
| `build_andLookup_fromMinimalCsv` | 最小 CSV からの辞書構築、`lookup()` の基本動作 |
| `build_withDelta_restoresPastState` | ADD ファイル適用後、その月以前にはエントリが存在しないことを確認 |
| `snapshotAt_veryOldDateReturnsEmpty` | 非常に古い時点では空マップを返す |
| `lookupByPrefix_returnsMatchingEntries` | 前方一致検索が正確に動作する（プレフィックス外のエントリを含まない） |

### 11.5 EstatConfigTest

テスト対象: `EstatConfig` の環境変数 fallback ロジック

| テスト名 | 検証内容 |
|---------|---------|
| `getAppId_returnsNonBlank` | 常に非 null・非空白の文字列を返す |
| `getAppId_defaultFallbackIsKnownValue` | `ESTAT_APP_ID` 未設定時はデフォルト値、設定時は環境変数値を返す |

### 11.6 テストデータの方針

- **最小 CSV 方式**: テスト用 CSV は `@TempDir` に MS932 エンコードで書き出す
- **実データ非依存**: 外部ダウンロードや本物の CSV ファイルへの依存なし
- **境界値テスト**: uint16 上限（65535）の境界チェック（65536件で例外）

### 11.7 CI 設定

`.github/workflows/ci.yml` で `mvn test` を push/PR 時に自動実行します。

### 11.8 テストのスコープ外

以下は現バージョンでのテスト対象外です:

- `JapanPostDownloader`: 外部 HTTP アクセスが必要なためインテグレーションテストのみ対象
- `ApiServer`: Javalin オプション依存のため除外
- `PostcodeWithContext`: `municipality-history` オプション依存のため除外
- 本物の KEN_ALL.CSV を使ったエンドツーエンドテスト

---

## 12. デプロイ・運用

### 12.1 Maven Central 公開

Maven Central に公開済みです（groupId: `org.unlaxer`、artifactId: `japanpost-history`）。

公開手順:
```bash
mvn deploy
```

Central Publishing Plugin (`org.sonatype.central:central-publishing-maven-plugin:0.7.0`) を使用します。GPG 署名が必要です。

### 12.2 バージョン管理

Semantic Versioning に準拠します。

| バージョン | 内容 |
|-----------|------|
| 1.0.0 | 初版。辞書構築・検索・スナップショット・REST API・PostcodeWithContext |
| 1.0.1 | ユニットテスト追加、CI 設定、EstatConfig、uint16 overflow guard、Zip Slip 防御 |

**後方互換性の保証**:
- 既存の public API に変更なし
- スナップショットフォーマットに変更なし（`version: 1` を維持）

### 12.3 スナップショットの配布

バイナリスナップショットは GitHub Releases で配布します。

```bash
curl -L -o /tmp/japanpost-history.snapshot \
  https://github.com/opaopa6969/japanpost-history/releases/download/v1.0.0/japanpost-history.snapshot
```

### 12.4 スナップショットの更新手順

新しい月のデータが公開されたら以下の手順でスナップショットを更新します:

```bash
# 1. 新しい月次ファイルを追加ダウンロード（既存ファイルはスキップ）
mvn -q compile exec:java -Dexec.args="download /tmp/japanpost-history"

# 2. スナップショットを再生成
mvn -q compile exec:java \
  -Dexec.args="snapshot-write /tmp/japanpost-history /tmp/japanpost-history.snapshot"
```

### 12.5 Docker

`Dockerfile` および `docker-entrypoint.sh` が提供されています。

### 12.6 ビルドコマンド

| コマンド | 用途 |
|---------|------|
| `mvn -q compile` | コンパイルのみ |
| `mvn test` | テスト実行 |
| `mvn package -DskipTests -Dgpg.skip=true` | JAR 生成（テスト・署名スキップ） |
| `mvn install -DskipTests -Dgpg.skip=true` | ローカルリポジトリにインストール |
| `mvn deploy` | Maven Central に公開 |

### 12.7 運用上の注意

- **月次更新**: 日本郵便は毎月1回、月初に前月分の ADD/DEL ファイルを公開します。定期的にスナップショットを再生成することを推奨します
- **KEN_ALL の廃止リスク**: 日本郵便が配布形式を変更した場合、`JapanPostDownloader` の URL やパーサーの更新が必要です
- **UTF-8 版移行**: 2023年6月以降、日本郵便は `utf_all.csv`（UTF-8版）を提供しています。現バージョンは MS932 版のみ対応であるため、将来的に UTF-8 版への移行を検討してください
- **2007年10月以前のデータ**: 取得不可能（日本郵便サーバーおよび Wayback Machine にも残っていない）

---

## 付録 A. データソース詳細

### A.1 ファイル種別と URL

| ファイル名 | 内容 | URL |
|------------|------|-----|
| `KEN_ALL.CSV` | 最新フルデータ（約12.4万件） | `https://www.post.japanpost.jp/zipcode/dl/kogaki/zip/ken_all.zip` |
| `ADD_YYMM.CSV` | 当月の追加エントリ（新設・変更） | `https://www.post.japanpost.jp/zipcode/dl/kogaki/zip/add_YYMM.zip` |
| `DEL_YYMM.CSV` | 当月の削除エントリ（廃止・変更前） | `https://www.post.japanpost.jp/zipcode/dl/kogaki/zip/del_YYMM.zip` |

`YYMM` は西暦下2桁 + 月2桁。例: `2604` = 2026年4月。

### A.2 ファイルフォーマット詳細

**文字コード**: MS932（Shift_JIS 拡張）。2023年6月以降は UTF-8 版（`utf_all.csv`）も提供されているが、現バージョンは MS932 版のみ対応。

**CSV カラム構成**（KEN_ALL.CSV / ADD_YYMM.CSV / DEL_YYMM.CSV 共通）:

| カラム番号 | フィールド名 | 説明 |
|-----------|-------------|------|
| 0 | lgCode | 全国地方公共団体コード（5桁） |
| 1 | (旧郵便番号) | 5桁旧郵便番号（未使用） |
| 2 | postcode | 7桁郵便番号（ハイフンなし） |
| 3 | prefectureKana | 都道府県名カナ（半角カタカナ） |
| 4 | municipalityKana | 市区町村名カナ（半角カタカナ） |
| 5 | townKana | 町域名カナ（半角カタカナ） |
| 6 | prefecture | 都道府県名 |
| 7 | municipality | 市区町村名 |
| 8 | town | 町域名 |

`PostcodeEntry.fromCsvLine()` がインデックス 0, 2, 3, 4, 5, 6, 7, 8 を使用します（インデックス 1 は使用しません）。

### A.3 年月コードの解析

ファイル名の `YYMM` は以下のルールで `YearMonth` に変換します。

```java
int year = yy >= 80 ? 1900 + yy : 2000 + yy;
// 例: 07 → 2007, 26 → 2026, 80 → 1980
```

現在（2026年時点）では `yy >= 80` の条件が実際に使われることはありませんが、将来の境界ケースに備えています。

### A.4 更新頻度

日本郵便は毎月1回、月初に前月分の ADD/DEL ファイルを公開します。KEN_ALL は常に最新月の状態が反映されています。

| 対象 | 頻度 |
|------|------|
| KEN_ALL.CSV | 毎月更新（常に最新） |
| ADD_YYMM.CSV / DEL_YYMM.CSV | 毎月1回追加（翌月初旬公開） |

---

## 付録 B. 実装クラス詳細

### B.1 PostcodeEntry

#### CSV パースの詳細

`PostcodeEntry.fromCsvLine(String line)` は `parseCsvLine()` を呼び出して CSV をパースします。

`parseCsvLine()` の特徴:
- RFC 4180 準拠のクォート処理
- クォート内のカンマを正しく処理（例: `"world, foo"` → `world, foo`）
- ダブルクォートのエスケープ（`""` → `"`）
- カラム数が 9 未満の場合 `fromCsvLine()` は `null` を返す
- `postcode` が空白のみの場合も `null` 扱い

#### 複数行分割マージのアルゴリズム

```
input: rawRows (List<PostcodeEntry>)
output: merged (List<PostcodeEntry>)

for each current in rawRows:
    if merged is empty:
        merged.add(current)
        continue
    prev = merged.last()
    if shouldMerge(prev, current):
        merged.replaceLast( new PostcodeEntry(
            lgCode=prev.lgCode, postcode=prev.postcode,
            prefKana=prev.prefKana, muniKana=prev.muniKana,
            townKana = prev.townKana + current.townKana,
            pref=prev.pref, muni=prev.muni,
            town = prev.town + current.town
        ))
    else:
        merged.add(current)
```

マージ条件 (`shouldMerge`):
1. `hasUnclosedParen(prev.town)` が `true`
2. `lgCode` が一致
3. `postcode` が一致
4. `prefecture` が一致
5. `municipality` が一致

#### 半角カタカナ → 全角カタカナの変換テーブル

変換は `HALF_KANA` 文字列と `FULL_KANA` 文字列のインデックス対応で行います。濁点（`ﾞ`）や半濁点（`ﾟ`）は次の文字と結合して変換します。

- 濁点適用可能文字（`DAKUTEN_BASE`）: カキクケコ、サシスセソ、タチツテト、ハヒフヘホ、ウ（→ ヴ）
- 半濁点適用可能文字（`HANDAKUTEN_BASE`）: ハヒフヘホ

### B.2 DictionarySnapshot

#### write() の処理フロー

```
1. KEN_ALL.CSV をロードしてベースライン取得
2. ADD_YYMM.CSV / DEL_YYMM.CSV を全月分ロード
3. baselineMonth = addByMonth.lastKey().plusMonths(1)
4. StringPool を構築（ベースライン + 全 ADD + 全 DEL）
5. pool.freeze()
6. GZIPOutputStream → DataOutputStream に書き出し:
   a. ヘッダ（magic / version / baselineMonth / count / deltaMonthCount）
   b. StringPool
   c. ベースラインエントリ
   d. デルタ月（newest-first = descendingSet() でイテレート）
      - uint16 overflow guard チェック
      - month / addCount / delCount
      - ADD エントリ
      - DEL エントリ
7. ファイルサイズを出力
```

#### load() の処理フロー

```
1. GZIPInputStream → DataInputStream で読み込み
2. magic / version チェック
3. ヘッダ情報を読み取り（baselineMonth, baselineCount, deltaMonthCount）
4. StringPool を復元
5. ベースラインエントリを復元（baselineCount 件）
6. デルタ月を復元（deltaMonthCount 件）
   - month / addCount / delCount
   - adds（LinkedHashSet）
   - dels（LinkedHashSet）
7. HistoricalPostcodeDictionary.buildFromSnapshot() を呼び出し
```

#### エントリのシリアライズ順序

書き込み順: `postcode, prefecture, municipality, town, lgCode, prefKana, muniKana, townKana`

読み取り後の PostcodeEntry コンストラクタ呼び出し順: `lgCode, postcode, prefKana, muniKana, townKana, pref, muni, town`

（フィールド順がコンストラクタと異なるため、`readEntry()` では変数に保持してからコンストラクタを呼び出します）

### B.3 HistoricalPostcodeDictionary

#### buildFromDirectory() の処理フロー

```
1. KEN_ALL.CSV をロード → baseline (Set<PostcodeEntry>)
2. ADD_YYMM.CSV を TreeMap<YearMonth, Path> に列挙
3. DEL_YYMM.CSV を TreeMap<YearMonth, Path> に列挙
4. latestMonth = addFiles.lastKey().plusMonths(1)
5. snapshots[latestMonth] = toPostcodeMap(baseline)
6. state = baseline のコピー
7. allMonths = ADD + DEL の全月の union
8. for month in allMonths.descendingSet():
      state.removeAll(loadCsv(addFiles[month]))  // ADDを引く
      state.addAll(loadCsv(delFiles[month]))      // DELを足す
      snapshots[month] = toPostcodeMap(state)
9. buildTimelines()
```

#### toPostcodeMap() の変換

```java
private static TreeMap<String, List<PostcodeEntry>> toPostcodeMap(Set<PostcodeEntry> entries) {
    return entries.stream().collect(Collectors.groupingBy(
            PostcodeEntry::postcode, TreeMap::new, Collectors.toList()));
}
```

`Set<PostcodeEntry>` を `TreeMap<String(postcode), List<PostcodeEntry>>` に変換します。1つの郵便番号に複数の住所エントリが対応する場合があります。

#### buildTimelines() の処理フロー

```
for each (month, snapshot) in snapshots:
    for each (postcode, entries) in snapshot:
        postcodeTimeline[postcode][month] = entries
        for each entry in entries:
            addressTimeline[entry.addressKey()][month] = postcode
```

全スナップショット × 全郵便番号 × 全エントリを走査するため、O(snapshots × entries) の時間がかかります。

### B.4 ApiServer

#### CORS・認証

現在の実装では CORS 設定および認証・認可の仕組みはありません。ローカル・内部利用を想定した設計です。

#### エラーハンドリング

| 条件 | レスポンス |
|------|-----------|
| `at` パラメータが不正な YYYY-MM 形式 | デフォルト値（最新月または最古月）にフォールバック |
| `prefecture`/`municipality`/`town` が欠落 | `400 Bad Request` + `{"error": "..."}` |
| municipality-history が利用不可 | `503 Service Unavailable` + `{"error": "municipality-history not available"}` |

#### `at` パラメータのデフォルト値

| エンドポイント | `at` 未指定時のデフォルト |
|---------------|------------------------|
| `/api/lookup/{postcode}` | `dict.latestMonth()` |
| `/api/prefix/{prefix}` | `dict.latestMonth()` |
| `/api/address/lookup` | `dict.latestMonth()` |
| `/api/diff` `from` | `dict.earliestMonth()` |
| `/api/diff` `to` | `dict.latestMonth()` |

### B.5 PostcodeWithContext

#### explainPostcodeChanges() のアルゴリズム

```
1. dict.postcodeAddressPeriods(postcode) で住所変遷を取得
2. 各 period の各 entry の municipality 名で muni.findByName() を呼び出し
3. 得られた MunicipalityChange を distinct() + effectiveDate 昇順ソート
4. PostcodeExplanation(postcode, periods, relatedChanges) を返す
```

#### explainAddressChanges() のアルゴリズム

```
1. dict.postcodePeriods(pref, muni, town) で郵便番号変遷を取得
2. muni.findByName(municipality) で自治体名から統廃合を検索
   → 結果が空の場合は muni.findByName(town) を試みる
3. prefecture でフィルタリング（同名の他県自治体を除外）
4. effectiveDate 昇順ソート
5. AddressExplanation(address, periods, relatedChanges) を返す
```

---

## 付録 C. 設計決定記録（ADR）

### ADR-1: バイナリスナップショットフォーマットの設計

**状況**: 222ヶ月分の全スナップショットを効率よく永続化する方法が必要でした。

**検討した選択肢**:

1. **全スナップショットをそのまま保存**: 223ヶ月 × 12万件 = 約2700万行を保存。サイズが大きすぎる
2. **SQLite などの組み込みDB**: 依存が増える、起動時の初期化が複雑
3. **ベースライン + デルタの差分格納（採用）**: ベースライン12万行 + デルタ約1.7万行のみ保存。GZIP 圧縮後 2.6 MB

**決定**: 方式3（ベースライン + デルタ）を採用。GZIP ラップ + StringPool で圧縮効率を最大化。

**結果**: CSV 29 MB → スナップショット 2.6 MB（11分の1）、ロード時間 60秒 → 218ms。

### ADR-2: String Pool による重複文字列の排除

**状況**: 郵便番号エントリはフィールドに多数の重複文字列を持ちます（例: 「北海道」は何千行にも登場）。

**問題**: エントリごとに文字列をそのまま格納すると、ファイルサイズが増大します。

**決定**: `StringPool` クラスで全文字列を一元管理し、エントリは StringPool インデックス（uint32）のみを保持する。

**効果**: 約12万エントリ × 8フィールド = 約96万の文字列参照を、約2万のユニーク文字列に圧縮。

### ADR-3: newest-first デルタ格納の採用理由

**状況**: バイナリスナップショットのデルタ月の格納順序を決める必要がありました。

**選択肢**:
- oldest-first: 2007年10月のデルタから順に格納
- newest-first（採用）: 最新月から格納

**決定**: newest-first を採用。

**理由**: ベースラインが最新の KEN_ALL である。復元時は `deltas.descendingMap()` で逆順適用するため、格納順と復元順が一致し実装がシンプルになる。

### ADR-4: コアライブラリのゼロ依存方針

**状況**: ライブラリとして配布する際、利用者への依存の押し付けを最小化したい。

**決定**: コアライブラリ（辞書構築・検索・スナップショット）は Java 標準ライブラリのみで動作させる。外部依存はすべて `<optional>true</optional>` で宣言する。

**理由**: 利用者が REST API や municipality-history 連携を必要としない場合、不要な依存を持ち込まない。

**結果**: `japanpost-history` を依存に追加するだけで、追加的な transitive dependency なしに全コア機能が利用可能。

### ADR-5: uint16 overflow guard の追加（v1.0.1）

**状況**: v1.0.0 では `dos.writeShort(adds.size())` の前にサイズチェックがなく、65535 件を超えた場合に silent overflow によるデータ破損が起きる可能性がありました。

**決定**: `IllegalStateException` を投げる早期チェックを追加。

**理由**: 現実的には発生しないが、データ破損は検知困難であり、予防的に例外を投げることで問題を明示化する。

### ADR-6: Zip Slip ガードの追加（v1.0.1）

**状況**: v1.0.0 では `outputDir.resolve(name)` の前に `normalize()` と `startsWith()` チェックがなく、悪意ある ZIP エントリ名（`../../../etc/passwd` 等）で出力ディレクトリ外に書き込まれる可能性がありました。

**決定**: パストラバーサルチェックを追加し、検出時に `SecurityException` を投げる。

**理由**: 現状の利用（信頼済みドメインからの HTTPS ダウンロード）では実際のリスクは低いが、ライブラリとして配布する以上、セキュリティベストプラクティスに従うべきである。

---

## 付録 D. 関連プロジェクトとの連携仕様

### D.1 municipality-history との連携

`municipality-history` ライブラリは自治体統廃合履歴（1970年〜2028年）を管理するライブラリです。

**連携インターフェース**:

```java
// MunicipalityHistory の主要 API
MunicipalityHistory muni = MunicipalityHistory.loadBundled();
int muni.size();  // レコード数
List<MunicipalityChange> muni.findByName(String name);  // 自治体名で検索

// MunicipalityChange の主要フィールド
LocalDate c.effectiveDate();  // 統廃合日
String c.lgCode();            // 地方公共団体コード
String c.prefecture();        // 都道府県名
String c.fullName();          // 自治体名
String c.reason();            // 統廃合理由
```

**japanpost-history 側の利用方法**:

`PostcodeWithContext` が `MunicipalityHistory.findByName(municipalityName)` を呼び出すことで、郵便番号の住所変遷に紐づく統廃合イベントを取得します。現バージョンでは**自治体名の文字列一致**で検索しており、lgCode による精密な紐づけは BACKLOG として管理されています。

### D.2 ABRUtils との連携（BACKLOG）

現在は以下の連携が計画されています（未実装）:

- **PostcodeAccuracyChecker の KEN_ALL fallback 強化**: 現在は最新 KEN_ALL のみ使用。japanpost-history のスナップショットを使えば、過去の郵便番号でも照合可能になる
- **ABRUtils の REST API に郵便番号変遷エンドポイントを追加**: ABR の free-search 結果に「この郵便番号は2019年に変更されました」的な情報を付与
- **住所正規化の強化**: 旧市町村名を現在の名前にマッピングするテーブルを japanpost-history + municipality-history から構築

---

## 付録 E. 既知の制限事項と将来の課題

### E.1 データの制限

| 制限 | 詳細 |
|------|------|
| 2007年10月以前 | 日本郵便サーバーおよび Wayback Machine にも ADD/DEL ファイルが残っておらず、取得不可能 |
| UTF-8 版未対応 | 2023年6月以降の `utf_all.csv` は未対応（MS932 版のみ） |
| 継続行の完全対応 | 括弧が閉じるまでのマージは実装済みだが、複雑なケースへの完全対応は BACKLOG |

### E.2 スナップショットフォーマットの制約

| 制約 | 現状 | 将来対応 |
|------|------|---------|
| `addCount` / `delCount` | uint16（最大 65535） | フォーマット version 2 で uint32 に拡張 |
| `deltaMonthCount` | uint16（最大 65535 ≈ 5461年分） | 現実的に問題なし |

### E.3 BACKLOG（未実装機能）

詳細は `BACKLOG.md` を参照してください。

- **合併前の旧市町村名での検索**: 「厚田村」で検索 → 現在の「石狩市厚田」にフォールバック
- **統廃合発生日と郵便番号変更日の自動紐づけ**: 現在は名前マッチ。地域コードでの紐づけに改善
- **スナップショットのインクリメンタル更新**: 毎月差分だけ追加
- **ABR との差分分析**: ABR にあって KEN_ALL にない住所、その逆を可視化

---

---

## 付録 F. コーディング規約・開発ガイドライン

### F.1 言語・スタイル

- **Java 21**: record、sealed class、pattern matching、virtual threads を活用する
- **データモデル**: `record` で定義する。イミュータブル。フィールドは primitive 優先
- **null 回避**: 空文字（`""`）や `List.of()` を使い、null は避ける。外部入力の null チェックは境界で行う
- **エラー処理**: 検査例外は呼び出し元に投げる。内部ログは `System.out` / `System.err`
- **命名**: 日本語 JavaDoc 可。メソッド名・変数名は英語

### F.2 パッケージ構造（理想）

プロジェクトの現在の構造はフラットですが、ABRUtils パターンでは以下の責務分離を推奨しています:

```
src/main/java/org/unlaxer/japanpost/
├── model/       # データモデル (record) — PostcodeEntry 等
├── parser/      # パース・変換ロジック — CSV パース等
├── store/       # DB/ファイルストア — DictionarySnapshot 等
├── query/       # 検索・クエリ API — HistoricalPostcodeDictionary 等
├── api/         # REST API (Javalin, optional) — ApiServer 等
└── App.java     # CLI エントリポイント
```

現バージョンはすべてのクラスがルートパッケージに配置されています。

### F.3 スナップショット戦略（ABRUtils 共通パターン）

1. CSV（生データ）からインメモリ辞書を構築して動くものを作る
2. HashMap/TreeMap/カラムナー配列のインメモリ辞書に最適化
3. String Pool + GZIP でバイナリスナップショットに書き出し
4. スナップショットからの高速ロード（DB 不要）

### F.4 REST API の設計パターン（ABRUtils 共通）

- 依存は `<optional>true</optional>`
- `GET /api/info` — メタ情報
- `GET /api/<resource>/{id}` — 単体取得
- `GET /api/<resource>/search?q=` — 検索
- JSON レスポンスは `Map.of()` / `LinkedHashMap` で構築
- 静的 UI は `src/main/resources/static/index.html`

### F.5 Git コミット規約

- コミットメッセージは英語、1〜2行で要点を書く
- `Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>` を末尾に追加
- 機能単位でコミットし、大きくまとめすぎない

---

## 付録 G. プロジェクト全体のファイル構成

```
japanpost-history/
├── pom.xml                          # Maven 設定（groupId: org.unlaxer, version: 1.0.1）
├── README.md                        # プロジェクト概要・使い方（日本語）
├── CHANGELOG.md                     # バージョン履歴
├── BACKLOG.md                       # 未着手タスク・気づき
├── CLAUDE.md                        # コーディング規約・開発ガイドライン
├── Dockerfile                       # Docker イメージ定義
├── docker-entrypoint.sh             # Docker エントリポイントスクリプト
├── spec/
│   └── SPEC.md                      # 本仕様書
├── docs/
│   ├── architecture.md              # アーキテクチャ設計
│   ├── data-source.md               # データソース詳細
│   ├── getting-started.md           # はじめに（チュートリアル）
│   └── decisions/
│       ├── uint16-delta.md          # ADR: uint16 デルタカウンタ
│       └── zip-slip.md              # セキュリティ: Zip Slip 対応方針
├── src/
│   ├── main/
│   │   ├── java/org/unlaxer/japanpost/
│   │   │   ├── HistoricalPostcodeDictionary.java  # メイン辞書クラス（CLI エントリポイント）
│   │   │   ├── DictionarySnapshot.java            # バイナリスナップショット読み書き
│   │   │   ├── PostcodeEntry.java                 # 郵便番号エントリ record
│   │   │   ├── JapanPostDownloader.java           # 日本郵便 HTTP ダウンローダー
│   │   │   ├── PostcodeWithContext.java           # municipality-history 連携
│   │   │   ├── ApiServer.java                     # Javalin REST API + UI サーバー
│   │   │   └── EstatConfig.java                   # e-Stat appId 設定ユーティリティ
│   │   └── resources/
│   │       └── static/
│   │           └── index.html                     # 検索 UI
│   └── test/
│       └── java/org/unlaxer/japanpost/
│           ├── PostcodeEntryTest.java             # PostcodeEntry ユニットテスト
│           ├── DictionarySnapshotTest.java        # DictionarySnapshot ユニットテスト
│           ├── HistoricalPostcodeDictionaryTest.java  # 辞書構築・検索テスト
│           └── EstatConfigTest.java              # EstatConfig テスト
└── target/                          # Maven ビルド出力（gitignore 推奨）
    ├── japanpost-history-1.0.1.jar
    ├── japanpost-history-1.0.1-sources.jar
    ├── japanpost-history-1.0.1-javadoc.jar
    └── ...
```

---

## 付録 H. 実際のデータ例とユースケース解説

### H.1 北海道石狩市厚田区厚田 の郵便番号変遷

石狩市厚田地区では2026年4月に「厚田区」が廃止され、町域名が変更されました。

```
2007-10 〜 2026-03: 〒061-3601 → 北海道石狩市厚田区厚田
2026-04 〜 現在:    〒061-3601 → 北海道石狩市厚田

PostcodeAddressPeriods の結果:
  [{from=2007-10, to=2026-04, entries=[{0613601, 北海道, 石狩市, 厚田区厚田}]},
   {from=2026-04, to=null,    entries=[{0613601, 北海道, 石狩市, 厚田}]}]
```

この例ではポストコード（`0613601`）は変わっておらず、住所名のみが変更されています。

### H.2 高知県高知市桟橋通 の郵便番号変遷

```
2007-10 〜 2019-05: 〒780-8010 → 高知県高知市桟橋通
2019-06 〜 現在:    〒781-8010 → 高知県高知市桟橋通

PostcodePeriods の結果:
  [{from=2007-10, to=2019-06, postcode=7808010},
   {from=2019-06, to=null,    postcode=7818010}]
```

この例では住所名は変わっておらず、郵便番号のみが変更されています。

### H.3 2026年4月の大規模変更

2026年4月は北海道の「厚田区」廃止に伴い、多数の郵便番号エントリで住所名が変更されました。

```java
var diff = dict.diff(YearMonth.of(2026, 3), YearMonth.of(2026, 4));
diff.addedCount()   // 69
diff.removedCount() // 69
```

added には「厚田区」を含まない新しい住所名のエントリが、removed には「厚田区」を含む旧住所名のエントリが含まれます。

### H.4 前方一致検索の活用例

```java
// 大田区の全郵便番号を一覧（現時点）
var entries = dict.lookupByPrefix("145", YearMonth.now(), 500);
// → 〒145-xxxx のエントリが最大500件

// 2015年1月時点の北海道の郵便番号を一覧
var entries = dict.lookupByPrefix("0", YearMonth.of(2015, 1), 1000);
// → 〒0xx-xxxx のエントリ（北海道）
```

### H.5 ABRUtils との連携シナリオ

（未実装・BACKLOG）

```java
// シナリオ: 2010年の住所で郵便番号を確認したい
var dict = HistoricalPostcodeDictionary.loadSnapshot(snapshotPath);

// ユーザー入力: "北海道 石狩市 厚田区厚田"
String zip = dict.lookupByAddress("北海道", "石狩市", "厚田区厚田", YearMonth.of(2010, 1));
// → "0613601" (正しい過去の郵便番号)

// 現在の住所を検索すると「厚田区厚田」は2026年4月以降存在しない
String zipNow = dict.lookupByAddress("北海道", "石狩市", "厚田区厚田", YearMonth.now());
// → null (2026年4月以降は「厚田区」が廃止されたため)
```

---

## 付録 I. よくある質問 (FAQ)

### Q1. 郵便番号の変遷を追跡したいが、どの API を使えばよいか？

住所名は変わらず郵便番号が変わるケース（番号変更）には `postcodePeriods()` を使用してください。

```java
var periods = dict.postcodePeriods("高知県", "高知市", "桟橋通");
// → [7808010 (2007-10 ~ 2019-06), 7818010 (2019-06 ~ present)]
```

郵便番号は変わらず住所名が変わるケース（合併等）には `postcodeAddressPeriods()` を使用してください。

```java
var periods = dict.postcodeAddressPeriods("0613601");
// → [北海道石狩市厚田区厚田 (2007-10 ~ 2026-04),
//    北海道石狩市厚田 (2026-04 ~ present)]
```

### Q2. バイナリスナップショットを自分で作るにはどうするか？

日本郵便サーバーから CSV をダウンロードしてスナップショットを生成します。

```bash
# 全 CSV をダウンロード（既存はスキップ）
mvn -q compile exec:java -Dexec.args="download /tmp/japanpost-history"

# スナップショットを生成
mvn -q compile exec:java \
  -Dexec.args="snapshot-write /tmp/japanpost-history /tmp/japanpost-history.snapshot"
```

### Q3. `at` パラメータに存在しない月を指定するとどうなるか？

`snapshots.floorEntry(at)` により、「指定時点以前で最も近いスナップショット」を返します。例えば、スナップショットが 2007-10, 2007-11, ... と月次で存在する場合、`at=2015-06` を指定すると `2015-06` のスナップショットが返されます。スナップショットの最古月（2007-10）より前の時点（例: 1990-01）を指定すると `null` となり、空リストまたは空マップが返ります。

### Q4. 1つの郵便番号に複数の住所エントリが返るのはなぜか？

日本の郵便番号は必ずしも1対1ではありません。1つの郵便番号が複数の町域をカバーする場合があります（例: 規模の小さい郵便区）。`lookup()` の戻り値は `List<PostcodeEntry>` です。

### Q5. 「以下に掲載がない場合」エントリとは何か？

KEN_ALL では、特定の町域名が登録されていない郵便番号に対して「以下に掲載がない場合」という特殊エントリが使われます。`PostcodeEntry.isCatchAll()` で識別できます。

```java
boolean isCatchAll = entry.isCatchAll();
// 「以下に掲載がない場合」エントリかどうか
```

通常の検索ではこのエントリも混在して返されます。必要に応じてフィルタリングしてください。

### Q6. スナップショットをロードしてから API サーバーを起動するには？

```java
var dict = HistoricalPostcodeDictionary.loadSnapshot(
    Path.of("/tmp/japanpost-history.snapshot")
);
new ApiServer(dict).start(7070);
```

Javalin 依存を pom.xml に追加してからビルドしてください。

### Q7. municipality-history 連携は必須か？

いいえ、オプションです。`municipality-history` 依存を pom.xml に追加しない限り、`PostcodeWithContext` は使用できませんが、コア API（辞書構築・検索・スナップショット）は影響を受けません。`ApiServer` は起動時に municipality-history のロードを試み、失敗した場合は `/explain` エンドポイントのみ 503 を返します。

---

*このドキュメントは japanpost-history v1.0.1 のソースコードおよび既存ドキュメントから生成されました。*
