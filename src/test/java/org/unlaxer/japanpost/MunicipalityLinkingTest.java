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
 * 市区町村連携（municipality linking）のテスト。
 *
 * 市区町村合併シミュレーション:
 *   KEN_ALL (最新 = 2026-02):
 *     1234567 新市A区
 *   ADD_2601 (2026-01 追加):
 *     1234567 新市A区   ← 合併後の名称が追加
 *   DEL_2601 (2026-01 削除):
 *     1234567 旧市A区   ← 合併前の名称が削除
 *
 *   この構成では:
 *     snapshots[2026-02] = {1234567: 新市A区}
 *     snapshots[2026-01] = {1234567: 旧市A区}
 *
 * → 同じ郵便番号で住所名が変わった → postcodeAddressPeriods で2期間
 * → 同じ住所名で郵便番号は変わっていない → postcodePeriods は1期間（各住所名で）
 */
class MunicipalityLinkingTest {

    private static final Charset MS932 = Charset.forName("MS932");

    private static HistoricalPostcodeDictionary buildMergerDict(Path tmp) throws Exception {
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(tmp.resolve("KEN_ALL.CSV"), MS932))) {
            w.println("\"13901\",\"123  \",\"1234567\",\"ﾄｳｷｮｳﾄ\",\"ｼﾝｼ\",\"ｴｰｸ\",\"東京都\",\"新市\",\"A区\"");
        }
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(tmp.resolve("ADD_2601.CSV"), MS932))) {
            w.println("\"13901\",\"123  \",\"1234567\",\"ﾄｳｷｮｳﾄ\",\"ｼﾝｼ\",\"ｴｰｸ\",\"東京都\",\"新市\",\"A区\"");
        }
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(tmp.resolve("DEL_2601.CSV"), MS932))) {
            w.println("\"13901\",\"123  \",\"1234567\",\"ﾄｳｷｮｳﾄ\",\"ｷｭｳｼ\",\"ｴｰｸ\",\"東京都\",\"旧市\",\"A区\"");
        }
        return HistoricalPostcodeDictionary.build(tmp);
    }

    // ---- postcodeAddressPeriods: 住所名変遷 ----

    @Test
    void postcodeAddressPeriods_addressChanged_hasMultiplePeriods(@TempDir Path tmp) throws Exception {
        HistoricalPostcodeDictionary dict = buildMergerDict(tmp);
        List<HistoricalPostcodeDictionary.AddressPeriod> periods =
                dict.postcodeAddressPeriods("1234567");
        // 旧市A区 → 新市A区 の2期間
        assertEquals(2, periods.size());
    }

    @Test
    void postcodeAddressPeriods_firstPeriodHasOlderAddress(@TempDir Path tmp) throws Exception {
        HistoricalPostcodeDictionary dict = buildMergerDict(tmp);
        List<HistoricalPostcodeDictionary.AddressPeriod> periods =
                dict.postcodeAddressPeriods("1234567");
        // 最初の期間は旧市 A区
        PostcodeEntry firstEntry = periods.get(0).entries().get(0);
        assertEquals("旧市", firstEntry.municipality());
    }

    @Test
    void postcodeAddressPeriods_lastPeriodHasNewerAddress(@TempDir Path tmp) throws Exception {
        HistoricalPostcodeDictionary dict = buildMergerDict(tmp);
        List<HistoricalPostcodeDictionary.AddressPeriod> periods =
                dict.postcodeAddressPeriods("1234567");
        PostcodeEntry lastEntry = periods.get(periods.size() - 1).entries().get(0);
        assertEquals("新市", lastEntry.municipality());
    }

    @Test
    void postcodeAddressPeriods_lastPeriodToIsNull(@TempDir Path tmp) throws Exception {
        HistoricalPostcodeDictionary dict = buildMergerDict(tmp);
        List<HistoricalPostcodeDictionary.AddressPeriod> periods =
                dict.postcodeAddressPeriods("1234567");
        // 最後の期間は現在まで有効
        assertNull(periods.get(periods.size() - 1).to());
    }

    @Test
    void postcodeAddressPeriods_firstPeriodToEqualsSecondPeriodFrom(@TempDir Path tmp) throws Exception {
        HistoricalPostcodeDictionary dict = buildMergerDict(tmp);
        List<HistoricalPostcodeDictionary.AddressPeriod> periods =
                dict.postcodeAddressPeriods("1234567");
        // 隣接期間: period[0].to == period[1].from
        assertEquals(periods.get(0).to(), periods.get(1).from());
    }

    // ---- findChangedPostcodes: 住所名が変わった郵便番号検出 ----

    @Test
    void findChangedPostcodes_detectedAfterMerger(@TempDir Path tmp) throws Exception {
        HistoricalPostcodeDictionary dict = buildMergerDict(tmp);
        List<HistoricalPostcodeDictionary.PostcodeChange> changes = dict.findChangedPostcodes();
        assertFalse(changes.isEmpty(), "Should detect address change for 1234567");
        assertTrue(changes.stream().anyMatch(c -> c.postcode().equals("1234567")));
    }

    @Test
    void findChangedPostcodes_periodsCountCorrect(@TempDir Path tmp) throws Exception {
        HistoricalPostcodeDictionary dict = buildMergerDict(tmp);
        HistoricalPostcodeDictionary.PostcodeChange change = dict.findChangedPostcodes().stream()
                .filter(c -> c.postcode().equals("1234567"))
                .findFirst().orElseThrow();
        assertEquals(2, change.periods().size());
    }

    // ---- postcodePeriods: 住所単位の郵便番号変遷 ----

    @Test
    void postcodePeriods_newMunicipality_onePeriodSinceChangeMonth(@TempDir Path tmp) throws Exception {
        HistoricalPostcodeDictionary dict = buildMergerDict(tmp);
        // 「新市A区」は 2026-02 以降のみ存在する
        List<HistoricalPostcodeDictionary.PostcodePeriod> periods =
                dict.postcodePeriods("東京都", "新市", "A区");
        assertEquals(1, periods.size());
        assertEquals("1234567", periods.get(0).postcode());
        // 合併月以降の期間開始
        assertEquals(YearMonth.of(2026, 2), periods.get(0).from());
    }

    @Test
    void postcodePeriods_oldMunicipality_onePeriodBeforeChangeMonth(@TempDir Path tmp) throws Exception {
        HistoricalPostcodeDictionary dict = buildMergerDict(tmp);
        // 「旧市A区」は 2026-01 スナップショットのみに存在
        List<HistoricalPostcodeDictionary.PostcodePeriod> periods =
                dict.postcodePeriods("東京都", "旧市", "A区");
        assertEquals(1, periods.size());
        assertEquals("1234567", periods.get(0).postcode());
    }

    // ---- lookup across timeline ----

    @Test
    void lookup_olderMonth_returnsOlderMunicipalityName(@TempDir Path tmp) throws Exception {
        HistoricalPostcodeDictionary dict = buildMergerDict(tmp);
        List<PostcodeEntry> entries = dict.lookup("1234567", YearMonth.of(2026, 1));
        assertFalse(entries.isEmpty());
        assertEquals("旧市", entries.get(0).municipality());
    }

    @Test
    void lookup_newerMonth_returnsNewMunicipalityName(@TempDir Path tmp) throws Exception {
        HistoricalPostcodeDictionary dict = buildMergerDict(tmp);
        List<PostcodeEntry> entries = dict.lookup("1234567", YearMonth.of(2026, 2));
        assertFalse(entries.isEmpty());
        assertEquals("新市", entries.get(0).municipality());
    }

    // ---- addressPostcodeHistory across timeline ----

    @Test
    void addressPostcodeHistory_newAddress_startsAtMergeMonth(@TempDir Path tmp) throws Exception {
        HistoricalPostcodeDictionary dict = buildMergerDict(tmp);
        NavigableMap<YearMonth, String> history =
                dict.addressPostcodeHistory("東京都", "新市", "A区");
        // 新市A区は2026-02スナップショットのみに存在
        assertFalse(history.isEmpty());
        assertEquals(YearMonth.of(2026, 2), history.firstKey());
        assertEquals("1234567", history.get(YearMonth.of(2026, 2)));
    }
}
