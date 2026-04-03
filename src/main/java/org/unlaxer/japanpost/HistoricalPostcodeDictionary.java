package org.unlaxer.japanpost;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 任意時点の郵便番号を引ける時系列辞書。
 *
 * <p>日本郵便の KEN_ALL.CSV（最新フルデータ）と月次差分ファイル（ADD/DEL）から、
 * 2008年1月～現在の各月のスナップショットを復元し、NavigableMap ベースで時点検索を提供する。</p>
 *
 * <h3>使い方</h3>
 * <pre>{@code
 * var dict = HistoricalPostcodeDictionary.build(Path.of("/tmp/japanpost-history"));
 *
 * // 郵便番号 → 2015年4月時点の住所
 * List<PostcodeEntry> entries = dict.lookup("1560051", YearMonth.of(2015, 4));
 *
 * // 住所 → 郵便番号の変遷 (period:postcode マップ)
 * NavigableMap<YearMonth, String> history =
 *     dict.addressPostcodeHistory("北海道", "石狩市", "厚田区厚田");
 * // {2008-01=0613601, ..., 2026-03=0613601}  ← 変更なし
 * // 変更があった場合は途中で値が変わる
 *
 * // 住所 → 指定時点の郵便番号
 * String zip = dict.lookupByAddress("東京都", "世田谷区", "宮坂", YearMonth.of(2010, 6));
 * }</pre>
 *
 * <h3>構築アルゴリズム</h3>
 * <ol>
 *   <li>現在の KEN_ALL.CSV をベースライン（最新月の状態）とする</li>
 *   <li>最新月から逆順に: DEL を足し、ADD を引いて過去の状態を復元</li>
 *   <li>各月のスナップショットを TreeMap に格納</li>
 *   <li>郵便番号タイムライン・住所タイムラインを横断構築</li>
 * </ol>
 */
public class HistoricalPostcodeDictionary {

    private static final Charset MS932 = Charset.forName("MS932");

    /** 月 → その月時点の (postcode → entries) マップ */
    private final TreeMap<YearMonth, Map<String, List<PostcodeEntry>>> snapshots = new TreeMap<>();

    /** postcode → (month → entries) — 全期間の郵便番号タイムライン */
    private final Map<String, TreeMap<YearMonth, List<PostcodeEntry>>> postcodeTimeline = new HashMap<>();

    /** addressKey → (month → postcode) — 全期間の住所→郵便番号タイムライン */
    private final Map<String, TreeMap<YearMonth, String>> addressTimeline = new HashMap<>();

    // ======== ファクトリ ========

    /**
     * ダウンロード済みディレクトリから辞書を構築。
     * @param dataDir KEN_ALL.CSV, ADD_YYMM.CSV, DEL_YYMM.CSV が入ったディレクトリ
     */
    public static HistoricalPostcodeDictionary build(Path dataDir) throws IOException {
        var dict = new HistoricalPostcodeDictionary();
        dict.buildFromDirectory(dataDir);
        return dict;
    }

    /**
     * ダウンロード + 構築をまとめて実行。
     */
    public static HistoricalPostcodeDictionary downloadAndBuild(Path dataDir) throws Exception {
        JapanPostDownloader.downloadAll(dataDir);
        return build(dataDir);
    }

    /**
     * バイナリスナップショットから辞書を構築。
     * DictionarySnapshot.load() から呼ばれる内部メソッド。
     */
    static HistoricalPostcodeDictionary buildFromSnapshot(
            YearMonth baselineMonth,
            Set<PostcodeEntry> baseline,
            TreeMap<YearMonth, DictionarySnapshot.DeltaEntries> deltas
    ) {
        var dict = new HistoricalPostcodeDictionary();
        System.out.println("Restoring snapshots from binary snapshot...");

        // ベースラインを最新月として登録
        dict.snapshots.put(baselineMonth, toPostcodeMap(baseline));

        // 逆順に過去を復元（deltaは newest-first で格納されている）
        Set<PostcodeEntry> state = new HashSet<>(baseline);
        int monthCount = 0;
        for (var entry : deltas.descendingMap().entrySet()) {
            YearMonth month = entry.getKey();
            DictionarySnapshot.DeltaEntries delta = entry.getValue();
            state.removeAll(delta.adds());  // この月のADDを引く
            state.addAll(delta.dels());     // この月のDELを足す
            dict.snapshots.put(month, toPostcodeMap(state));
            monthCount++;
            if (monthCount % 50 == 0) {
                System.out.printf("  restored %d months (current: %s, entries: %,d)\n",
                        monthCount, month, state.size());
            }
        }
        System.out.printf("  Total snapshots: %d (%s ~ %s)\n",
                dict.snapshots.size(),
                dict.snapshots.firstKey(),
                dict.snapshots.lastKey());

        dict.buildTimelines();
        return dict;
    }

    /**
     * バイナリスナップショットファイルから辞書をロード。
     */
    public static HistoricalPostcodeDictionary loadSnapshot(Path snapshotFile) throws IOException {
        return DictionarySnapshot.load(snapshotFile);
    }

    // ======== 検索API ========

    /**
     * 郵便番号 → 指定時点の住所エントリ。
     * floorEntry で「その時点以前で最も近いスナップショット」を使用。
     */
    public List<PostcodeEntry> lookup(String postcode, YearMonth at) {
        var floor = snapshots.floorEntry(at);
        if (floor == null) return List.of();
        List<PostcodeEntry> entries = floor.getValue().get(postcode);
        return entries != null ? List.copyOf(entries) : List.of();
    }

    /**
     * 郵便番号の全変遷。
     * @return month → その月時点での住所リスト
     */
    public NavigableMap<YearMonth, List<PostcodeEntry>> postcodeHistory(String postcode) {
        var timeline = postcodeTimeline.get(postcode);
        return timeline != null ? Collections.unmodifiableNavigableMap(timeline) : Collections.emptyNavigableMap();
    }

    /**
     * 住所 → 郵便番号の変遷 (period:postcode マップ)。
     * @return month → その月時点での郵便番号
     */
    public NavigableMap<YearMonth, String> addressPostcodeHistory(
            String prefecture, String municipality, String town) {
        String key = prefecture + "|" + municipality + "|" + town;
        var timeline = addressTimeline.get(key);
        return timeline != null ? Collections.unmodifiableNavigableMap(timeline) : Collections.emptyNavigableMap();
    }

    /**
     * 住所 → 指定時点の郵便番号。
     */
    public String lookupByAddress(String prefecture, String municipality, String town, YearMonth at) {
        var history = addressPostcodeHistory(prefecture, municipality, town);
        var floor = history.floorEntry(at);
        return floor != null ? floor.getValue() : null;
    }

    /**
     * 郵便番号の変遷を「変化点」だけ抽出。
     * @return 郵便番号が変わった時点のリスト [{from, to, postcode}]
     */
    public List<PostcodePeriod> postcodePeriods(String prefecture, String municipality, String town) {
        var history = addressPostcodeHistory(prefecture, municipality, town);
        if (history.isEmpty()) return List.of();

        List<PostcodePeriod> periods = new ArrayList<>();
        String prevPostcode = null;
        YearMonth periodStart = null;

        for (var entry : history.entrySet()) {
            if (!entry.getValue().equals(prevPostcode)) {
                if (prevPostcode != null) {
                    periods.add(new PostcodePeriod(periodStart, entry.getKey(), prevPostcode));
                }
                prevPostcode = entry.getValue();
                periodStart = entry.getKey();
            }
        }
        if (prevPostcode != null) {
            periods.add(new PostcodePeriod(periodStart, null, prevPostcode)); // null = 現在まで
        }
        return periods;
    }

    /**
     * 郵便番号 → 住所の変遷を「変化点」だけ抽出 (period:address マップ)。
     * 同一郵便番号でも住所名が変わった場合（市町村合併等）を追跡できる。
     * @return 住所が変わった時点のリスト [{from, to, address}]
     */
    public List<AddressPeriod> postcodeAddressPeriods(String postcode) {
        var history = postcodeHistory(postcode);
        if (history.isEmpty()) return List.of();

        List<AddressPeriod> periods = new ArrayList<>();
        String prevKey = null;
        List<PostcodeEntry> prevEntries = null;
        YearMonth periodStart = null;

        for (var entry : history.entrySet()) {
            // 住所リストをキー文字列化して比較
            String key = entry.getValue().stream()
                    .map(PostcodeEntry::addressKey)
                    .sorted()
                    .collect(java.util.stream.Collectors.joining(";"));
            if (!key.equals(prevKey)) {
                if (prevKey != null) {
                    periods.add(new AddressPeriod(periodStart, entry.getKey(), prevEntries));
                }
                prevKey = key;
                prevEntries = entry.getValue();
                periodStart = entry.getKey();
            }
        }
        if (prevKey != null) {
            periods.add(new AddressPeriod(periodStart, null, prevEntries));
        }
        return periods;
    }

    public record AddressPeriod(YearMonth from, YearMonth to, List<PostcodeEntry> entries) {
        @Override
        public String toString() {
            String toStr = to == null ? "present" : to.toString();
            String addr = entries.stream()
                    .map(e -> e.prefecture() + e.municipality() + e.town())
                    .collect(java.util.stream.Collectors.joining(", "));
            return addr + " (" + from + " ~ " + toStr + ")";
        }
    }

    public record PostcodePeriod(YearMonth from, YearMonth to, String postcode) {
        @Override
        public String toString() {
            String toStr = to == null ? "present" : to.toString();
            return postcode + " (" + from + " ~ " + toStr + ")";
        }
    }

    // ======== スナップショット・差分API ========

    /**
     * 指定時点の全郵便番号辞書を取得。
     * @return postcode → entries (その時点の全エントリ)
     */
    public Map<String, List<PostcodeEntry>> snapshotAt(YearMonth at) {
        var floor = snapshots.floorEntry(at);
        return floor != null ? Collections.unmodifiableMap(floor.getValue()) : Map.of();
    }

    /**
     * 2時点間の差分を取得。
     * @return added: fromにはなくtoにある, removed: fromにはあるがtoにはない
     */
    public Diff diff(YearMonth from, YearMonth to) {
        var fromSnapshot = snapshotAt(from);
        var toSnapshot = snapshotAt(to);

        Map<String, List<PostcodeEntry>> added = new HashMap<>();
        Map<String, List<PostcodeEntry>> removed = new HashMap<>();

        // toにあってfromにないもの = added
        Set<String> allPostcodes = new HashSet<>(fromSnapshot.keySet());
        allPostcodes.addAll(toSnapshot.keySet());
        for (String pc : allPostcodes) {
            var fromEntries = fromSnapshot.getOrDefault(pc, List.of());
            var toEntries = toSnapshot.getOrDefault(pc, List.of());
            var fromKeys = fromEntries.stream().map(PostcodeEntry::addressKey).collect(Collectors.toSet());
            var toKeys = toEntries.stream().map(PostcodeEntry::addressKey).collect(Collectors.toSet());
            if (!fromKeys.equals(toKeys)) {
                var addedEntries = toEntries.stream().filter(e -> !fromKeys.contains(e.addressKey())).toList();
                var removedEntries = fromEntries.stream().filter(e -> !toKeys.contains(e.addressKey())).toList();
                if (!addedEntries.isEmpty()) added.put(pc, addedEntries);
                if (!removedEntries.isEmpty()) removed.put(pc, removedEntries);
            }
        }
        return new Diff(from, to, added, removed);
    }

    public record Diff(
            YearMonth from, YearMonth to,
            Map<String, List<PostcodeEntry>> added,
            Map<String, List<PostcodeEntry>> removed
    ) {
        public int addedCount() { return added.values().stream().mapToInt(List::size).sum(); }
        public int removedCount() { return removed.values().stream().mapToInt(List::size).sum(); }
    }

    // ======== 変更検出API ========

    /**
     * 郵便番号が変わった住所の一覧。
     * (同一住所で期間中に郵便番号が変化したもの)
     */
    public List<AddressChange> findChangedAddresses() {
        List<AddressChange> changes = new ArrayList<>();
        for (var entry : addressTimeline.entrySet()) {
            TreeMap<YearMonth, String> timeline = entry.getValue();
            String prev = null;
            for (var monthEntry : timeline.entrySet()) {
                if (prev != null && !prev.equals(monthEntry.getValue())) {
                    String[] parts = entry.getKey().split("\\|", 3);
                    changes.add(new AddressChange(
                            parts.length > 0 ? parts[0] : "",
                            parts.length > 1 ? parts[1] : "",
                            parts.length > 2 ? parts[2] : "",
                            prev, monthEntry.getValue(), monthEntry.getKey()));
                }
                prev = monthEntry.getValue();
            }
        }
        return changes;
    }

    /**
     * 住所が変わった郵便番号の一覧。
     * (同一郵便番号で期間中に住所名が変化したもの — 市町村合併等)
     */
    public List<PostcodeChange> findChangedPostcodes() {
        List<PostcodeChange> changes = new ArrayList<>();
        for (var entry : postcodeTimeline.entrySet()) {
            var periods = postcodeAddressPeriods(entry.getKey());
            if (periods.size() > 1) {
                changes.add(new PostcodeChange(entry.getKey(), periods));
            }
        }
        return changes;
    }

    public record AddressChange(
            String prefecture, String municipality, String town,
            String oldPostcode, String newPostcode, YearMonth changedAt
    ) {
        @Override
        public String toString() {
            return prefecture + municipality + town + ": " + oldPostcode + " -> " + newPostcode + " (" + changedAt + ")";
        }
    }

    public record PostcodeChange(String postcode, List<AddressPeriod> periods) {
        @Override
        public String toString() {
            return postcode + ": " + periods.size() + " periods";
        }
    }

    /** 最古のスナップショット月 */
    public YearMonth earliestMonth() { return snapshots.isEmpty() ? null : snapshots.firstKey(); }
    /** 最新のスナップショット月 */
    public YearMonth latestMonth() { return snapshots.isEmpty() ? null : snapshots.lastKey(); }
    /** スナップショット数 */
    public int snapshotCount() { return snapshots.size(); }
    /** ユニーク郵便番号数（全期間） */
    public int uniquePostcodeCount() { return postcodeTimeline.size(); }
    /** ユニーク住所数（全期間） */
    public int uniqueAddressCount() { return addressTimeline.size(); }

    // ======== 構築ロジック ========

    private void buildFromDirectory(Path dataDir) throws IOException {
        System.out.println("Building historical postcode dictionary from: " + dataDir);

        // KEN_ALL をベースラインとしてロード
        Path kenAllPath = dataDir.resolve("KEN_ALL.CSV");
        if (!Files.exists(kenAllPath)) {
            throw new FileNotFoundException("KEN_ALL.CSV not found in " + dataDir);
        }
        Set<PostcodeEntry> baseline = loadCsv(kenAllPath);
        System.out.printf("  KEN_ALL loaded: %,d entries\n", baseline.size());

        // ADD/DELファイルを列挙
        TreeMap<YearMonth, Path> addFiles = new TreeMap<>();
        TreeMap<YearMonth, Path> delFiles = new TreeMap<>();
        try (var stream = Files.list(dataDir)) {
            stream.forEach(p -> {
                String name = p.getFileName().toString().toUpperCase();
                if (name.matches("ADD_\\d{4}\\.CSV")) {
                    YearMonth ym = parseYearMonth(name.substring(4, 8));
                    if (ym != null) addFiles.put(ym, p);
                } else if (name.matches("DEL_\\d{4}\\.CSV")) {
                    YearMonth ym = parseYearMonth(name.substring(4, 8));
                    if (ym != null) delFiles.put(ym, p);
                }
            });
        }
        System.out.printf("  ADD files: %d, DEL files: %d\n", addFiles.size(), delFiles.size());

        // 最新月: ADD/DELの最新月の翌月（変更適用後）
        YearMonth latestMonth = addFiles.isEmpty() ? YearMonth.now()
                : addFiles.lastKey().plusMonths(1);

        // ベースラインを最新月として登録
        snapshots.put(latestMonth, toPostcodeMap(baseline));

        // 逆順に過去を復元
        // 月M の ADD = M月に追加されたエントリ → M-1月にはなかった → 引く
        // 月M の DEL = M月に削除されたエントリ → M-1月にはあった → 足す
        NavigableSet<YearMonth> allMonths = new TreeSet<>();
        allMonths.addAll(addFiles.keySet());
        allMonths.addAll(delFiles.keySet());

        Set<PostcodeEntry> state = new HashSet<>(baseline);
        int monthCount = 0;
        for (YearMonth month : allMonths.descendingSet()) {
            Path addPath = addFiles.get(month);
            if (addPath != null) {
                state.removeAll(loadCsv(addPath));
            }
            Path delPath = delFiles.get(month);
            if (delPath != null) {
                state.addAll(loadCsv(delPath));
            }
            snapshots.put(month, toPostcodeMap(state));
            monthCount++;
            if (monthCount % 50 == 0) {
                System.out.printf("  restored %d months (current: %s, entries: %,d)\n",
                        monthCount, month, state.size());
            }
        }
        System.out.printf("  Total snapshots: %d (%s ~ %s)\n",
                snapshots.size(), snapshots.firstKey(), snapshots.lastKey());

        // タイムライン構築
        buildTimelines();
    }

    private void buildTimelines() {
        System.out.println("Building timelines...");
        for (var snapshotEntry : snapshots.entrySet()) {
            YearMonth month = snapshotEntry.getKey();
            for (var pcEntry : snapshotEntry.getValue().entrySet()) {
                String postcode = pcEntry.getKey();
                postcodeTimeline
                        .computeIfAbsent(postcode, k -> new TreeMap<>())
                        .put(month, pcEntry.getValue());

                for (PostcodeEntry pe : pcEntry.getValue()) {
                    addressTimeline
                            .computeIfAbsent(pe.addressKey(), k -> new TreeMap<>())
                            .put(month, postcode);
                }
            }
        }
        System.out.printf("  Postcode timeline: %,d unique postcodes\n", postcodeTimeline.size());
        System.out.printf("  Address timeline: %,d unique addresses\n", addressTimeline.size());
    }

    // ======== CSV I/O ========

    private static Set<PostcodeEntry> loadCsv(Path path) throws IOException {
        List<PostcodeEntry> rawEntries = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(path, MS932)) {
            String line;
            while ((line = reader.readLine()) != null) {
                PostcodeEntry entry = PostcodeEntry.fromCsvLine(line);
                if (entry != null && !entry.postcode().isBlank()) {
                    rawEntries.add(entry);
                }
            }
        }
        // 複数行分割（町域名の括弧が閉じるまで続く行）をマージ
        List<PostcodeEntry> merged = PostcodeEntry.mergeContinuationRows(rawEntries);
        Set<PostcodeEntry> entries = new LinkedHashSet<>(merged);
        return entries;
    }

    private static Map<String, List<PostcodeEntry>> toPostcodeMap(Set<PostcodeEntry> entries) {
        return entries.stream().collect(Collectors.groupingBy(
                PostcodeEntry::postcode, HashMap::new, Collectors.toList()));
    }

    private static YearMonth parseYearMonth(String yymm) {
        try {
            int yy = Integer.parseInt(yymm.substring(0, 2));
            int mm = Integer.parseInt(yymm.substring(2, 4));
            int year = yy >= 80 ? 1900 + yy : 2000 + yy;
            return YearMonth.of(year, mm);
        } catch (Exception e) {
            return null;
        }
    }

    // ======== CLI ========

    public static void main(String[] args) throws Exception {
        String command = args.length > 0 ? args[0] : "help";
        Path dataDir = Path.of(args.length > 1 ? args[1] : "/tmp/japanpost-history");

        switch (command) {
            case "download" -> {
                JapanPostDownloader.downloadAll(dataDir);
            }
            case "build" -> {
                var dict = build(dataDir);
                demo(dict);
            }
            case "run" -> {
                var dict = downloadAndBuild(dataDir);
                demo(dict);
            }
            case "snapshot-write" -> {
                Path output = Path.of(args.length > 2 ? args[2] : dataDir.resolve("japanpost-history.snapshot").toString());
                DictionarySnapshot.write(dataDir, output);
            }
            case "snapshot-load" -> {
                Path input = Path.of(args.length > 1 ? args[1] : "/tmp/japanpost-history/japanpost-history.snapshot");
                var dict = loadSnapshot(input);
                demo(dict);
            }
            default -> {
                System.out.println("japanpost-history: Historical Japanese postal code dictionary");
                System.out.println();
                System.out.println("Commands:");
                System.out.println("  download [dir]      Download KEN_ALL + all ADD/DEL files");
                System.out.println("  build [dir]         Build dictionary from CSV files");
                System.out.println("  run [dir]           Download + build + demo");
                System.out.println("  snapshot-write [dir] [output]  Write binary snapshot");
                System.out.println("  snapshot-load [file] Load from binary snapshot + demo");
                System.out.println();
                System.out.println("Default dir: /tmp/japanpost-history");
            }
        }
    }

    private static void demo(HistoricalPostcodeDictionary dict) {
        System.out.printf("\nDictionary ready: %s ~ %s (%d snapshots)\n",
                dict.earliestMonth(), dict.latestMonth(), dict.snapshotCount());

        // 郵便番号の時点検索
        System.out.println("\n=== Postcode lookup at different dates ===");
        String[] postcodes = {"0613601", "9218046"};
        YearMonth[] dates = {
                YearMonth.of(2008, 1), YearMonth.of(2012, 1),
                YearMonth.of(2018, 1), YearMonth.of(2026, 1)
        };
        for (String pc : postcodes) {
            System.out.printf("\n  %s:\n", pc);
            for (YearMonth d : dates) {
                var entries = dict.lookup(pc, d);
                if (entries.isEmpty()) {
                    System.out.printf("    %s: (not found)\n", d);
                } else {
                    var e = entries.get(0);
                    System.out.printf("    %s: %s%s%s\n", d, e.prefecture(), e.municipality(), e.town());
                }
            }
        }

        // 住所の郵便番号変遷 (period:postcode)
        System.out.println("\n=== Address postcode periods ===");
        String[][] addresses = {
                {"北海道", "石狩市", "厚田区厚田"},
                {"北海道", "石狩市", "厚田"},
        };
        for (String[] addr : addresses) {
            var periods = dict.postcodePeriods(addr[0], addr[1], addr[2]);
            if (periods.isEmpty()) continue;
            System.out.printf("\n  %s%s%s:\n", addr[0], addr[1], addr[2]);
            for (var p : periods) {
                System.out.printf("    %s\n", p);
            }
        }

        // 郵便番号の住所変遷 (period:address)
        System.out.println("\n=== Postcode address periods (postcode -> period:address) ===");
        String[] interestingPostcodes = {"0613601", "0613151"};
        for (String pc : interestingPostcodes) {
            var periods = dict.postcodeAddressPeriods(pc);
            if (periods.isEmpty()) continue;
            System.out.printf("\n  %s:\n", pc);
            for (var p : periods) {
                System.out.printf("    %s\n", p);
            }
        }

        // 2時点間の差分
        System.out.println("\n=== Diff: 2026-03 vs 2026-04 ===");
        var d = dict.diff(YearMonth.of(2026, 3), YearMonth.of(2026, 4));
        System.out.printf("  Added: %d entries, Removed: %d entries\n", d.addedCount(), d.removedCount());
        d.added().entrySet().stream().limit(5).forEach(e ->
                System.out.printf("  + %s: %s\n", e.getKey(), e.getValue().get(0).prefecture() + e.getValue().get(0).municipality() + e.getValue().get(0).town()));
        d.removed().entrySet().stream().limit(5).forEach(e ->
                System.out.printf("  - %s: %s\n", e.getKey(), e.getValue().get(0).prefecture() + e.getValue().get(0).municipality() + e.getValue().get(0).town()));

        // 住所が変わった郵便番号の数
        System.out.println("\n=== Statistics ===");
        var changedPostcodes = dict.findChangedPostcodes();
        var changedAddresses = dict.findChangedAddresses();
        System.out.printf("  Postcodes with address changes: %,d\n", changedPostcodes.size());
        System.out.printf("  Addresses with postcode changes: %,d\n", changedAddresses.size());
        changedPostcodes.stream().limit(5).forEach(c ->
                System.out.printf("    %s\n", c));
        changedAddresses.stream().limit(5).forEach(c ->
                System.out.printf("    %s\n", c));
    }
}
