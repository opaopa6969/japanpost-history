package org.unlaxer.japanpost;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.List;
import java.util.NavigableMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 変遷 API (postcodePeriods / postcodeAddressPeriods / postcodeHistory) と
 * メタ情報 API (earliestMonth / latestMonth / snapshotCount 等) のテスト。
 */
class HistoryTraversalTest {

    private static final Charset MS932 = Charset.forName("MS932");

    /**
     * 郵便番号が途中で変わる2スナップショット辞書を構築する。
     *
     * KEN_ALL (最新 = 2601+1 = 2026-02):
     *   1000001 千代田区イカニ  (新しい方)
     *
     * ADD_2601.CSV (2026-01 追加):
     *   1000001 千代田区イカニ
     *
     * DEL_2601.CSV (2026-01 削除):
     *   1000099 千代田区旧イカニ  → 2026-01 以前は 1000099 が存在した
     *
     * この設定で:
     *   2026-01 スナップショット = {1000099, 1000001}  (ADD を引いて DEL を戻す)
     *   Wait — build() はこう動く:
     *     baseline = KEN_ALL = {1000001}
     *     latestMonth = ADD最大月+1 = 2026-02
     *     snapshots[2026-02] = {1000001}
     *     月 2026-01: state - ADD_2601 + DEL_2601
     *       state = {1000001} - {1000001} + {1000099} = {1000099}
     *     snapshots[2026-01] = {1000099}
     */
    private static HistoricalPostcodeDictionary buildTwoSnapshotDict(Path tmp) throws Exception {
        // KEN_ALL: 最新状態
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(tmp.resolve("KEN_ALL.CSV"), MS932))) {
            w.println("\"13101\",\"100  \",\"1000001\",\"ﾄｳｷｮｳﾄ\",\"ﾁﾖﾀﾞｸ\",\"ｲｶﾆ\",\"東京都\",\"千代田区\",\"以下に掲載がない場合\"");
        }
        // ADD_2601: 2026-01 に 1000001 が追加された → 2026-01 以前には存在しない
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(tmp.resolve("ADD_2601.CSV"), MS932))) {
            w.println("\"13101\",\"100  \",\"1000001\",\"ﾄｳｷｮｳﾄ\",\"ﾁﾖﾀﾞｸ\",\"ｲｶﾆ\",\"東京都\",\"千代田区\",\"以下に掲載がない場合\"");
        }
        // DEL_2601: 2026-01 に 1000099 が削除された → 2026-01 以前には存在していた
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(tmp.resolve("DEL_2601.CSV"), MS932))) {
            w.println("\"13101\",\"100  \",\"1000099\",\"ﾄｳｷｮｳﾄ\",\"ﾁﾖﾀﾞｸ\",\"ｷｭｳ\",\"東京都\",\"千代田区\",\"旧エリア\"");
        }
        return HistoricalPostcodeDictionary.build(tmp);
    }

    // ---- meta information API ----

    @Test
    void metaInfo_snapshotCount_twoSnapshots(@TempDir Path tmp) throws Exception {
        HistoricalPostcodeDictionary dict = buildTwoSnapshotDict(tmp);
        // snapshots[2026-01] と snapshots[2026-02] の 2つ
        assertEquals(2, dict.snapshotCount());
    }

    @Test
    void metaInfo_earliestAndLatestMonth(@TempDir Path tmp) throws Exception {
        HistoricalPostcodeDictionary dict = buildTwoSnapshotDict(tmp);
        assertEquals(YearMonth.of(2026, 1), dict.earliestMonth());
        assertEquals(YearMonth.of(2026, 2), dict.latestMonth());
    }

    @Test
    void metaInfo_uniquePostcodeCount(@TempDir Path tmp) throws Exception {
        HistoricalPostcodeDictionary dict = buildTwoSnapshotDict(tmp);
        // 1000001 と 1000099 の2件
        assertEquals(2, dict.uniquePostcodeCount());
    }

    @Test
    void metaInfo_uniqueAddressCount(@TempDir Path tmp) throws Exception {
        HistoricalPostcodeDictionary dict = buildTwoSnapshotDict(tmp);
        // "東京都|千代田区|以下に掲載がない場合" と "東京都|千代田区|旧エリア" の2件
        assertEquals(2, dict.uniqueAddressCount());
    }

    // ---- postcodeHistory ----

    @Test
    void postcodeHistory_existingPostcode_hasAllMonths(@TempDir Path tmp) throws Exception {
        HistoricalPostcodeDictionary dict = buildTwoSnapshotDict(tmp);
        NavigableMap<YearMonth, List<PostcodeEntry>> history = dict.postcodeHistory("1000001");
        // 1000001 は 2026-02 スナップショットのみに存在
        assertEquals(1, history.size());
        assertTrue(history.containsKey(YearMonth.of(2026, 2)));
    }

    @Test
    void postcodeHistory_oldPostcode_presentOnlyInOlderSnapshot(@TempDir Path tmp) throws Exception {
        HistoricalPostcodeDictionary dict = buildTwoSnapshotDict(tmp);
        NavigableMap<YearMonth, List<PostcodeEntry>> history = dict.postcodeHistory("1000099");
        assertEquals(1, history.size());
        assertTrue(history.containsKey(YearMonth.of(2026, 1)));
    }

    @Test
    void postcodeHistory_unknownPostcode_returnsEmpty(@TempDir Path tmp) throws Exception {
        HistoricalPostcodeDictionary dict = buildTwoSnapshotDict(tmp);
        NavigableMap<YearMonth, List<PostcodeEntry>> history = dict.postcodeHistory("9999999");
        assertTrue(history.isEmpty());
    }

    // ---- postcodePeriods ----

    @Test
    void postcodePeriods_stableAddress_onePeriodNullTo(@TempDir Path tmp) throws Exception {
        // 1エントリのみ、変化なし → 1期間、to=null
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(tmp.resolve("KEN_ALL.CSV"), MS932))) {
            w.println("\"01101\",\"060  \",\"0600001\",\"ﾎｯｶｲﾄﾞｳ\",\"ｻｯﾎﾟﾛｼ\",\"ｲｶﾆ\",\"北海道\",\"札幌市中央区\",\"以下に掲載がない場合\"");
        }
        HistoricalPostcodeDictionary dict = HistoricalPostcodeDictionary.build(tmp);
        List<HistoricalPostcodeDictionary.PostcodePeriod> periods =
                dict.postcodePeriods("北海道", "札幌市中央区", "以下に掲載がない場合");
        assertEquals(1, periods.size());
        assertNull(periods.get(0).to(), "Ongoing period must have to=null");
        assertEquals("0600001", periods.get(0).postcode());
    }

    @Test
    void postcodePeriods_unknownAddress_returnsEmpty(@TempDir Path tmp) throws Exception {
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(tmp.resolve("KEN_ALL.CSV"), MS932))) {
            w.println("\"01101\",\"060  \",\"0600001\",\"ﾎｯｶｲﾄﾞｳ\",\"ｻｯﾎﾟﾛｼ\",\"ｲｶﾆ\",\"北海道\",\"札幌市中央区\",\"以下に掲載がない場合\"");
        }
        HistoricalPostcodeDictionary dict = HistoricalPostcodeDictionary.build(tmp);
        List<HistoricalPostcodeDictionary.PostcodePeriod> periods =
                dict.postcodePeriods("存在しない県", "市", "町");
        assertTrue(periods.isEmpty());
    }

    // ---- postcodeAddressPeriods ----

    @Test
    void postcodeAddressPeriods_stablePostcode_onePeriodNullTo(@TempDir Path tmp) throws Exception {
        HistoricalPostcodeDictionary dict = buildTwoSnapshotDict(tmp);
        List<HistoricalPostcodeDictionary.AddressPeriod> periods =
                dict.postcodeAddressPeriods("1000001");
        assertEquals(1, periods.size());
        assertNull(periods.get(0).to());
        assertFalse(periods.get(0).entries().isEmpty());
    }

    @Test
    void postcodeAddressPeriods_unknownPostcode_returnsEmpty(@TempDir Path tmp) throws Exception {
        HistoricalPostcodeDictionary dict = buildTwoSnapshotDict(tmp);
        List<HistoricalPostcodeDictionary.AddressPeriod> periods =
                dict.postcodeAddressPeriods("9999999");
        assertTrue(periods.isEmpty());
    }
}
