# プロジェクト作法（ABRUtils準拠）

## コーディング規約

- **Java 21** — record, sealed, pattern matching, virtual threads 活用
- **データモデル** — `record` で定義。イミュータブル。フィールドはprimitive優先
- **パッケージ構造** — `model/`, `parser/`, `store/`, `query/`, `api/` で責務分離
- **エラー処理** — 検査例外は呼び出し元に投げる。内部でログを出す場合はSystem.out/err
- **null** — 空文字やList.of()を使い、nullは避ける。外部入力のnullチェックは境界で
- **命名** — 日本語JavaDoc可。メソッド名・変数名は英語

## プロジェクト構造

```
src/main/java/org/unlaxer/<project>/
├── model/       # データモデル (record)
├── parser/      # パース・変換ロジック
├── store/       # DB/ファイルストア
├── query/       # 検索・クエリAPI
├── api/         # REST API (Javalin, optional)
└── App.java     # CLI エントリポイント
```

## ビルド・テスト

- `mvn -q compile` — コンパイル
- `mvn -q exec:java -Dexec.args="..."` — CLI実行
- `mvn package -DskipTests -Dgpg.skip=true` — jar生成
- `mvn install -DskipTests -Dgpg.skip=true` — ローカルinstall
- `mvn deploy` — Maven Central publish

## ABRUtils からの共通パターン

### スナップショット戦略
1. まずRDB (PostgreSQL) に投入して動くものを作る
2. インメモリ辞書に変換（HashMap/TreeMap/カラムナー配列）
3. バイナリスナップショットに書き出し（String Pool + GZIP）
4. スナップショットからの高速ロード（DB不要）

### CLI パターン
```java
public static void main(String[] args) {
    String command = args[0];
    switch (command) {
        case "load" -> ...;
        case "query" -> ...;
        case "build-snapshot" -> ...;
        default -> printUsage();
    }
}
```

### REST API パターン (Javalin)
- 依存はoptional
- `GET /api/info` — メタ情報
- `GET /api/<resource>/{id}` — 単体取得
- `GET /api/<resource>/search?q=` — 検索
- JSON レスポンスは `Map.of()` / `LinkedHashMap` で構築
- 静的UIは `src/main/resources/static/index.html`

## ドキュメント

- **README.md** — Maven dependency、API例、データソース（日本語）
- **BACKLOG.md** — 未着手タスク、気づき
- **docs/*.md** — 設計ノート、分析結果

## Git

- コミットメッセージは英語、1-2行で要点
- `Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>`
- 機能単位でコミット、大きくまとめすぎない

## このプロジェクト固有

- **リポ**: https://github.com/opaopa6969/japanpost-history (public, Maven Central)
- **概要**: 時系列郵便番号辞書（2007-10〜現在、223スナップショット）
- **現状**: v1.0.0 Maven Central公開済み。バイナリスナップショット2.6MB/218ms。REST API+UI実装済み。PostcodeWithContextでmunicipality-historyと連携済み
- **e-Stat appId**: 環境変数 `ESTAT_APP_ID` を優先使用（`EstatConfig.getAppId()`）。未設定時はデフォルト値にフォールバック（後方互換）
