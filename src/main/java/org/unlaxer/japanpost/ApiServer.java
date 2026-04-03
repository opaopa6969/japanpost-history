package org.unlaxer.japanpost;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.staticfiles.Location;

import java.nio.file.Path;
import java.time.YearMonth;
import java.util.*;
import java.util.stream.Collectors;

/**
 * japanpost-history の REST API + 検索 UI サーバー。
 */
public class ApiServer {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private final HistoricalPostcodeDictionary dict;

    public ApiServer(HistoricalPostcodeDictionary dict) {
        this.dict = dict;
    }

    public void start(int port) {
        Javalin app = Javalin.create(config -> {
            config.staticFiles.add("/static", Location.CLASSPATH);
        }).start(port);

        // 辞書メタ情報
        app.get("/api/info", this::handleInfo);

        // 郵便番号 → 住所（時点指定）
        app.get("/api/lookup/{postcode}", this::handleLookup);

        // 郵便番号の住所変遷
        app.get("/api/postcode/{postcode}/history", this::handlePostcodeHistory);

        // 郵便番号の住所変遷（期間抽出）
        app.get("/api/postcode/{postcode}/periods", this::handlePostcodeAddressPeriods);

        // 郵便番号前方一致検索（時点指定）
        app.get("/api/prefix/{prefix}", this::handlePrefix);

        // 住所 → 郵便番号（時点指定）
        app.get("/api/address/lookup", this::handleAddressLookup);

        // 住所の郵便番号変遷
        app.get("/api/address/history", this::handleAddressHistory);

        // 住所の郵便番号変遷（期間抽出）
        app.get("/api/address/periods", this::handleAddressPostcodePeriods);

        // 2時点間の差分
        app.get("/api/diff", this::handleDiff);

        // 統計
        app.get("/api/stats", this::handleStats);

        System.out.printf("API server started on http://localhost:%d\n", port);
    }

    private void handleInfo(Context ctx) {
        ctx.json(Map.of(
                "earliest", dict.earliestMonth().toString(),
                "latest", dict.latestMonth().toString(),
                "snapshots", dict.snapshotCount(),
                "uniquePostcodes", dict.uniquePostcodeCount(),
                "uniqueAddresses", dict.uniqueAddressCount()
        ));
    }

    private void handleLookup(Context ctx) {
        String postcode = ctx.pathParam("postcode");
        YearMonth at = parseYearMonth(ctx.queryParam("at"), dict.latestMonth());

        var entries = dict.lookup(postcode, at);
        ctx.json(Map.of(
                "postcode", postcode,
                "at", at.toString(),
                "count", entries.size(),
                "entries", entries.stream().map(this::entryToMap).toList()
        ));
    }

    private void handlePrefix(Context ctx) {
        String prefix = ctx.pathParam("prefix");
        YearMonth at = parseYearMonth(ctx.queryParam("at"), dict.latestMonth());
        int limit = Integer.parseInt(Objects.requireNonNullElse(ctx.queryParam("limit"), "200"));

        var results = dict.lookupByPrefix(prefix, at, limit);
        ctx.json(Map.of(
                "prefix", prefix,
                "at", at.toString(),
                "count", results.size(),
                "entries", results.stream().map(e -> {
                    var m = new LinkedHashMap<String, Object>();
                    m.put("postcode", e.postcode());
                    m.put("prefecture", e.prefecture());
                    m.put("municipality", e.municipality());
                    m.put("town", e.town());
                    return m;
                }).toList()
        ));
    }

    private void handlePostcodeHistory(Context ctx) {
        String postcode = ctx.pathParam("postcode");
        var history = dict.postcodeHistory(postcode);

        // 変化点だけ抽出（全月を返すと巨大すぎる）
        List<Map<String, Object>> changes = new ArrayList<>();
        String prevKey = null;
        for (var entry : history.entrySet()) {
            String key = entry.getValue().stream()
                    .map(PostcodeEntry::addressKey).sorted()
                    .collect(Collectors.joining(";"));
            if (!key.equals(prevKey)) {
                changes.add(Map.of(
                        "month", entry.getKey().toString(),
                        "entries", entry.getValue().stream().map(this::entryToMap).toList()
                ));
                prevKey = key;
            }
        }
        ctx.json(Map.of(
                "postcode", postcode,
                "changeCount", changes.size(),
                "changes", changes
        ));
    }

    private void handlePostcodeAddressPeriods(Context ctx) {
        String postcode = ctx.pathParam("postcode");
        var periods = dict.postcodeAddressPeriods(postcode);
        ctx.json(Map.of(
                "postcode", postcode,
                "periodCount", periods.size(),
                "periods", periods.stream().map(p -> Map.of(
                        "from", p.from().toString(),
                        "to", p.to() == null ? "present" : p.to().toString(),
                        "addresses", p.entries().stream().map(this::entryToMap).toList()
                )).toList()
        ));
    }

    private void handleAddressLookup(Context ctx) {
        String pref = ctx.queryParam("prefecture");
        String muni = ctx.queryParam("municipality");
        String town = ctx.queryParam("town");
        YearMonth at = parseYearMonth(ctx.queryParam("at"), dict.latestMonth());

        if (pref == null || muni == null || town == null) {
            ctx.status(400).json(Map.of("error", "prefecture, municipality, town are required"));
            return;
        }
        String postcode = dict.lookupByAddress(pref, muni, town, at);
        ctx.json(Map.of(
                "prefecture", pref,
                "municipality", muni,
                "town", town,
                "at", at.toString(),
                "postcode", postcode != null ? postcode : ""
        ));
    }

    private void handleAddressHistory(Context ctx) {
        String pref = ctx.queryParam("prefecture");
        String muni = ctx.queryParam("municipality");
        String town = ctx.queryParam("town");

        if (pref == null || muni == null || town == null) {
            ctx.status(400).json(Map.of("error", "prefecture, municipality, town are required"));
            return;
        }
        var history = dict.addressPostcodeHistory(pref, muni, town);
        // 変化点だけ
        List<Map<String, String>> changes = new ArrayList<>();
        String prev = null;
        for (var entry : history.entrySet()) {
            if (!entry.getValue().equals(prev)) {
                changes.add(Map.of("month", entry.getKey().toString(), "postcode", entry.getValue()));
                prev = entry.getValue();
            }
        }
        ctx.json(Map.of(
                "address", pref + muni + town,
                "changeCount", changes.size(),
                "changes", changes
        ));
    }

    private void handleAddressPostcodePeriods(Context ctx) {
        String pref = ctx.queryParam("prefecture");
        String muni = ctx.queryParam("municipality");
        String town = ctx.queryParam("town");

        if (pref == null || muni == null || town == null) {
            ctx.status(400).json(Map.of("error", "prefecture, municipality, town are required"));
            return;
        }
        var periods = dict.postcodePeriods(pref, muni, town);
        ctx.json(Map.of(
                "address", pref + muni + town,
                "periodCount", periods.size(),
                "periods", periods.stream().map(p -> Map.of(
                        "from", p.from().toString(),
                        "to", p.to() == null ? "present" : p.to().toString(),
                        "postcode", p.postcode()
                )).toList()
        ));
    }

    private void handleDiff(Context ctx) {
        YearMonth from = parseYearMonth(ctx.queryParam("from"), dict.earliestMonth());
        YearMonth to = parseYearMonth(ctx.queryParam("to"), dict.latestMonth());
        int limit = Integer.parseInt(Objects.requireNonNullElse(ctx.queryParam("limit"), "50"));

        var diff = dict.diff(from, to);
        ctx.json(Map.of(
                "from", from.toString(),
                "to", to.toString(),
                "addedCount", diff.addedCount(),
                "removedCount", diff.removedCount(),
                "added", diff.added().entrySet().stream().limit(limit)
                        .map(e -> Map.of("postcode", e.getKey(),
                                "entries", e.getValue().stream().map(this::entryToMap).toList()))
                        .toList(),
                "removed", diff.removed().entrySet().stream().limit(limit)
                        .map(e -> Map.of("postcode", e.getKey(),
                                "entries", e.getValue().stream().map(this::entryToMap).toList()))
                        .toList()
        ));
    }

    private void handleStats(Context ctx) {
        var changedPostcodes = dict.findChangedPostcodes();
        var changedAddresses = dict.findChangedAddresses();
        ctx.json(Map.of(
                "postcodesWithAddressChanges", changedPostcodes.size(),
                "addressesWithPostcodeChanges", changedAddresses.size(),
                "sampleAddressChanges", changedAddresses.stream().limit(20)
                        .map(c -> Map.of(
                                "address", c.prefecture() + c.municipality() + c.town(),
                                "oldPostcode", c.oldPostcode(),
                                "newPostcode", c.newPostcode(),
                                "changedAt", c.changedAt().toString()
                        )).toList()
        ));
    }

    private Map<String, String> entryToMap(PostcodeEntry e) {
        return Map.of(
                "postcode", e.postcode(),
                "prefecture", e.prefecture(),
                "municipality", e.municipality(),
                "town", e.town(),
                "prefectureKana", e.prefectureKana(),
                "municipalityKana", e.municipalityKana(),
                "townKana", e.townKana()
        );
    }

    private YearMonth parseYearMonth(String value, YearMonth defaultValue) {
        if (value == null || value.isBlank()) return defaultValue;
        try {
            return YearMonth.parse(value);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    // CLI entry point
    public static void main(String[] args) throws Exception {
        Path dataDir = Path.of(args.length > 0 ? args[0] : "/tmp/japanpost-history");
        int port = Integer.parseInt(args.length > 1 ? args[1] : "7070");

        System.out.println("Building dictionary...");
        var dict = HistoricalPostcodeDictionary.build(dataDir);
        System.out.printf("Dictionary ready: %s ~ %s (%d snapshots)\n",
                dict.earliestMonth(), dict.latestMonth(), dict.snapshotCount());

        new ApiServer(dict).start(port);
    }
}
