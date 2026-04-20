package org.unlaxer.japanpost;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PostcodeEntry のユニットテスト。
 */
class PostcodeEntryTest {

    @Test
    void fromCsvLine_parsesStandardLine() {
        String line = "\"01101\",\"060  \",\"0600001\",\"ﾎｯｶｲﾄﾞｳ\",\"ｻｯﾎﾟﾛｼﾁｭｳｵｳｸ\",\"ｲｶﾆｹｲｻｲｶﾞﾅｲﾊﾞｱｲ\",\"北海道\",\"札幌市中央区\",\"以下に掲載がない場合\"";
        PostcodeEntry entry = PostcodeEntry.fromCsvLine(line);
        assertNotNull(entry);
        assertEquals("01101", entry.lgCode());
        assertEquals("0600001", entry.postcode());
        assertEquals("北海道", entry.prefecture());
        assertEquals("札幌市中央区", entry.municipality());
        assertEquals("以下に掲載がない場合", entry.town());
    }

    @Test
    void fromCsvLine_returnsNullForShortLine() {
        assertNull(PostcodeEntry.fromCsvLine("a,b,c"));
    }

    @Test
    void isCatchAll_trueForCatchAllTown() {
        PostcodeEntry entry = new PostcodeEntry("01101", "0600001", "", "", "",
                "北海道", "札幌市中央区", "以下に掲載がない場合");
        assertTrue(entry.isCatchAll());
    }

    @Test
    void isCatchAll_falseForNormalTown() {
        PostcodeEntry entry = new PostcodeEntry("01101", "0600001", "", "", "",
                "北海道", "札幌市中央区", "大通西");
        assertFalse(entry.isCatchAll());
    }

    @Test
    void normalizeKana_convertsHalfToFullKatakana() {
        PostcodeEntry entry = new PostcodeEntry("01101", "0600001",
                "ﾎｯｶｲﾄﾞｳ", "ｻｯﾎﾟﾛｼ", "ｵｵﾄﾞｵﾘﾆｼ",
                "北海道", "札幌市", "大通西");
        PostcodeEntry normalized = entry.normalizeKana();
        assertEquals("ホッカイドウ", normalized.prefectureKana());
        assertEquals("サッポロシ", normalized.municipalityKana());
        assertEquals("オオドオリニシ", normalized.townKana());
        // 正規化後も他フィールドは変わらない
        assertEquals(entry.lgCode(), normalized.lgCode());
        assertEquals(entry.postcode(), normalized.postcode());
        assertEquals(entry.prefecture(), normalized.prefecture());
    }

    @Test
    void townWithoutParens_removesParentheses() {
        PostcodeEntry entry = new PostcodeEntry("01101", "0600001", "", "", "",
                "北海道", "札幌市", "大通西（1〜19丁目）");
        assertEquals("大通西", entry.townWithoutParens());
    }

    @Test
    void addressKey_format() {
        PostcodeEntry entry = new PostcodeEntry("01101", "0600001", "", "", "",
                "北海道", "札幌市中央区", "大通西");
        assertEquals("北海道|札幌市中央区|大通西", entry.addressKey());
    }

    @Test
    void mergeContinuationRows_mergesUnclosedParens() {
        PostcodeEntry row1 = new PostcodeEntry("01101", "0600001", "ﾎｯｶｲﾄﾞｳ", "ｻｯﾎﾟﾛｼ", "ｱ（1",
                "北海道", "札幌市", "アイウ（1");
        PostcodeEntry row2 = new PostcodeEntry("01101", "0600001", "ﾎｯｶｲﾄﾞｳ", "ｻｯﾎﾟﾛｼ", "2）",
                "北海道", "札幌市", "2）");
        List<PostcodeEntry> merged = PostcodeEntry.mergeContinuationRows(List.of(row1, row2));
        assertEquals(1, merged.size());
        assertEquals("アイウ（12）", merged.get(0).town());
    }

    @Test
    void halfToFullKana_dakutenCombination() {
        // ｶﾞ → ガ (カ+濁点 → ガ)
        assertEquals("ガ", PostcodeEntry.halfToFullKana("\uff76\uff9e"));
        // ﾊﾟ → パ (ハ+半濁点 → パ)
        assertEquals("パ", PostcodeEntry.halfToFullKana("\uff8a\uff9f"));
    }

    @Test
    void parseCsvLine_handlesQuotedFields() {
        String[] cols = PostcodeEntry.parseCsvLine("\"hello\",\"world, foo\",\"bar\"");
        assertEquals(3, cols.length);
        assertEquals("hello", cols[0]);
        assertEquals("world, foo", cols[1]);
        assertEquals("bar", cols[2]);
    }
}
