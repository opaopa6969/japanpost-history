package org.unlaxer.japanpost;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DictionarySnapshot の StringPool 単体テストおよび overflow guard のテスト。
 */
class DictionarySnapshotTest {

    // ---- StringPool round-trip ----

    @Test
    void stringPool_addAndGet() throws IOException {
        DictionarySnapshot.StringPool pool = new DictionarySnapshot.StringPool();
        pool.add("北海道");
        pool.add("東京都");
        pool.add("北海道"); // duplicate: no-op
        pool.freeze();

        assertEquals(2, pool.size());
        assertEquals(0, pool.indexOf("北海道"));
        assertEquals(1, pool.indexOf("東京都"));
        assertEquals("北海道", pool.get(0));
        assertEquals("東京都", pool.get(1));
    }

    @Test
    void stringPool_writeAndRead_roundTrip() throws IOException {
        DictionarySnapshot.StringPool original = new DictionarySnapshot.StringPool();
        original.add("ABC");
        original.add("DEF");
        original.add("日本語テスト");
        original.freeze();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        original.writeTo(dos);
        dos.flush();

        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(baos.toByteArray()));
        DictionarySnapshot.StringPool restored = DictionarySnapshot.StringPool.readFrom(dis);

        assertEquals(3, restored.size());
        assertEquals("ABC", restored.get(0));
        assertEquals("DEF", restored.get(1));
        assertEquals("日本語テスト", restored.get(2));
    }

    @Test
    void stringPool_emptyStringRoundTrip() throws IOException {
        DictionarySnapshot.StringPool pool = new DictionarySnapshot.StringPool();
        pool.add("");
        pool.freeze();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        pool.writeTo(new DataOutputStream(baos));
        DictionarySnapshot.StringPool restored = DictionarySnapshot.StringPool.readFrom(
                new DataInputStream(new ByteArrayInputStream(baos.toByteArray())));

        assertEquals(1, restored.size());
        assertEquals("", restored.get(0));
    }

    // ---- uint16 overflow guard ----

    @Test
    void write_throwsWhenAddCountExceedsUint16(@TempDir Path tmp) throws Exception {
        Path csvDir = tmp.resolve("csv");
        java.nio.file.Files.createDirectories(csvDir);

        // KEN_ALL.CSV (1行)
        Path kenAll = csvDir.resolve("KEN_ALL.CSV");
        writeMinimalCsv(kenAll, "0600001", "01101", "北海道", "札幌市中央区", "大通西");

        // ADD_2601.CSV に 65536 件 → addCount uint16 overflow → IllegalStateException
        Path addFile = csvDir.resolve("ADD_2601.CSV");
        try (var writer = new java.io.PrintWriter(
                java.nio.file.Files.newBufferedWriter(addFile, java.nio.charset.Charset.forName("MS932")))) {
            for (int i = 0; i < 65536; i++) {
                String pc = String.format("%07d", i);
                writer.printf("\"01101\",\"%s\",\"%s\",\"ﾎｯｶｲﾄﾞｳ\",\"ｻｯﾎﾟﾛｼ\",\"ﾃｽﾄ\",\"北海道\",\"札幌市\",\"テスト%d\"%n",
                        pc.substring(0, 3) + "  ", pc, i);
            }
        }

        Path output = tmp.resolve("out.snapshot");
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> DictionarySnapshot.write(csvDir, output));
        assertTrue(ex.getMessage().contains("65535"),
                "Exception message should mention uint16 limit, got: " + ex.getMessage());
    }

    // ---- helper ----

    private static void writeMinimalCsv(Path path, String postcode, String lgCode,
            String pref, String muni, String town) throws Exception {
        try (var w = new java.io.PrintWriter(Files.newBufferedWriter(path, Charset.forName("MS932")))) {
            w.printf("\"%s\",\"%s\",\"%s\",\"ﾎｯｶｲﾄﾞｳ\",\"ｻｯﾎﾟﾛｼ\",\"ｱ\",\"%s\",\"%s\",\"%s\"%n",
                    lgCode, postcode.substring(0, 3) + "  ", postcode, pref, muni, town);
        }
    }
}
