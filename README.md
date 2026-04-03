# japanpost-history

日本郵便の郵便番号データ (KEN_ALL.CSV) と月次更新ファイル (ADD/DEL) から、**任意時点の郵便番号を引ける時系列辞書**を構築する Java ライブラリ。

2008年1月～現在の約18年分・219ヶ月のスナップショットを復元し、`NavigableMap` ベースで時点検索を提供します。

## ユースケース

- 20年前に電力契約した住所の郵便番号を、**当時の郵便番号**で引きたい
- ある住所の郵便番号が**いつ変わったか**を追跡したい
- 過去の郵便番号から**当時の住所**を逆引きしたい

## クイックスタート

### 前提

- Java 21+
- Maven

### 1. ダウンロード + 辞書構築 + デモ

```bash
mvn -q compile exec:java -Dexec.args="run"
```

これで:
1. 日本郵便から KEN_ALL + 219ヶ月分の ADD/DEL を `/tmp/japanpost-history/` にダウンロード
2. 辞書を構築（最新→過去を逆算復元）
3. デモ検索を実行

### 2. ダウンロードだけ / ビルドだけ

```bash
# ダウンロードのみ
mvn -q compile exec:java -Dexec.args="download /path/to/dir"

# 構築のみ（ダウンロード済みデータから）
mvn -q compile exec:java -Dexec.args="build /path/to/dir"
```

## Java API

```java
import org.unlaxer.japanpost.HistoricalPostcodeDictionary;
import java.time.YearMonth;

// 構築
var dict = HistoricalPostcodeDictionary.build(Path.of("/tmp/japanpost-history"));

// 郵便番号 → 指定時点の住所
var entries = dict.lookup("0613601", YearMonth.of(2015, 4));
// → [PostcodeEntry{postcode=0613601, prefecture=北海道, municipality=石狩市, town=厚田区厚田}]

// 住所 → 指定時点の郵便番号
String zip = dict.lookupByAddress("北海道", "石狩市", "厚田区厚田", YearMonth.of(2020, 1));
// → "0613601"

// 住所の郵便番号変遷 (period:postcode)
var periods = dict.postcodePeriods("北海道", "石狩市", "厚田区厚田");
// → [0613601 (2008-01 ~ 2026-03), ...]
// 途中で郵便番号が変わった場合は複数期間が返る

// 全変遷マップ
NavigableMap<YearMonth, String> history =
    dict.addressPostcodeHistory("北海道", "石狩市", "厚田区厚田");
// → {2008-01=0613601, 2008-02=0613601, ..., 2026-04=0613601}
```

## データソース

| ファイル | URL | 説明 |
|---------|-----|------|
| KEN_ALL.ZIP | `https://www.post.japanpost.jp/zipcode/dl/kogaki/zip/ken_all.zip` | 最新の全国郵便番号 (12.4万件) |
| ADD_YYMM.ZIP | `.../add_YYMM.zip` | 月次追加分 |
| DEL_YYMM.ZIP | `.../del_YYMM.zip` | 月次削除分 |

- ADD/DEL は 2008年1月 (0801) ～ 2026年3月 (2603) の219ヶ月分が利用可能
- CSV形式は MS932 (Shift_JIS) エンコーディング
- 日本郵便は郵便番号データの著作権を主張せず、自由に配布可能

## 構築アルゴリズム

```
KEN_ALL (2026年4月時点)
  ← DEL_2603 を足す + ADD_2603 を引く → 2026年3月時点
  ← DEL_2602 を足す + ADD_2602 を引く → 2026年2月時点
  ...
  ← DEL_0801 を足す + ADD_0801 を引く → 2008年1月時点
```

月M の ADD = その月に追加されたエントリ（前月にはなかった）  
月M の DEL = その月に削除されたエントリ（前月にはあった）  
→ 逆算: ADDを引き、DELを足すと前月の状態が復元される
