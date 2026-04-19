# セキュリティ: Zip Slip 対応方針

## 概要

`JapanPostDownloader` は日本郵便サーバーから ZIP ファイルを取得・展開します。ZIP 展開を伴う処理では **Zip Slip 攻撃**（ZIP エントリにパストラバーサルを仕込んで任意パスにファイルを書き出す手法）への対応を検討する必要があります。

## 現状のリスク評価

**リスク: 低（理論上のみ）**

理由:

1. **信頼済みドメインのみ**: ダウンロード先は `https://www.post.japanpost.jp/zipcode/dl/kogaki/zip/` に固定。URL はハードコードされており、外部から変更できない
2. **HTTPS**: 中間者攻撃による ZIP 改ざんは TLS により防止される
3. **悪意のある ZIP を受け取る経路がない**: 本ライブラリは日本郵便サーバー以外の ZIP を展開しない

## 現在の実装

```java
while ((entry = zis.getNextEntry()) != null) {
    if (entry.isDirectory()) continue;
    String name = entry.getName().toUpperCase();
    if (name.endsWith(".CSV")) {
        Path target = outputDir.resolve(name);
        Files.copy(zis, target, StandardCopyOption.REPLACE_EXISTING);
    }
}
```

`outputDir.resolve(name)` は、`name` に `../` などのパストラバーサルが含まれる場合、出力ディレクトリの外にファイルを書き出す可能性があります（Zip Slip）。

## 対応方針

現時点では**信頼済みソースのみを利用**しているため、追加対策なしで許容します。

将来的に外部 ZIP ファイルを受け入れる機能（ユーザー指定の ZIP など）を追加する場合は、以下のガードを実装すること:

```java
Path resolved = outputDir.resolve(name).normalize();
if (!resolved.startsWith(outputDir.normalize())) {
    throw new IOException("Zip Slip detected: " + name);
}
```

## 参考

- [Zip Slip の詳細（Snyk Research）](https://security.snyk.io/research/zip-slip-vulnerability)
- 影響するクラス: `JapanPostDownloader.downloadAndExtract()`
