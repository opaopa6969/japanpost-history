# 変更履歴

このプロジェクトのすべての変更はここに記録されます。
フォーマットは [Keep a Changelog](https://keepachangelog.com/ja/1.0.0/) に準拠。
バージョニングは [Semantic Versioning](https://semver.org/lang/ja/) に従います。

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
