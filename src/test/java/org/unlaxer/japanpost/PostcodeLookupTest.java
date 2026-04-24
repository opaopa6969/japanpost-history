package org.unlaxer.japanpost;

import org.junit.jupiter.api.BeforeEach;
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
 * 郵便番号検索 API (lookup / lookupByAddress / lookupByPrefix / addressPostcodeHistory) のテスト。
 */
class PostcodeLookupTest {

    private static final Charset MS932 = Charset.forName("MS932");

    /** テスト用辞書: 東京都2件 + 北海道1件 */
    private HistoricalPostcodeDictionary dict;

    @BeforeEach
    void setUp(@TempDir Path tmp) throws Exception {
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(tmp.resolve("KEN_ALL.CSV"), MS932))) {
            w.println("\"13101\",\"100  \",\"1000001\",\"ﾄｳｷｮｳﾄ\",\"ﾁﾖﾀﾞｸ\",\"ｲｶﾆ\",\"東京都\",\"千代田区\",\"以下に掲載がない場合\"");
            w.println("\"13101\",\"100  \",\"1000002\",\"ﾄｳｷｮｳﾄ\",\"ﾁﾖﾀﾞｸ\",\"ｺｳｷｮｶｲﾜｲ\",\"東京都\",\"千代田区\",\"皇居外苑\"");
            w.println("\"01101\",\"060  \",\"0600001\",\"ﾎｯｶｲﾄﾞｳ\",\"ｻｯﾎﾟﾛｼ\",\"ｲｶﾆ\",\"北海道\",\"札幌市中央区\",\"以下に掲載がない場合\"");
        }
        dict = HistoricalPostcodeDictionary.build(tmp);
    }

    // ---- lookup: postcode → address at point in time ----

    @Test
    void lookup_existingPostcode_returnsEntry() {
        List<PostcodeEntry> result = dict.lookup("1000001", dict.latestMonth());
        assertEquals(1, result.size());
        assertEquals("東京都", result.get(0).prefecture());
        assertEquals("千代田区", result.get(0).municipality());
    }

    @Test
    void lookup_unknownPostcode_returnsEmptyList() {
        List<PostcodeEntry> result = dict.lookup("9999999", dict.latestMonth());
        assertTrue(result.isEmpty(), "Unknown postcode must return empty list");
    }

    @Test
    void lookup_beforeAnySnapshot_returnsEmptyList() {
        // 1900-01 はどのスナップショットよりも前
        List<PostcodeEntry> result = dict.lookup("1000001", YearMonth.of(1900, 1));
        assertTrue(result.isEmpty());
    }

    @Test
    void lookup_usesFloorEntry_futureMonthReturnsLatest() {
        // 遠い未来の日付でも最新スナップショットが返る
        List<PostcodeEntry> result = dict.lookup("1000001", YearMonth.of(2099, 12));
        assertFalse(result.isEmpty(), "Future date should fall back to latest snapshot");
        assertEquals("東京都", result.get(0).prefecture());
    }

    @Test
    void lookup_multipleEntriesForSamePostcode() {
        // 1000001 と 1000002 は別郵便番号なのでそれぞれ1件
        assertEquals(1, dict.lookup("1000001", dict.latestMonth()).size());
        assertEquals(1, dict.lookup("1000002", dict.latestMonth()).size());
    }

    // ---- lookupByAddress: address → postcode ----

    @Test
    void lookupByAddress_returnsCorrectPostcode() {
        String postcode = dict.lookupByAddress("東京都", "千代田区", "以下に掲載がない場合", dict.latestMonth());
        assertEquals("1000001", postcode);
    }

    @Test
    void lookupByAddress_unknownAddress_returnsNull() {
        String postcode = dict.lookupByAddress("東京都", "千代田区", "存在しない町", dict.latestMonth());
        assertNull(postcode, "Unknown address must return null");
    }

    @Test
    void lookupByAddress_wrongPrefecture_returnsNull() {
        // 道府県が違うと null
        String postcode = dict.lookupByAddress("大阪府", "千代田区", "以下に掲載がない場合", dict.latestMonth());
        assertNull(postcode);
    }

    // ---- lookupByPrefix: prefix search ----

    @Test
    void lookupByPrefix_matchesAllEntriesUnderPrefix() {
        // "100" で始まる郵便番号は1000001と1000002の2件
        List<PostcodeEntry> results = dict.lookupByPrefix("100", dict.latestMonth(), 100);
        assertEquals(2, results.size());
        assertTrue(results.stream().allMatch(e -> e.postcode().startsWith("100")));
    }

    @Test
    void lookupByPrefix_limitEnforcedWhenExceeded() {
        // limit=1 なら1件だけ返る
        List<PostcodeEntry> results = dict.lookupByPrefix("100", dict.latestMonth(), 1);
        assertEquals(1, results.size());
    }

    @Test
    void lookupByPrefix_noMatch_returnsEmpty() {
        List<PostcodeEntry> results = dict.lookupByPrefix("999", dict.latestMonth(), 100);
        assertTrue(results.isEmpty());
    }

    @Test
    void lookupByPrefix_differentPrefix_doesNotOverlap() {
        List<PostcodeEntry> r060 = dict.lookupByPrefix("060", dict.latestMonth(), 100);
        List<PostcodeEntry> r100 = dict.lookupByPrefix("100", dict.latestMonth(), 100);
        // 060 と 100 のエントリが重複しないことを確認
        for (PostcodeEntry e : r060) {
            assertFalse(r100.contains(e));
        }
    }

    // ---- addressPostcodeHistory ----

    @Test
    void addressPostcodeHistory_existingAddress_returnsAllMonths() {
        NavigableMap<YearMonth, String> history =
                dict.addressPostcodeHistory("北海道", "札幌市中央区", "以下に掲載がない場合");
        assertFalse(history.isEmpty());
        // すべての月で同じ郵便番号
        assertTrue(history.values().stream().allMatch("0600001"::equals));
    }

    @Test
    void addressPostcodeHistory_unknownAddress_returnsEmptyMap() {
        NavigableMap<YearMonth, String> history =
                dict.addressPostcodeHistory("存在しない", "市", "町");
        assertTrue(history.isEmpty());
    }
}
