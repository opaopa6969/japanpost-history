package org.unlaxer.japanpost;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HistoricalPostcodeDictionary の build / lookup テスト (最小 CSV で動作確認)。
 */
class HistoricalPostcodeDictionaryTest {

    private static final Charset MS932 = Charset.forName("MS932");

    /** 最小 KEN_ALL.CSV を含むディレクトリから辞書を構築し、基本 API を確認 */
    @Test
    void build_andLookup_fromMinimalCsv(@TempDir Path tmp) throws Exception {
        writeCsvRow(tmp.resolve("KEN_ALL.CSV"),
                "01101", "0600001", "ﾎｯｶｲﾄﾞｳ", "ｻｯﾎﾟﾛｼﾁｭｳｵｳｸ", "ｲｶﾆｹｲｻｲｶﾞﾅｲﾊﾞｱｲ",
                "北海道", "札幌市中央区", "以下に掲載がない場合");

        var dict = HistoricalPostcodeDictionary.build(tmp);
        assertNotNull(dict);
        assertEquals(1, dict.snapshotCount());

        List<PostcodeEntry> result = dict.lookup("0600001", dict.latestMonth());
        assertEquals(1, result.size());
        assertEquals("北海道", result.get(0).prefecture());
        assertEquals("札幌市中央区", result.get(0).municipality());
    }

    /** ADD ファイルを含む場合、delta 適用で過去のスナップショットを正しく復元できること */
    @Test
    void build_withDelta_restoresPastState(@TempDir Path tmp) throws Exception {
        // KEN_ALL.CSV: 2件
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(tmp.resolve("KEN_ALL.CSV"), MS932))) {
            w.println("\"01101\",\"060  \",\"0600001\",\"ﾎｯｶｲﾄﾞｳ\",\"ｻｯﾎﾟﾛｼ\",\"ｲｶﾆ\",\"北海道\",\"札幌市\",\"以下に掲載がない場合\"");
            w.println("\"13101\",\"100  \",\"1000001\",\"ﾄｳｷｮｳﾄ\",\"ﾁﾖﾀﾞｸ\",\"ｲｶﾆ\",\"東京都\",\"千代田区\",\"以下に掲載がない場合\"");
        }
        // ADD_2601.CSV: 2601 = 2026年01月 に 東京 エントリが追加された
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(tmp.resolve("ADD_2601.CSV"), MS932))) {
            w.println("\"13101\",\"100  \",\"1000001\",\"ﾄｳｷｮｳﾄ\",\"ﾁﾖﾀﾞｸ\",\"ｲｶﾆ\",\"東京都\",\"千代田区\",\"以下に掲載がない場合\"");
        }

        var dict = HistoricalPostcodeDictionary.build(tmp);
        // 現在 (2026-02 以降) → 2件見える
        YearMonth latest = dict.latestMonth();
        assertEquals(2, dict.snapshotAt(latest).size());

        // 2026-01 以前 → 東京エントリは存在しないはず
        YearMonth before = YearMonth.of(2026, 1);
        assertTrue(dict.lookup("1000001", before).isEmpty(),
                "1000001 should not exist before the ADD month");
    }

    /** snapshotAt が存在しない時点では空を返すこと */
    @Test
    void snapshotAt_veryOldDateReturnsEmpty(@TempDir Path tmp) throws Exception {
        writeCsvRow(tmp.resolve("KEN_ALL.CSV"),
                "01101", "0600001", "", "", "", "北海道", "札幌市", "テスト");
        var dict = HistoricalPostcodeDictionary.build(tmp);
        assertTrue(dict.snapshotAt(YearMonth.of(1900, 1)).isEmpty());
    }

    /** lookupByPrefix の前方一致検索が動作すること */
    @Test
    void lookupByPrefix_returnsMatchingEntries(@TempDir Path tmp) throws Exception {
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(tmp.resolve("KEN_ALL.CSV"), MS932))) {
            w.println("\"01101\",\"060  \",\"0600001\",\"ﾎｯｶｲﾄﾞｳ\",\"ｻｯﾎﾟﾛｼ\",\"ｱ\",\"北海道\",\"札幌市\",\"A\"");
            w.println("\"01101\",\"060  \",\"0600002\",\"ﾎｯｶｲﾄﾞｳ\",\"ｻｯﾎﾟﾛｼ\",\"ｲ\",\"北海道\",\"札幌市\",\"B\"");
            w.println("\"13101\",\"100  \",\"1000001\",\"ﾄｳｷｮｳﾄ\",\"ﾁﾖﾀﾞｸ\",\"ｱ\",\"東京都\",\"千代田区\",\"C\"");
        }
        var dict = HistoricalPostcodeDictionary.build(tmp);
        List<PostcodeEntry> results = dict.lookupByPrefix("060", dict.latestMonth(), 10);
        assertEquals(2, results.size());
        assertTrue(results.stream().allMatch(e -> e.postcode().startsWith("060")));
    }

    // ---- helper ----

    private static void writeCsvRow(Path path, String lgCode, String postcode,
            String prefKana, String muniKana, String townKana,
            String pref, String muni, String town) throws Exception {
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(path, MS932))) {
            w.printf("\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\"%n",
                    lgCode, postcode.substring(0, 3) + "  ", postcode,
                    prefKana, muniKana, townKana, pref, muni, town);
        }
    }
}
