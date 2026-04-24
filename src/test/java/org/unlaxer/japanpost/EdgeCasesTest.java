package org.unlaxer.japanpost;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * エッジケースのテスト。
 *
 * - 空 ADD ファイル
 * - 郵便番号が空白の行のスキップ
 * - 括弧を含む複数行分割のマージ
 * - スナップショット magic / version エラー
 * - addressKey フォーマット
 * - PostcodePeriod / AddressPeriod toString
 * - lookupByPrefix limit=0 / limit=大
 * - parseCsvLine の引用符エスケープ
 */
class EdgeCasesTest {

    private static final Charset MS932 = Charset.forName("MS932");

    // ---- empty ADD/DEL files ----

    @Test
    void build_withEmptyAddFile_noException(@TempDir Path tmp) throws Exception {
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(tmp.resolve("KEN_ALL.CSV"), MS932))) {
            w.println("\"01101\",\"060  \",\"0600001\",\"ﾎｯｶｲﾄﾞｳ\",\"ｻｯﾎﾟﾛｼ\",\"ｲｶﾆ\",\"北海道\",\"札幌市中央区\",\"以下に掲載がない場合\"");
        }
        // 空の ADD ファイルを用意する（行なし）
        Files.createFile(tmp.resolve("ADD_2601.CSV"));
        HistoricalPostcodeDictionary dict = HistoricalPostcodeDictionary.build(tmp);
        assertNotNull(dict);
        // 空 ADD で delta 1ヶ月: snapshots は 2 つ (2026-01, 2026-02)
        assertEquals(2, dict.snapshotCount());
    }

    // ---- postcode with blank/garbage is skipped ----

    @Test
    void build_lineWithBlankPostcode_isSkipped(@TempDir Path tmp) throws Exception {
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(tmp.resolve("KEN_ALL.CSV"), MS932))) {
            // 空郵便番号の行 → スキップされる
            w.println("\"01101\",\"060  \",\"\",\"ﾎｯｶｲﾄﾞｳ\",\"ｻｯﾎﾟﾛｼ\",\"ｲｶﾆ\",\"北海道\",\"札幌市中央区\",\"以下に掲載がない場合\"");
            // 有効な行
            w.println("\"01101\",\"060  \",\"0600001\",\"ﾎｯｶｲﾄﾞｳ\",\"ｻｯﾎﾟﾛｼ\",\"ｲｶﾆ\",\"北海道\",\"札幌市中央区\",\"以下に掲載がない場合\"");
        }
        HistoricalPostcodeDictionary dict = HistoricalPostcodeDictionary.build(tmp);
        Map<String, List<PostcodeEntry>> snap = dict.snapshotAt(dict.latestMonth());
        assertEquals(1, snap.size());
        assertTrue(snap.containsKey("0600001"));
    }

    // ---- multi-line town merge (括弧分割) ----

    @Test
    void mergeContinuationRows_multiLineTown_mergesCorrectly() {
        PostcodeEntry row1 = new PostcodeEntry("01101", "0600001", "ﾎｯｶｲﾄﾞｳ", "ｻｯﾎﾟﾛｼ", "ｵｵﾄﾞｵﾘﾆｼ（1",
                "北海道", "札幌市中央区", "大通西（1");
        PostcodeEntry row2 = new PostcodeEntry("01101", "0600001", "ﾎｯｶｲﾄﾞｳ", "ｻｯﾎﾟﾛｼ", "19）",
                "北海道", "札幌市中央区", "19）");
        List<PostcodeEntry> merged = PostcodeEntry.mergeContinuationRows(List.of(row1, row2));
        assertEquals(1, merged.size());
        assertEquals("大通西（119）", merged.get(0).town());
    }

    @Test
    void mergeContinuationRows_differentPostcode_doesNotMerge() {
        PostcodeEntry row1 = new PostcodeEntry("01101", "0600001", "", "", "（",
                "北海道", "札幌市", "大通（");
        PostcodeEntry row2 = new PostcodeEntry("01101", "0600002", "", "", "続き）",
                "北海道", "札幌市", "続き）");
        // 郵便番号が違うのでマージしない
        List<PostcodeEntry> merged = PostcodeEntry.mergeContinuationRows(List.of(row1, row2));
        assertEquals(2, merged.size());
    }

    // ---- snapshot magic / version errors ----

    @Test
    void snapshotLoad_wrongMagic_throwsIOException(@TempDir Path tmp) throws Exception {
        Path badFile = tmp.resolve("bad.snapshot");
        try (java.util.zip.GZIPOutputStream gzos =
                     new java.util.zip.GZIPOutputStream(Files.newOutputStream(badFile));
             DataOutputStream dos = new DataOutputStream(gzos)) {
            dos.write("XXXX".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            dos.writeShort(1);
        }
        IOException ex = assertThrows(IOException.class,
                () -> HistoricalPostcodeDictionary.loadSnapshot(badFile));
        assertTrue(ex.getMessage().toLowerCase().contains("magic") || ex.getMessage().contains("XXXX"),
                "Exception should mention invalid magic, got: " + ex.getMessage());
    }

    @Test
    void snapshotLoad_wrongVersion_throwsIOException(@TempDir Path tmp) throws Exception {
        Path badFile = tmp.resolve("bad_ver.snapshot");
        try (java.util.zip.GZIPOutputStream gzos =
                     new java.util.zip.GZIPOutputStream(Files.newOutputStream(badFile));
             DataOutputStream dos = new DataOutputStream(gzos)) {
            dos.write("JPHS".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            dos.writeShort(99);
        }
        IOException ex = assertThrows(IOException.class,
                () -> HistoricalPostcodeDictionary.loadSnapshot(badFile));
        assertTrue(ex.getMessage().toLowerCase().contains("version") || ex.getMessage().contains("99"),
                "Exception should mention unsupported version, got: " + ex.getMessage());
    }

    // ---- addressKey format ----

    @Test
    void addressKey_containsPipeDelimitedTriple() {
        PostcodeEntry e = new PostcodeEntry("13101", "1000001", "", "", "",
                "東京都", "千代田区", "大手町");
        String key = e.addressKey();
        assertEquals("東京都|千代田区|大手町", key);
        assertEquals(2, key.chars().filter(c -> c == '|').count());
    }

    // ---- PostcodePeriod / AddressPeriod toString ----

    @Test
    void postcodePeriod_toString_withTo() {
        HistoricalPostcodeDictionary.PostcodePeriod p =
                new HistoricalPostcodeDictionary.PostcodePeriod(
                        YearMonth.of(2007, 10), YearMonth.of(2019, 6), "7808010");
        String s = p.toString();
        assertTrue(s.contains("7808010"));
        assertTrue(s.contains("2007-10"));
        assertTrue(s.contains("2019-06"));
    }

    @Test
    void postcodePeriod_toString_presentWhenToNull() {
        HistoricalPostcodeDictionary.PostcodePeriod p =
                new HistoricalPostcodeDictionary.PostcodePeriod(
                        YearMonth.of(2019, 6), null, "7818010");
        String s = p.toString();
        assertTrue(s.contains("present"));
    }

    @Test
    void addressPeriod_toString_containsAddress() {
        PostcodeEntry entry = new PostcodeEntry("01101", "0600001", "", "", "",
                "北海道", "石狩市", "厚田区厚田");
        HistoricalPostcodeDictionary.AddressPeriod p =
                new HistoricalPostcodeDictionary.AddressPeriod(
                        YearMonth.of(2007, 10), null, List.of(entry));
        String s = p.toString();
        assertTrue(s.contains("北海道"));
        assertTrue(s.contains("石狩市"));
        assertTrue(s.contains("厚田区厚田"));
    }

    // ---- lookupByPrefix edge cases ----

    @Test
    void lookupByPrefix_limitZero_returnsEmpty(@TempDir Path tmp) throws Exception {
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(tmp.resolve("KEN_ALL.CSV"), MS932))) {
            w.println("\"01101\",\"060  \",\"0600001\",\"ﾎｯｶｲﾄﾞｳ\",\"ｻｯﾎﾟﾛｼ\",\"ｲｶﾆ\",\"北海道\",\"札幌市中央区\",\"以下に掲載がない場合\"");
        }
        HistoricalPostcodeDictionary dict = HistoricalPostcodeDictionary.build(tmp);
        List<PostcodeEntry> results = dict.lookupByPrefix("060", dict.latestMonth(), 0);
        assertTrue(results.isEmpty(), "limit=0 should return empty list");
    }

    @Test
    void lookupByPrefix_largeLimitReturnsAll(@TempDir Path tmp) throws Exception {
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(tmp.resolve("KEN_ALL.CSV"), MS932))) {
            w.println("\"01101\",\"060  \",\"0600001\",\"ﾎｯｶｲﾄﾞｳ\",\"ｻｯﾎﾟﾛｼ\",\"ｲｶﾆ\",\"北海道\",\"札幌市中央区\",\"以下に掲載がない場合\"");
        }
        HistoricalPostcodeDictionary dict = HistoricalPostcodeDictionary.build(tmp);
        List<PostcodeEntry> results = dict.lookupByPrefix("060", dict.latestMonth(), Integer.MAX_VALUE);
        assertEquals(1, results.size());
    }

    // ---- parseCsvLine: double-quote escape ----

    @Test
    void parseCsvLine_doubleQuoteEscape_decodesCorrectly() {
        String[] cols = PostcodeEntry.parseCsvLine("\"hello \"\"world\"\"\",\"foo\"");
        assertEquals(2, cols.length);
        assertEquals("hello \"world\"", cols[0]);
        assertEquals("foo", cols[1]);
    }

    @Test
    void parseCsvLine_emptyFields_parsedCorrectly() {
        String[] cols = PostcodeEntry.parseCsvLine("\"\",\"\",\"abc\"");
        assertEquals(3, cols.length);
        assertEquals("", cols[0]);
        assertEquals("", cols[1]);
        assertEquals("abc", cols[2]);
    }

    // ---- halfToFullKana edge cases ----

    @Test
    void halfToFullKana_nonKanaCharsPassThrough() {
        String input = "ABC123";
        assertEquals("ABC123", PostcodeEntry.halfToFullKana(input));
    }

    @Test
    void halfToFullKana_emptyString_returnsEmpty() {
        assertEquals("", PostcodeEntry.halfToFullKana(""));
    }
}
