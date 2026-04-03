package org.unlaxer.japanpost;

import org.unlaxer.municipality.MunicipalityChange;
import org.unlaxer.municipality.MunicipalityHistory;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 郵便番号の変遷に自治体統廃合の文脈を付与する。
 *
 * <pre>
 * var dict = HistoricalPostcodeDictionary.loadSnapshot(snapshotPath);
 * var muni = MunicipalityHistory.loadBundled();
 * var ctx = new PostcodeWithContext(dict, muni);
 *
 * // 「なぜこの郵便番号の住所名が変わったのか」を取得
 * var result = ctx.explainPostcodeChanges("0613601");
 * // → 0613601: 北海道石狩市厚田区厚田 (2007-10 ~ 2026-04)
 * //           → 北海道石狩市厚田 (2026-04 ~ present)
 * //   関連する統廃合: 2005-10-01 厚田村(01305)、浜益村(01306)が石狩市に編入
 *
 * // 住所変遷 + 統廃合理由をまとめて取得
 * var result = ctx.explainAddressChanges("北海道", "石狩市", "厚田区厚田");
 * </pre>
 */
public class PostcodeWithContext {

    private final HistoricalPostcodeDictionary dict;
    private final MunicipalityHistory muni;

    public PostcodeWithContext(HistoricalPostcodeDictionary dict, MunicipalityHistory muni) {
        this.dict = dict;
        this.muni = muni;
    }

    /**
     * 郵便番号の住所変遷 + 関連する統廃合情報。
     */
    public PostcodeExplanation explainPostcodeChanges(String postcode) {
        var periods = dict.postcodeAddressPeriods(postcode);
        if (periods.isEmpty()) return new PostcodeExplanation(postcode, List.of(), List.of());

        // 変化点の前後で自治体名を比較し、関連する統廃合を検索
        List<MunicipalityChange> relatedChanges = new ArrayList<>();
        for (var period : periods) {
            for (var entry : period.entries()) {
                var nameChanges = muni.findByName(entry.municipality());
                relatedChanges.addAll(nameChanges);
            }
        }
        // 重複除去・時系列ソート
        relatedChanges = relatedChanges.stream()
                .distinct()
                .sorted(Comparator.comparing(c -> c.effectiveDate() != null ? c.effectiveDate() : LocalDate.MIN))
                .collect(Collectors.toList());

        return new PostcodeExplanation(postcode, periods, relatedChanges);
    }

    /**
     * 住所の郵便番号変遷 + 関連する統廃合情報。
     */
    public AddressExplanation explainAddressChanges(String prefecture, String municipality, String town) {
        var periods = dict.postcodePeriods(prefecture, municipality, town);

        List<MunicipalityChange> relatedChanges = muni.findByName(municipality);
        if (relatedChanges.isEmpty() && !town.isEmpty()) {
            relatedChanges = muni.findByName(town);
        }
        relatedChanges = relatedChanges.stream()
                .filter(c -> c.prefecture().equals(prefecture))
                .sorted(Comparator.comparing(c -> c.effectiveDate() != null ? c.effectiveDate() : LocalDate.MIN))
                .collect(Collectors.toList());

        return new AddressExplanation(prefecture + municipality + town, periods, relatedChanges);
    }

    /**
     * 指定期間に住所変更があった郵便番号と、同時期の統廃合を紐づけ。
     */
    public List<PostcodeExplanation> findChangesWithContext(YearMonth from, YearMonth to) {
        var diff = dict.diff(from, to);
        List<PostcodeExplanation> results = new ArrayList<>();

        Set<String> postcodes = new HashSet<>(diff.added().keySet());
        postcodes.addAll(diff.removed().keySet());

        for (String pc : postcodes) {
            var explanation = explainPostcodeChanges(pc);
            if (!explanation.relatedMuniChanges().isEmpty()) {
                results.add(explanation);
            }
        }
        return results;
    }

    public record PostcodeExplanation(
            String postcode,
            List<HistoricalPostcodeDictionary.AddressPeriod> addressPeriods,
            List<MunicipalityChange> relatedMuniChanges
    ) {
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("〒").append(postcode).append(":\n");
            for (var p : addressPeriods) {
                sb.append("  ").append(p).append("\n");
            }
            if (!relatedMuniChanges.isEmpty()) {
                sb.append("  関連する統廃合:\n");
                for (var c : relatedMuniChanges) {
                    sb.append("    ").append(c.effectiveDate())
                            .append(" ").append(c.prefecture()).append(c.fullName())
                            .append(": ").append(c.reason().split("\n")[0]).append("\n");
                }
            }
            return sb.toString();
        }
    }

    public record AddressExplanation(
            String address,
            List<HistoricalPostcodeDictionary.PostcodePeriod> postcodePeriods,
            List<MunicipalityChange> relatedMuniChanges
    ) {
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(address).append(":\n");
            for (var p : postcodePeriods) {
                sb.append("  〒").append(p).append("\n");
            }
            if (!relatedMuniChanges.isEmpty()) {
                sb.append("  関連する統廃合:\n");
                for (var c : relatedMuniChanges) {
                    sb.append("    ").append(c.effectiveDate())
                            .append(" ").append(c.prefecture()).append(c.fullName())
                            .append(": ").append(c.reason().split("\n")[0]).append("\n");
                }
            }
            return sb.toString();
        }
    }
}
