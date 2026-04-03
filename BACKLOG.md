# japanpost-history バックログ

## 他プロジェクトとの連携

### ABRUtils → japanpost-history
- **PostcodeAccuracyChecker の KEN_ALL fallback 強化** — 現在は最新 KEN_ALL のみ使用。japanpost-history のスナップショットを使えば、過去の郵便番号でも照合可能になる
- **ABR postcodeマップとKEN_ALLの差分分析** — ABRにあってKEN_ALLにない住所、その逆を可視化して精度向上の余地を特定

### municipality-history → japanpost-history
- [x] ~~PostcodeWithContext~~ — 郵便番号変遷に統廃合コンテキストを付与（実装済み）
- **統廃合発生日と郵便番号変更日の自動紐づけ** — 現在は名前マッチ。地域コードでの紐づけに改善
- **合併前の旧市町村名での検索** — 「厚田村」で検索 → 現在の「石狩市厚田」にフォールバック

### japanpost-history → ABRUtils
- **ABRUtils の REST API に郵便番号変遷エンドポイントを追加** — ABRの free-search 結果に「この郵便番号は2019年に変更されました」的な情報を付与
- **住所正規化の強化** — 旧市町村名を現在の名前にマッピングするテーブルを japanpost-history + municipality-history から構築

## 機能改善

- 2007年10月より前のデータ（取得困難、Waybackにも残っていない）
- KEN_ALL 複数行分割の完全対応（継続行マージは実装済み）
- UTF-8版 (utf_all.csv) への対応（2023年6月〜）
- スナップショットのインクリメンタル更新（毎月差分だけ追加）
