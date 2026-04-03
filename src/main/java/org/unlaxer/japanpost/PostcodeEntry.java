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
