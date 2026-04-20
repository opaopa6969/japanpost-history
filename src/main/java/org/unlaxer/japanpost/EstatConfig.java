package org.unlaxer.japanpost;

/**
 * e-Stat API の設定。
 *
 * <p>appId は環境変数 {@code ESTAT_APP_ID} から優先的に読み込みます。
 * 環境変数が設定されていない場合は、デフォルト値にフォールバックします（後方互換）。</p>
 *
 * <h3>設定方法</h3>
 * <pre>{@code
 * export ESTAT_APP_ID=your_app_id_here
 * }</pre>
 *
 * <p>デフォルト値はプロジェクトオーナーの開発用 appId です。
 * 本番環境や高頻度の利用では、e-Stat (https://www.e-stat.go.jp/) で
 * 個人の appId を取得して環境変数に設定してください。</p>
 */
public final class EstatConfig {

    /** デフォルト appId (後方互換のため維持。本番では環境変数を設定してください) */
    private static final String DEFAULT_APP_ID = "24edfb042993e87548e75f8e26f6f5421646a6fe";

    private EstatConfig() {}

    /**
     * e-Stat API appId を取得する。
     * 環境変数 {@code ESTAT_APP_ID} が設定されている場合はその値を、
     * そうでなければデフォルト値を返す。
     *
     * @return appId (never null, never blank)
     */
    public static String getAppId() {
        String envValue = System.getenv("ESTAT_APP_ID");
        if (envValue != null && !envValue.isBlank()) {
            return envValue;
        }
        return DEFAULT_APP_ID;
    }
}
