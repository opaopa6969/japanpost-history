# はじめに

## 前提

- Java 21 以上
- Maven 3.6 以上

---

## Maven 設定

`pom.xml` に以下を追加します。

```xml
<dependency>
    <groupId>org.unlaxer</groupId>
    <artifactId>japanpost-history</artifactId>
    <version>1.0.0</version>
</dependency>
```

Maven Central に公開済みです。追加のリポジトリ設定は不要です。

---

## 辞書のロード

### 方法 1: バイナリスナップショットから（推奨）

最も手軽な方法です。スナップショットファイルを GitHub Releases からダウンロードし、218ms でロードできます。

```java
import org.unlaxer.japanpost.HistoricalPostcodeDictionary;
import java.nio.file.Path;

// スナップショットファイルをダウンロード
// https://github.com/opaopa6969/japanpost-history/releases/download/v1.0.0/japanpost-history.snapshot

var dict = HistoricalPostcodeDictionary.loadSnapshot(
    Path.of("/tmp/japanpost-history.snapshot")
);
// → "Loaded in 218 ms (baseline=124,xxx, deltas=222 months)"
```

### 方法 2: CSV から構築

日本郵便サーバーから CSV をダウンロードして辞書を構築します。初回は約 60 秒かかります。

```java
// ダウンロード + 構築を一括実行
var dict = HistoricalPostcodeDictionary.downloadAndBuild(
    Path.of("/tmp/japanpost-history")
);

// または、ダウンロード済みの CSV ディレクトリから構築
var dict = HistoricalPostcodeDictionary.build(
    Path.of("/tmp/japanpost-history")
);
```

---

## 基本的な API 例

```java
import java.time.YearMonth;

// 郵便番号 → 住所（時点指定）
var entries = dict.lookup("0613601", YearMonth.of(2015, 4));
// → [{postcode=0613601, prefecture=北海道, municipality=石狩市, town=厚田区厚田}]

var entries2 = dict.lookup("0613601", YearMonth.now());
// → [{postcode=0613601, prefecture=北海道, municipality=石狩市, town=厚田}]
//    ↑ 2026年4月に「厚田区」が廃止された

// 住所 → 郵便番号（時点指定）
String zip = dict.lookupByAddress("北海道", "石狩市", "厚田区厚田", YearMonth.of(2020, 1));
// → "0613601"

// 前方一致検索
var results = dict.lookupByPrefix("1450", YearMonth.of(2026, 4), 200);
// → [{1450061, 東京都, 大田区, 石川町}, {1450062, 東京都, 大田区, 北千束}, ...]

// 住所の郵便番号変遷（変化点のみ）
var periods = dict.postcodePeriods("高知県", "高知市", "桟橋通");
// → [7808010 (2007-10 ~ 2019-06), 7818010 (2019-06 ~ present)]

// 郵便番号の住所変遷（変化点のみ）
var addrPeriods = dict.postcodeAddressPeriods("0613601");
// → [北海道石狩市厚田区厚田 (2007-10 ~ 2026-04),
//    北海道石狩市厚田 (2026-04 ~ present)]

// 2時点間の差分
var diff = dict.diff(YearMonth.of(2026, 3), YearMonth.of(2026, 4));
System.out.println(diff.addedCount());   // 69
System.out.println(diff.removedCount()); // 69

// メタ情報
dict.earliestMonth();       // 2007-10
dict.latestMonth();         // 2026-04
dict.snapshotCount();       // 223
dict.uniquePostcodeCount(); // 121,282
dict.uniqueAddressCount();  // 130,136
```

---

## スナップショットの作成

ローカルでスナップショットを生成する場合:

```bash
mvn -q compile exec:java \
  -Dexec.args="snapshot-write /tmp/japanpost-history /tmp/japanpost-history.snapshot"
```

---

## CLI コマンド一覧

```bash
# KEN_ALL + ADD/DEL 月次ファイルをすべてダウンロード
mvn -q compile exec:java -Dexec.args="download /tmp/japanpost-history"

# CSV から辞書を構築してデモ出力
mvn -q compile exec:java -Dexec.args="build /tmp/japanpost-history"

# ダウンロード + 構築 + デモ を一括実行
mvn -q compile exec:java -Dexec.args="run /tmp/japanpost-history"

# バイナリスナップショットを書き出す
mvn -q compile exec:java \
  -Dexec.args="snapshot-write /tmp/japanpost-history /tmp/out.snapshot"

# バイナリスナップショットからロードしてデモ出力
mvn -q compile exec:java \
  -Dexec.args="snapshot-load /tmp/japanpost-history.snapshot"
```

---

## REST API の起動

Javalin 依存を pom.xml に追加することで、REST API サーバーと検索 UI が利用できます。

```xml
<dependency>
    <groupId>io.javalin</groupId>
    <artifactId>javalin</artifactId>
    <version>5.6.3</version>
</dependency>
<dependency>
    <groupId>com.fasterxml.jackson.core</groupId>
    <artifactId>jackson-databind</artifactId>
    <version>2.18.3</version>
</dependency>
<dependency>
    <groupId>com.fasterxml.jackson.datatype</groupId>
    <artifactId>jackson-datatype-jsr310</artifactId>
    <version>2.18.3</version>
</dependency>
<dependency>
    <groupId>org.slf4j</groupId>
    <artifactId>slf4j-simple</artifactId>
    <version>2.0.9</version>
</dependency>
```

| エンドポイント | 説明 |
|---------------|------|
| `GET /api/prefix/{prefix}?at=YYYY-MM` | 前方一致検索 |
| `GET /api/lookup/{postcode}?at=YYYY-MM` | 郵便番号→住所 |
| `GET /api/postcode/{pc}/periods` | 郵便番号の住所変遷 |
| `GET /api/address/periods?prefecture=&municipality=&town=` | 住所の郵便番号変遷 |
| `GET /api/diff?from=YYYY-MM&to=YYYY-MM` | 差分比較 |

---

## 注意事項

- **テストコードなし**: 現バージョン（1.0.0）にはユニットテスト・インテグレーションテストが含まれていません。本番投入前に利用側でテストを追加することを推奨します。
- **uint16 上限**: スナップショットフォーマットの `addCount`・`delCount`・`deltaMonthCount` は uint16（最大 65535）です。現在のデータ規模では問題ありませんが、詳細は [アーキテクチャドキュメント](architecture.md#uint16-制限に関する警告) を参照してください。
