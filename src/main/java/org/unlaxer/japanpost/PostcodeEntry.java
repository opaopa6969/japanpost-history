package org.unlaxer.japanpost;

import java.util.ArrayList;
import java.util.List;

/**
 * KEN_ALL.CSV の1行に対応する郵便番号エントリ。
 */
public record PostcodeEntry(
        String lgCode,
        String postcode,
        String prefectureKana,
        String municipalityKana,
        String townKana,
        String prefecture,
        String municipality,
        String town
) {
    /** CSV行をパース (MS932 or UTF-8) */
    static PostcodeEntry fromCsvLine(String line) {
        String[] cols = parseCsvLine(line);
        if (cols.length < 9) return null;
        return new PostcodeEntry(
                unquote(cols[0]),
                unquote(cols[2]),
                unquote(cols[3]),
                unquote(cols[4]),
                unquote(cols[5]),
                unquote(cols[6]),
                unquote(cols[7]),
                unquote(cols[8])
        );
    }

    /** 都道府県+市区町村+町域 の複合キー */
    String addressKey() {
        return prefecture + "|" + municipality + "|" + town;
    }

    /** 「以下に掲載がない場合」かどうか */
    boolean isCatchAll() {
        return "\u4ee5\u4e0b\u306b\u63b2\u8f09\u304c\u306a\u3044\u5834\u5408".equals(town);
    }

    /** 半角カタカナ → 全角カタカナ変換 */
    PostcodeEntry normalizeKana() {
        return new PostcodeEntry(
                lgCode, postcode,
                halfToFullKana(prefectureKana),
                halfToFullKana(municipalityKana),
                halfToFullKana(townKana),
                prefecture, municipality, town
        );
    }

    /** 町域名の括弧内を除去した版を返す（検索用） */
    String townWithoutParens() {
        return town.replaceAll("[\uff08(].+?[\uff09)]", "").trim();
    }

    private static final String HALF_KANA =
            "\uff66\uff67\uff68\uff69\uff6a\uff6b\uff6c\uff6d\uff6e\uff6f" +
            "\uff70\uff71\uff72\uff73\uff74\uff75\uff76\uff77\uff78\uff79" +
            "\uff7a\uff7b\uff7c\uff7d\uff7e\uff7f\uff80\uff81\uff82\uff83" +
            "\uff84\uff85\uff86\uff87\uff88\uff89\uff8a\uff8b\uff8c\uff8d" +
            "\uff8e\uff8f\uff90\uff91\uff92\uff93\uff94\uff95\uff96\uff97" +
            "\uff98\uff99\uff9a\uff9b\uff9c\uff9d\uff9e\uff9f";
    private static final String FULL_KANA =
            "\u30f2\u30a1\u30a3\u30a5\u30a7\u30a9\u30e3\u30e5\u30e7\u30c3" +
            "\u30fc\u30a2\u30a4\u30a6\u30a8\u30aa\u30ab\u30ad\u30af\u30b1" +
            "\u30b3\u30b5\u30b7\u30b9\u30bb\u30bd\u30bf\u30c1\u30c4\u30c6" +
            "\u30c8\u30ca\u30cb\u30cc\u30cd\u30ce\u30cf\u30d2\u30d5\u30d8" +
            "\u30db\u30de\u30df\u30e0\u30e1\u30e2\u30e4\u30e6\u30e8\u30e9" +
            "\u30ea\u30eb\u30ec\u30ed\u30ef\u30f3\u309b\u309c";

    static String halfToFullKana(String s) {
        if (s == null || s.isEmpty()) return s;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int idx = HALF_KANA.indexOf(c);
            if (idx >= 0) {
                char full = FULL_KANA.charAt(idx);
                // 濁点・半濁点の結合処理
                if (i + 1 < s.length()) {
                    char next = s.charAt(i + 1);
                    if (next == '\uff9e' && canDakuten(full)) { // ﾞ
                        sb.append((char)(full + 1));
                        i++;
                        continue;
                    } else if (next == '\uff9f' && canHandakuten(full)) { // ﾟ
                        sb.append((char)(full + 2));
                        i++;
                        continue;
                    }
                }
                sb.append(full);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    // 濁点が付ける文字 → 濁音 (+1)
    private static final String DAKUTEN_BASE = "\u30ab\u30ad\u30af\u30b1\u30b3" // カキクケコ
            + "\u30b5\u30b7\u30b9\u30bb\u30bd" // サシスセソ
            + "\u30bf\u30c1\u30c4\u30c6\u30c8" // タチツテト
            + "\u30cf\u30d2\u30d5\u30d8\u30db" // ハヒフヘホ
            + "\u30a6"; // ウ→ヴ
    // 半濁点が付ける文字 → 半濁音 (+2)
    private static final String HANDAKUTEN_BASE = "\u30cf\u30d2\u30d5\u30d8\u30db"; // ハヒフヘホ

    private static boolean canDakuten(char c) {
        return DAKUTEN_BASE.indexOf(c) >= 0;
    }

    private static boolean canHandakuten(char c) {
        return HANDAKUTEN_BASE.indexOf(c) >= 0;
    }

    private static String unquote(String s) {
        if (s == null) return "";
        return s.replace("\"", "").trim();
    }

    /**
     * KEN_ALLの複数行分割（町域名が長い場合、括弧が閉じるまで次行に続く）をマージ。
     */
    static List<PostcodeEntry> mergeContinuationRows(List<PostcodeEntry> rawRows) {
        List<PostcodeEntry> merged = new ArrayList<>();
        for (PostcodeEntry current : rawRows) {
            if (merged.isEmpty()) {
                merged.add(current);
                continue;
            }
            int lastIdx = merged.size() - 1;
            PostcodeEntry prev = merged.get(lastIdx);
            if (shouldMerge(prev, current)) {
                merged.set(lastIdx, new PostcodeEntry(
                        prev.lgCode, prev.postcode,
                        prev.prefectureKana, prev.municipalityKana,
                        append(prev.townKana, current.townKana),
                        prev.prefecture, prev.municipality,
                        append(prev.town, current.town)
                ));
            } else {
                merged.add(current);
            }
        }
        return merged;
    }

    private static boolean shouldMerge(PostcodeEntry prev, PostcodeEntry current) {
        if (!hasUnclosedParen(prev.town)) return false;
        return prev.lgCode.equals(current.lgCode)
                && prev.postcode.equals(current.postcode)
                && prev.prefecture.equals(current.prefecture)
                && prev.municipality.equals(current.municipality);
    }

    private static boolean hasUnclosedParen(String text) {
        if (text == null || text.isBlank()) return false;
        int open = 0;
        for (char c : text.toCharArray()) {
            if (c == '（' || c == '(') open++;
            if (c == '）' || c == ')') open--;
        }
        return open > 0;
    }

    private static String append(String a, String b) {
        if (a == null || a.isEmpty()) return b;
        if (b == null || b.isEmpty()) return a;
        return a + b;
    }

    static String[] parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString());
        return fields.toArray(new String[0]);
    }
}
