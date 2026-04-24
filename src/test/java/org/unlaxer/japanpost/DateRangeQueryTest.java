package org.unlaxer.japanpost;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 日付範囲クエリ: diff / snapshotAt / findChangedAddresses / findChangedPostcodes のテスト。
 *
 * 辞書構成:
 *   KEN_ALL (現在 = 2026-02):
 *     1000001 千代田区A
 *     2000001 渋谷区A
 *   ADD_2601 (2026-01 に 2000001 追加):
 *     2000001 渋谷区A
 *   DEL_2601 (2026-01 に 2000099 削除):
 *     2000099 渋谷区B (旧エリア)
 *
 *   結果:
 *     snapshots[2026-02] = {1000001:千代田区A, 2000001:渋谷区A}
 *     snapshots[2026-01] = {1000001:千代田区A, 2000099:渋谷区B}
 */
class DateRangeQueryTest {

    private static final Charset MS932 = Charset.forName("MS932");

    private static HistoricalPostcodeDictionary buildDict(Path tmp) throws Exception {
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(tmp.resolve("KEN_ALL.CSV"), MS932))) {
            w.println("\"13101\",\"100  \",\"1000001\",\"ﾄｳｷｮｳﾄ\",\"ﾁﾖﾀﾞｸ\",\"ｴｰ\",\"東京都\",\"千代田区\",\"エリアA\"");
            w.println("\"13102\",\"200  \",\"2000001\",\"ﾄｳｷｮｳﾄ\",\"ｼﾌﾞﾔｸ\",\"ｴｰ\",\"東京都\",\"渋谷区\",\"エリアA\"");
        }
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(tmp.resolve("ADD_2601.CSV"), MS932))) {
            w.println("\"13102\",\"200  \",\"2000001\",\"ﾄｳｷｮｳﾄ\",\"ｼﾌﾞﾔｸ\",\"ｴｰ\",\"東京都\",\"渋谷区\",\"エリアA\"");
        }
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(tmp.resolve("DEL_2601.CSV"), MS932))) {
            w.println("\"13102\",\"200  \",\"2000099\",\"ﾄｳｷｮｳﾄ\",\"ｼﾌﾞﾔｸ\",\"ﾋﾞｰ\",\"東京都\",\"渋谷区\",\"エリアB\"");
        }
        return HistoricalPostcodeDictionary.build(tmp);
    }

    // ---- snapshotAt ----

    @Test
    void snapshotAt_latestMonth_containsAllCurrentEntries(@TempDir Path tmp) throws Exception {
        HistoricalPostcodeDictionary dict = buildDict(tmp);
        Map<String, List<PostcodeEntry>> snap = dict.snapshotAt(YearMonth.of(2026, 2));
        assertTrue(snap.containsKey("1000001"));
        assertTrue(snap.containsKey("2000001"));
        assertFalse(snap.containsKey("2000099"), "2000099 should not exist at 2026-02");
    }

    @Test
    void snapshotAt_olderMonth_containsOldEntries(@TempDir Path tmp) throws Exception {
        HistoricalPostcodeDictionary dict = buildDict(tmp);
        Map<String, List<PostcodeEntry>> snap = dict.snapshotAt(YearMonth.of(2026, 1));
        assertFalse(snap.containsKey("2000001"), "2000001 should not exist at 2026-01");
        assertTrue(snap.containsKey("2000099"), "2000099 should exist at 2026-01");
    }

    @Test
    void snapshotAt_veryOldDate_returnsEmpty(@TempDir Path tmp) throws Exception {
        HistoricalPostcodeDictionary dict = buildDict(tmp);
        Map<String, List<PostcodeEntry>> snap = dict.snapshotAt(YearMonth.of(1980, 1));
        assertTrue(snap.isEmpty());
    }

    // ---- diff ----

    @Test
    void diff_addedCountMatchesExpected(@TempDir Path tmp) throws Exception {
        HistoricalPostcodeDictionary dict = buildDict(tmp);
        HistoricalPostcodeDictionary.Diff diff =
                dict.diff(YearMonth.of(2026, 1), YearMonth.of(2026, 2));
        // 2026-02 に追加された 2000001:渋谷区A
        assertEquals(1, diff.addedCount());
    }

    @Test
    void diff_removedCountMatchesExpected(@TempDir Path tmp) throws Exception {
        HistoricalPostcodeDictionary dict = buildDict(tmp);
        HistoricalPostcodeDictionary.Diff diff =
                dict.diff(YearMonth.of(2026, 1), YearMonth.of(2026, 2));
        // 2026-01 → 2026-02 で消えた 2000099:渋谷区B
        assertEquals(1, diff.removedCount());
    }

    @Test
    void diff_addedContainsCorrectPostcode(@TempDir Path tmp) throws Exception {
        HistoricalPostcodeDictionary dict = buildDict(tmp);
        HistoricalPostcodeDictionary.Diff diff =
                dict.diff(YearMonth.of(2026, 1), YearMonth.of(2026, 2));
        assertTrue(diff.added().containsKey("2000001"),
                "2000001 should be in added map");
    }

    @Test
    void diff_removedContainsCorrectPostcode(@TempDir Path tmp) throws Exception {
        HistoricalPostcodeDictionary dict = buildDict(tmp);
        HistoricalPostcodeDictionary.Diff diff =
                dict.diff(YearMonth.of(2026, 1), YearMonth.of(2026, 2));
        assertTrue(diff.removed().containsKey("2000099"),
                "2000099 should be in removed map");
    }

    @Test
    void diff_sameMonth_zeroDiff(@TempDir Path tmp) throws Exception {
        HistoricalPostcodeDictionary dict = buildDict(tmp);
        HistoricalPostcodeDictionary.Diff diff =
                dict.diff(YearMonth.of(2026, 2), YearMonth.of(2026, 2));
        assertEquals(0, diff.addedCount());
        assertEquals(0, diff.removedCount());
    }

    @Test
    void diff_fromAndToFieldsAreSet(@TempDir Path tmp) throws Exception {
        HistoricalPostcodeDictionary dict = buildDict(tmp);
        YearMonth from = YearMonth.of(2026, 1);
        YearMonth to = YearMonth.of(2026, 2);
        HistoricalPostcodeDictionary.Diff diff = dict.diff(from, to);
        assertEquals(from, diff.from());
        assertEquals(to, diff.to());
    }

    // ---- findChangedAddresses ----

    @Test
    void findChangedAddresses_noChanges_returnsEmpty(@TempDir Path tmp) throws Exception {
        // 変化のない1スナップショット辞書
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(tmp.resolve("KEN_ALL.CSV"), MS932))) {
            w.println("\"01101\",\"060  \",\"0600001\",\"ﾎｯｶｲﾄﾞｳ\",\"ｻｯﾎﾟﾛｼ\",\"ｲｶﾆ\",\"北海道\",\"札幌市中央区\",\"以下に掲載がない場合\"");
        }
        HistoricalPostcodeDictionary dict = HistoricalPostcodeDictionary.build(tmp);
        List<HistoricalPostcodeDictionary.AddressChange> changes = dict.findChangedAddresses();
        assertTrue(changes.isEmpty(), "No address postcode changes in a single-snapshot dict");
    }

    // ---- findChangedPostcodes ----

    @Test
    void findChangedPostcodes_noChanges_returnsEmpty(@TempDir Path tmp) throws Exception {
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(tmp.resolve("KEN_ALL.CSV"), MS932))) {
            w.println("\"01101\",\"060  \",\"0600001\",\"ﾎｯｶｲﾄﾞｳ\",\"ｻｯﾎﾟﾛｼ\",\"ｲｶﾆ\",\"北海道\",\"札幌市中央区\",\"以下に掲載がない場合\"");
        }
        HistoricalPostcodeDictionary dict = HistoricalPostcodeDictionary.build(tmp);
        List<HistoricalPostcodeDictionary.PostcodeChange> changes = dict.findChangedPostcodes();
        assertTrue(changes.isEmpty());
    }
}
