package org.unlaxer.japanpost;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * HistoricalPostcodeDictionary のバイナリスナップショット。
 *
 * <h3>フォーマット設計</h3>
 * 差分格納方式: ベースライン（最新月のフルデータ）+ 月次delta（ADD/DEL）で構成。
 * 全222ヶ月のdelta合計は ~17K行なので、ベースライン12万行 + delta 1.7万行 ≈ 14万行分のデータのみ。
 * GZIP圧縮で推定 3-5MB。
 *
 * <h3>バイナリレイアウト (GZIP wrapped)</h3>
 * <pre>
 * [Header]
 *   magic: "JPHS" (4 bytes)
 *   version: uint16
 *   baselineMonth: uint16 (year) + uint8 (month)
 *   baselineEntryCount: uint32
 *   deltaMonthCount: uint16
 *
 * [String Pool]
 *   poolSize: uint32
 *   strings: length-prefixed UTF-8 strings
 *
 * [Baseline Entries]
 *   For each entry:
 *     postcodeIdx: uint32 (string pool index)
 *     prefectureIdx: uint32
 *     municipalityIdx: uint32
 *     townIdx: uint32
 *     lgCodeIdx: uint32
 *     prefKanaIdx: uint32
 *     muniKanaIdx: uint32
 *     townKanaIdx: uint32
 *
 * [Delta Months] (newest to oldest)
 *   For each month:
 *     month: uint16 (year) + uint8 (month)
 *     addCount: uint16
 *     delCount: uint16
 *     [ADD entries] (same format as baseline)
 *     [DEL entries] (same format as baseline)
 * </pre>
 */
public class DictionarySnapshot {

    private static final byte[] MAGIC = "JPHS".getBytes(StandardCharsets.US_ASCII);
    private static final int VERSION = 1;

    /**
     * 辞書をバイナリスナップショットとして書き出す。
     * 書き出し元は CSV ディレクトリ（KEN_ALL + ADD/DEL）。
     */
    public static void write(Path csvDir, Path outputFile) throws IOException {
        System.out.println("Building snapshot from: " + csvDir);

        // KEN_ALL をベースラインとしてロード
        Path kenAllPath = csvDir.resolve("KEN_ALL.CSV");
        List<PostcodeEntry> baseline = loadAndMergeCsv(kenAllPath);
        System.out.printf("  Baseline: %,d entries\n", baseline.size());

        // ADD/DELファイルを列挙
        TreeMap<YearMonth, List<PostcodeEntry>> addByMonth = new TreeMap<>();
        TreeMap<YearMonth, List<PostcodeEntry>> delByMonth = new TreeMap<>();
        try (var stream = Files.list(csvDir)) {
            stream.forEach(p -> {
                String name = p.getFileName().toString().toUpperCase();
                try {
                    if (name.matches("ADD_\\d{4}\\.CSV")) {
                        YearMonth ym = parseYearMonth(name.substring(4, 8));
                        if (ym != null) addByMonth.put(ym, loadAndMergeCsv(p));
                    } else if (name.matches("DEL_\\d{4}\\.CSV")) {
                        YearMonth ym = parseYearMonth(name.substring(4, 8));
                        if (ym != null) delByMonth.put(ym, loadAndMergeCsv(p));
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }

        YearMonth baselineMonth = addByMonth.isEmpty() ? YearMonth.now()
                : addByMonth.lastKey().plusMonths(1);

        // String Pool を構築
        StringPool pool = new StringPool();
        for (PostcodeEntry e : baseline) indexEntry(pool, e);
        for (var entries : addByMonth.values()) for (PostcodeEntry e : entries) indexEntry(pool, e);
        for (var entries : delByMonth.values()) for (PostcodeEntry e : entries) indexEntry(pool, e);
        pool.freeze();
        System.out.printf("  String pool: %,d unique strings\n", pool.size());

        // 書き出し
        try (DataOutputStream dos = new DataOutputStream(
                new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(outputFile))))) {

            // Header
            dos.write(MAGIC);
            dos.writeShort(VERSION);
            dos.writeShort(baselineMonth.getYear());
            dos.writeByte(baselineMonth.getMonthValue());
            dos.writeInt(baseline.size());

            // Delta month count
            NavigableSet<YearMonth> allMonths = new TreeSet<>();
            allMonths.addAll(addByMonth.keySet());
            allMonths.addAll(delByMonth.keySet());
            dos.writeShort(allMonths.size());

            // String Pool
            pool.writeTo(dos);

            // Baseline
            for (PostcodeEntry e : baseline) writeEntry(dos, pool, e);

            // Deltas (newest first → 復元時に逆順適用)
            for (YearMonth month : allMonths.descendingSet()) {
                dos.writeShort(month.getYear());
                dos.writeByte(month.getMonthValue());
                List<PostcodeEntry> adds = addByMonth.getOrDefault(month, List.of());
                List<PostcodeEntry> dels = delByMonth.getOrDefault(month, List.of());
                // uint16 overflow guard: addCount and delCount are stored as unsigned short (max 65535)
                if (adds.size() > 65535) {
                    throw new IllegalStateException(
                            "ADD entry count " + adds.size() + " for " + month +
                            " exceeds uint16 limit of 65535. Cannot write snapshot without data corruption.");
                }
                if (dels.size() > 65535) {
                    throw new IllegalStateException(
                            "DEL entry count " + dels.size() + " for " + month +
                            " exceeds uint16 limit of 65535. Cannot write snapshot without data corruption.");
                }
                dos.writeShort(adds.size());
                dos.writeShort(dels.size());
                for (PostcodeEntry e : adds) writeEntry(dos, pool, e);
                for (PostcodeEntry e : dels) writeEntry(dos, pool, e);
            }
        }

        long size = Files.size(outputFile);
        System.out.printf("  Snapshot written: %s (%,d bytes = %.1f MB)\n",
                outputFile, size, size / 1024.0 / 1024.0);
    }

    /**
     * バイナリスナップショットから辞書を復元。
     */
    public static HistoricalPostcodeDictionary load(Path snapshotFile) throws IOException {
        System.out.println("Loading snapshot from: " + snapshotFile);
        long startMs = System.currentTimeMillis();

        try (DataInputStream dis = new DataInputStream(
                new BufferedInputStream(new GZIPInputStream(Files.newInputStream(snapshotFile))))) {

            // Header
            byte[] magic = new byte[4];
            dis.readFully(magic);
            if (!Arrays.equals(magic, MAGIC)) {
                throw new IOException("Invalid snapshot magic: " + new String(magic));
            }
            int version = dis.readUnsignedShort();
            if (version != VERSION) {
                throw new IOException("Unsupported snapshot version: " + version);
            }
            int baseYear = dis.readUnsignedShort();
            int baseMonth = dis.readUnsignedByte();
            YearMonth baselineMonth = YearMonth.of(baseYear, baseMonth);
            int baselineCount = dis.readInt();
            int deltaMonthCount = dis.readUnsignedShort();

            // String Pool
            StringPool pool = StringPool.readFrom(dis);

            // Baseline
            Set<PostcodeEntry> baseline = new LinkedHashSet<>(baselineCount * 4 / 3);
            for (int i = 0; i < baselineCount; i++) {
                baseline.add(readEntry(dis, pool));
            }

            // 辞書用のCSV-likeデータを仮ディレクトリに書き出す代わりに、
            // 直接 HistoricalPostcodeDictionary を構築する
            // → buildFromSnapshot メソッドが必要
            TreeMap<YearMonth, DeltaEntries> deltas = new TreeMap<>();
            for (int i = 0; i < deltaMonthCount; i++) {
                int year = dis.readUnsignedShort();
                int month = dis.readUnsignedByte();
                YearMonth ym = YearMonth.of(year, month);
                int addCount = dis.readUnsignedShort();
                int delCount = dis.readUnsignedShort();
                Set<PostcodeEntry> adds = new LinkedHashSet<>(addCount * 4 / 3);
                for (int j = 0; j < addCount; j++) adds.add(readEntry(dis, pool));
                Set<PostcodeEntry> dels = new LinkedHashSet<>(delCount * 4 / 3);
                for (int j = 0; j < delCount; j++) dels.add(readEntry(dis, pool));
                deltas.put(ym, new DeltaEntries(adds, dels));
            }

            long elapsed = System.currentTimeMillis() - startMs;
            System.out.printf("  Loaded in %d ms (baseline=%,d, deltas=%d months, pool=%,d strings)\n",
                    elapsed, baseline.size(), deltaMonthCount, pool.size());

            return HistoricalPostcodeDictionary.buildFromSnapshot(baselineMonth, baseline, deltas);
        }
    }

    record DeltaEntries(Set<PostcodeEntry> adds, Set<PostcodeEntry> dels) {}

    // ======== Entry I/O ========

    private static void writeEntry(DataOutputStream dos, StringPool pool, PostcodeEntry e) throws IOException {
        dos.writeInt(pool.indexOf(e.postcode()));
        dos.writeInt(pool.indexOf(e.prefecture()));
        dos.writeInt(pool.indexOf(e.municipality()));
        dos.writeInt(pool.indexOf(e.town()));
        dos.writeInt(pool.indexOf(e.lgCode()));
        dos.writeInt(pool.indexOf(e.prefectureKana()));
        dos.writeInt(pool.indexOf(e.municipalityKana()));
        dos.writeInt(pool.indexOf(e.townKana()));
    }

    private static PostcodeEntry readEntry(DataInputStream dis, StringPool pool) throws IOException {
        // write order: postcode, prefecture, municipality, town, lgCode, prefKana, muniKana, townKana
        // PostcodeEntry record order: lgCode, postcode, prefKana, muniKana, townKana, pref, muni, town
        String postcode = pool.get(dis.readInt());
        String prefecture = pool.get(dis.readInt());
        String municipality = pool.get(dis.readInt());
        String town = pool.get(dis.readInt());
        String lgCode = pool.get(dis.readInt());
        String prefectureKana = pool.get(dis.readInt());
        String municipalityKana = pool.get(dis.readInt());
        String townKana = pool.get(dis.readInt());
        return new PostcodeEntry(lgCode, postcode, prefectureKana, municipalityKana, townKana,
                prefecture, municipality, town);
    }

    private static void indexEntry(StringPool pool, PostcodeEntry e) {
        pool.add(e.postcode());
        pool.add(e.prefecture());
        pool.add(e.municipality());
        pool.add(e.town());
        pool.add(e.lgCode());
        pool.add(e.prefectureKana());
        pool.add(e.municipalityKana());
        pool.add(e.townKana());
    }

    // ======== String Pool ========

    static class StringPool {
        private final Map<String, Integer> indexMap = new LinkedHashMap<>();
        private String[] strings;

        void add(String s) { indexMap.putIfAbsent(s, indexMap.size()); }
        void freeze() { strings = indexMap.keySet().toArray(new String[0]); }
        int indexOf(String s) { return indexMap.getOrDefault(s, 0); }
        String get(int idx) { return idx >= 0 && idx < strings.length ? strings[idx] : ""; }
        int size() { return indexMap.size(); }

        void writeTo(DataOutputStream dos) throws IOException {
            dos.writeInt(strings.length);
            for (String s : strings) {
                byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
                dos.writeShort(bytes.length);
                dos.write(bytes);
            }
        }

        static StringPool readFrom(DataInputStream dis) throws IOException {
            int size = dis.readInt();
            StringPool pool = new StringPool();
            pool.strings = new String[size];
            for (int i = 0; i < size; i++) {
                int len = dis.readUnsignedShort();
                byte[] bytes = new byte[len];
                dis.readFully(bytes);
                String s = new String(bytes, StandardCharsets.UTF_8);
                pool.strings[i] = s;
                pool.indexMap.put(s, i);
            }
            return pool;
        }
    }

    // ======== CSV helpers ========

    private static final java.nio.charset.Charset MS932 = java.nio.charset.Charset.forName("MS932");

    private static List<PostcodeEntry> loadAndMergeCsv(Path path) throws IOException {
        List<PostcodeEntry> raw = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(path, MS932)) {
            String line;
            while ((line = reader.readLine()) != null) {
                PostcodeEntry entry = PostcodeEntry.fromCsvLine(line);
                if (entry != null && !entry.postcode().isBlank()) raw.add(entry);
            }
        }
        return PostcodeEntry.mergeContinuationRows(raw);
    }

    private static YearMonth parseYearMonth(String yymm) {
        try {
            int yy = Integer.parseInt(yymm.substring(0, 2));
            int mm = Integer.parseInt(yymm.substring(2, 4));
            return YearMonth.of(yy >= 80 ? 1900 + yy : 2000 + yy, mm);
        } catch (Exception e) { return null; }
    }
}
