package org.unlaxer.japanpost;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.YearMonth;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 日本郵便サーバーから KEN_ALL + ADD/DEL 月次更新ファイルをダウンロード。
 *
 * 利用可能な期間: 2007年10月 (add_0710) ～ 現在
 */
public class JapanPostDownloader {

    private static final String BASE_URL = "https://www.post.japanpost.jp/zipcode/dl/kogaki/zip/";

    public static void downloadAll(Path outputDir) throws Exception {
        downloadAll(outputDir, YearMonth.of(2007, 10), YearMonth.now());
    }

    public static void downloadAll(Path outputDir, YearMonth from, YearMonth to) throws Exception {
        Files.createDirectories(outputDir);
        HttpClient client = HttpClient.newHttpClient();

        // KEN_ALL (最新のフルデータ)
        System.out.println("Downloading KEN_ALL.ZIP...");
        downloadAndExtract(client, BASE_URL + "ken_all.zip", outputDir);

        // ADD/DEL月次ファイル
        int downloaded = 0;
        int skipped = 0;
        YearMonth current = from;
        while (!current.isAfter(to)) {
            String yymm = String.format("%02d%02d", current.getYear() % 100, current.getMonthValue());

            // 既存ファイルはスキップ
            String addName = "ADD_" + yymm + ".CSV";
            String delName = "DEL_" + yymm + ".CSV";
            if (Files.exists(outputDir.resolve(addName)) && Files.exists(outputDir.resolve(delName))) {
                skipped++;
                current = current.plusMonths(1);
                continue;
            }

            boolean addOk = downloadAndExtract(client, BASE_URL + "add_" + yymm + ".zip", outputDir);
            boolean delOk = downloadAndExtract(client, BASE_URL + "del_" + yymm + ".zip", outputDir);
            if (addOk || delOk) {
                downloaded++;
                if (downloaded % 20 == 0) {
                    System.out.printf("  downloaded %d months (current: %s)\n", downloaded, current);
                }
            }
            current = current.plusMonths(1);
        }
        System.out.printf("Downloaded %d monthly pairs (skipped %d existing)\n", downloaded, skipped);
    }

    private static boolean downloadAndExtract(HttpClient client, String url, Path outputDir) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) return false;

        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(response.body()))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = entry.getName().toUpperCase();
                if (name.endsWith(".CSV")) {
                    // Zip Slip 防御: 展開先が outputDir の外に出ないことを確認
                    Path target = outputDir.resolve(name).normalize();
                    if (!target.startsWith(outputDir.toAbsolutePath().normalize())) {
                        throw new SecurityException(
                                "Zip Slip detected: entry '" + entry.getName() +
                                "' would extract outside target directory");
                    }
                    Files.copy(zis, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        return true;
    }
}
