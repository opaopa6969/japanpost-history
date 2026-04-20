# 変更履歴

このプロジェクトのすべての変更はここに記録されます。
フォーマットは [Keep a Changelog](https://keepachangelog.com/ja/1.0.0/) に準拠。
バージョニングは [Semantic Versioning](https://semver.org/lang/ja/) に従います。

## [1.0.1] - 2026-04-19

### 追加
- **ユニットテスト**: `PostcodeEntryTest`・`DictionarySnapshotTest`・`HistoricalPostcodeDictionaryTest`・`EstatConfigTest` を追加 (#1, #2)
- **CI**: `.github/workflows/ci.yml` — `mvn test` を push/PR 時に自動実行 (#1)
- **`EstatConfig`**: e-Stat appId を `ESTAT_APP_ID` 環境変数から優先取得するユーティリティクラス。未設定時はデフォルト値にフォールバック（後方互換）(#4)

### セキュリティ
- **uint16 overflow guard** (`DictionarySnapshot`): delta の ADD/DEL エントリ数が 65535 を超える場合に `IllegalStateException` を投げるよう変更。従来は符号なし short の silent overflow でデータが破損する可能性があった (#3)
- **Zip Slip 防御** (`JapanPostDownloader`): ZIP 展開時にエントリのパスが出力ディレクトリ外を指す場合に `SecurityException` を投げるよう変更 (#3)

### 依存関係
- `junit-jupiter-api` 5.10.3 (test scope) 追加
- `junit-jupiter-engine` 5.10.3 (test scope) 追加
- `maven-surefire-plugin` 3.2.5 追加

### 後方互換性
- 既存の public API に変更なし
- スナップショットフォーマットに変更なし
- `DictionarySnapshot.write()` の uint16 overflow guard は、65535 件を超えるデルタを含む異常データに対してのみ例外を投げる（通常データは影響なし）

## [1.0.0] - 2026-04-19

### 追加
- `HistoricalPostcodeDictionary` — 時系列郵便番号辞書の初版
  - `build(Path)` — KEN_ALL + ADD/DEL CSVからの辞書構築（約60秒）
  - `loadSnapshot(Path)` — バイナリスナップショットからの高速ロード（218ms）
  - `downloadAndBuild(Path)` — ダウンロード + 構築の一括実行
  - `lookup(postcode, YearMonth)` — 郵便番号→住所の時点検索
  - `lookupByAddress(pref, muni, town, YearMonth)` — 住所→郵便番号の時点検索
  - `lookupByPrefix(prefix, YearMonth, limit)` — 前方一致検索
  - `postcodePeriods(pref, muni, town)` — 住所の郵便番号変遷
  - `postcodeAddressPeriods(postcode)` — 郵便番号の住所変遷
  - `addressPostcodeHistory(pref, muni, town)` — 全月の住所→郵便番号マップ
  - `diff(YearMonth, YearMonth)` — 2時点間の差分
  - `findChangedAddresses()` / `findChangedPostcodes()` — 変更検出
- `DictionarySnapshot` — バイナリスナップショットの読み書き
  - マジック `JPHS` + バージョン番号 + GZIP圧縮
  - StringPool による重複文字列の圧縮
  - デルタを newest-first で格納し、逆順適用で過去を復元
- `PostcodeEntry` — KEN_ALL.CSV 1行に対応するイミュータブルレコード
  - 複数行分割（括弧が閉じるまで継続）の自動マージ
  - 半角カタカナ→全角カタカナ正規化
- `JapanPostDownloader` — 日本郵便サーバーからの一括ダウンロード
  - KEN_ALL.ZIP + ADD/DEL 月次 ZIP を HTTP で取得・展開
  - 既存ファイルのスキップ（増分ダウンロード対応）
- `PostcodeWithContext` — municipality-history 連携による変遷コンテキスト付与
- `ApiServer` — Javalin ベース REST API + 静的検索 UI（optional 依存）
- バイナリスナップショット: CSV 29 MB → 2.6 MB、ロード 60秒 → 218ms
- Maven Central 公開（groupId: `org.unlaxer`、artifactId: `japanpost-history`）
- カバー期間: 2007年10月～現在（223スナップショット）

### データ
- KEN_ALL.CSV: 12.4万件（最新フルデータ）
- ADD/DEL 月次ファイル: 222ヶ月分
- ユニーク郵便番号: 121,282件（全期間）
- ユニーク住所: 130,136件（全期間）
