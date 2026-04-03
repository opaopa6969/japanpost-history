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
