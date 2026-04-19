# japanpost-history

[![Maven Central](https://img.shields.io/maven-central/v/org.unlaxer/japanpost-history)](https://central.sonatype.com/artifact/org.unlaxer/japanpost-history)

日本郵便の郵便番号データ (KEN_ALL.CSV) と月次更新ファイル (ADD/DEL) から、**任意時点の郵便番号を引ける時系列辞書**を構築する Java ライブラリ。

2007年10月～現在の約18年分・222ヶ月のスナップショットを復元し、`NavigableMap` ベースで時点検索を提供します。

**ドキュメント**:
[はじめに](docs/getting-started.md) |
[アーキテクチャ](docs/architecture.md) |
[データソース](docs/data-source.md) |
[CHANGELOG](CHANGELOG.md)

## ユースケース

- 20年前に電力契約した住所の郵便番号を、**当時の郵便番号**で引きたい
- ある住所の郵便番号が**いつ変わったか**を追跡したい
- 過去の郵便番号から**当時の住所**を逆引きしたい

## Maven

```xml
<dependency>
    <groupId>org.unlaxer</groupId>
    <artifactId>japanpost-history</artifactId>
    <version>1.0.0</version>
</dependency>
```

## ライブラリとして使う

### 辞書の構築

```java
import org.unlaxer.japanpost.HistoricalPostcodeDictionary;
import java.nio.file.Path;
import java.time.YearMonth;

// 方法1: バイナリスナップショットからロード（推奨、218ms）
var dict = HistoricalPostcodeDictionary.loadSnapshot(Path.of("japanpost-history.snapshot"));

// 方法2: CSVからビルド（初回のみ、約60秒）
var dict = HistoricalPostcodeDictionary.build(Path.of("/tmp/japanpost-history"));

// 方法3: ダウンロード + ビルドを一括実行
var dict = HistoricalPostcodeDictionary.downloadAndBuild(Path.of("/tmp/japanpost-history"));
```

### 郵便番号 → 住所（時点指定）

```java
// 2015年4月時点で 〒061-3601 はどこ？
var entries = dict.lookup("0613601", YearMonth.of(2015, 4));
// → [{postcode=0613601, prefecture=北海道, municipality=石狩市, town=厚田区厚田}]

// 現在時点
var entries = dict.lookup("0613601", YearMonth.now());
// → [{postcode=0613601, prefecture=北海道, municipality=石狩市, town=厚田}]
//    ↑ 2026年4月に「厚田区」が廃止された
```

### 住所 → 郵便番号（時点指定）

```java
String zip = dict.lookupByAddress("北海道", "石狩市", "厚田区厚田", YearMonth.of(2020, 1));
// → "0613601"
```

### 郵便番号の前方一致検索

```java
// 〒145-0xxx（大田区周辺）の住所を一覧
var entries = dict.lookupByPrefix("1450", YearMonth.of(2026, 4), 200);
// → [{1450061, 東京都, 大田区, 石川町}, {1450062, 東京都, 大田区, 北千束}, ...]
```

### 住所の郵便番号変遷（period:postcode マップ）

```java
// この住所の郵便番号はいつ変わった？
var periods = dict.postcodePeriods("高知県", "高知市", "桟橋通");
// → [7808010 (2007-10 ~ 2019-06), 7818010 (2019-06 ~ present)]
//    ↑ 2019年6月に郵便番号が変更された

// 全月の変遷マップ
NavigableMap<YearMonth, String> history =
    dict.addressPostcodeHistory("北海道", "石狩市", "厚田区厚田");
// → {2007-10=0613601, 2007-11=0613601, ..., 2026-03=0613601}
```

### 郵便番号の住所変遷（period:address マップ）

```java
// 〒061-3601 の住所名はいつ変わった？
var periods = dict.postcodeAddressPeriods("0613601");
// → [北海道石狩市厚田区厚田 (2007-10 ~ 2026-04),
//    北海道石狩市厚田 (2026-04 ~ present)]
```

### 2時点間の差分

```java
var diff = dict.diff(YearMonth.of(2026, 3), YearMonth.of(2026, 4));
// diff.addedCount()   → 69   (新規追加)
// diff.removedCount() → 69   (削除)
// diff.added()        → {0613601: [北海道石狩市厚田], ...}
// diff.removed()      → {0613601: [北海道石狩市厚田区厚田], ...}
```

### 変更検出

```java
// 郵便番号が変わった住所の一覧
var addressChanges = dict.findChangedAddresses();
// → [高知県高知市桟橋通: 7808010 -> 7818010 (2019-06), ...]

// 住所名が変わった郵便番号の一覧（市町村合併等）
var postcodeChanges = dict.findChangedPostcodes();
// → [0613601: 2 periods, ...]  (4,821件)
```

### メタ情報

```java
dict.earliestMonth();      // 2007-10
dict.latestMonth();        // 2026-04
dict.snapshotCount();      // 223
dict.uniquePostcodeCount(); // 121,282
dict.uniqueAddressCount();  // 130,136
```

## クイックスタート（CLI）

### 前提

- Java 21+
- Maven

### スナップショットから即起動

```bash
curl -L -o /tmp/japanpost-history.snapshot \
  https://github.com/opaopa6969/japanpost-history/releases/download/v1.0.0/japanpost-history.snapshot

mvn -q compile exec:java -Dexec.args="snapshot-load /tmp/japanpost-history.snapshot"
```

### CSVからフルビルド

```bash
mvn -q compile exec:java -Dexec.args="run"
```

### スナップショット作成

```bash
mvn -q compile exec:java -Dexec.args="snapshot-write /path/to/csv-dir /path/to/output.snapshot"
```

## バイナリスナップショット

| 方式 | サイズ | ロード時間 |
|------|--------|-----------|
| CSV (222ファイル) | 29 MB | ~60秒 |
| バイナリスナップショット | **2.6 MB** | **218 ms** |

フォーマット: ベースライン + 月次delta をString Pool + GZIP圧縮。

ダウンロード: https://github.com/opaopa6969/japanpost-history/releases

## REST API + 検索UI

Javalin依存を追加すれば API サーバー + 検索UI も起動できます。

| エンドポイント | 説明 |
|---------------|------|
| `GET /api/prefix/{prefix}?at=` | 前方一致検索 |
| `GET /api/lookup/{postcode}?at=` | 郵便番号→住所 |
| `GET /api/postcode/{pc}/periods` | 郵便番号の住所変遷 |
| `GET /api/address/periods?prefecture=&municipality=&town=` | 住所の郵便番号変遷 |
| `GET /api/diff?from=&to=` | 差分比較 |

## データソース

- **KEN_ALL.CSV**: 日本郵便 全国郵便番号（最新12.4万件）
- **ADD/DEL月次ファイル**: 2007年10月〜現在の222ヶ月分
- 日本郵便は郵便番号データの著作権を主張せず、自由に配布可能

## 関連プロジェクト

- [ABRUtils](https://github.com/opaopa6969/ABRUtils) — 住所基盤レジストリ検索ライブラリ
- [municipality-history](https://github.com/opaopa6969/municipality-history) — 自治体統廃合履歴（1970〜2028）
