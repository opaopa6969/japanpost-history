# データソース

## 日本郵便の郵便番号データ

japanpost-history が使用するデータはすべて[日本郵便の郵便番号データダウンロードページ](https://www.post.japanpost.jp/zipcode/dl/kogaki-zip.html)から取得しています。

### ファイル種別

| ファイル名 | 内容 | 配布形式 |
|------------|------|---------|
| `KEN_ALL.CSV` | 全国郵便番号（最新・フルデータ、約 12.4 万件） | ZIP 圧縮 |
| `ADD_YYMM.CSV` | 当月の追加エントリ（新設・変更） | ZIP 圧縮 |
| `DEL_YYMM.CSV` | 当月の削除エントリ（廃止・変更前） | ZIP 圧縮 |

`YYMM` は西暦下2桁 + 月2桁（例: `2604` = 2026年4月）。

### 文字コード

KEN_ALL および ADD/DEL ファイルは **MS932**（Shift_JIS 拡張）でエンコードされています。ライブラリ内で `Charset.forName("MS932")` で読み込みます。

> 日本郵便は 2023年6月から UTF-8 版（`utf_all.csv`）も提供していますが、現バージョンでは MS932 版のみ対応しています。

### 提供期間

ADD/DEL 月次ファイルは **2007年10月（add_0710）以降**のものが取得可能です。それ以前のデータは日本郵便サーバーにも Wayback Machine にも残っておらず、取得は困難です。

### 著作権・利用条件

日本郵便は郵便番号データについて著作権を主張せず、自由に配布・利用可能としています。

---

## 更新頻度

日本郵便は毎月1回、月初に前月分の ADD/DEL ファイルを公開します。KEN_ALL は常に最新月の状態が反映されています。

| 更新対象 | 頻度 |
|----------|------|
| KEN_ALL.CSV | 毎月更新（常に最新） |
| ADD_YYMM.CSV / DEL_YYMM.CSV | 毎月1回追加（翌月初旬） |

### スナップショットの手動更新

新しい月のデータが公開されたら、以下の手順でスナップショットを更新できます。

```bash
# 1. 新しい月次ファイルだけを追加ダウンロード（既存ファイルはスキップ）
mvn -q compile exec:java -Dexec.args="download /tmp/japanpost-history"

# 2. スナップショットを再生成
mvn -q compile exec:java \
  -Dexec.args="snapshot-write /tmp/japanpost-history /tmp/japanpost-history.snapshot"
```

---

## ダウンロード実装

### JapanPostDownloader（Java / HTTP クライアント）

`JapanPostDownloader.downloadAll(Path)` は Java 標準の `HttpClient` を使用します。

- ベース URL: `https://www.post.japanpost.jp/zipcode/dl/kogaki/zip/`
- ZIP をメモリ上に展開し、CSV ファイルを outputDir に書き出す
- 既存ファイルがあればスキップ（増分ダウンロード対応）
- 2007年10月〜現在の全 ADD/DEL を順次取得

```java
// すべての月次ファイルをダウンロード
JapanPostDownloader.downloadAll(Path.of("/tmp/japanpost-history"));

// 期間を指定してダウンロード
JapanPostDownloader.downloadAll(
    Path.of("/tmp/japanpost-history"),
    YearMonth.of(2020, 1),
    YearMonth.of(2026, 4)
);
```

### download-estat.mjs（Node.js / Playwright）

e-Stat 経由でデータを取得する場合は `download-estat.mjs` スクリプトを使用します。このスクリプトは [Playwright](https://playwright.dev/) を使用してブラウザ自動化でデータを取得します。e-Stat API appId が必要です。

appId は環境変数 `ESTAT_APP_ID` で設定してください（`EstatConfig.getAppId()` が優先的に参照します）。未設定の場合はデフォルト値にフォールバックします。

```bash
export ESTAT_APP_ID=your_app_id_here
```

```bash
# Node.js と Playwright をインストール済みの場合
node download-estat.mjs
```

> 通常の用途では `JapanPostDownloader`（Java HTTP）で十分です。`download-estat.mjs` は e-Stat の構造変更に追従するためのメンテナンス用途です。

---

## データの品質・制限事項

- **2007年10月以前のデータなし**: それ以前の郵便番号は復元不可
- **KEN_ALL の「以下に掲載がない場合」エントリ**: `PostcodeEntry.isCatchAll()` で検出可能。通常の検索では混在します
- **継続行**: 町域名が長く括弧が閉じるまで複数行に及ぶ場合、`mergeContinuationRows()` が自動マージします
- **UTF-8 版未対応**: `utf_all.csv`（2023年6月以降）は現在非対応
