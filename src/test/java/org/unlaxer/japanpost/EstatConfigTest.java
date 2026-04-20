package org.unlaxer.japanpost;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EstatConfig の env var fallback テスト。
 */
class EstatConfigTest {

    @Test
    void getAppId_returnsNonBlank() {
        String appId = EstatConfig.getAppId();
        assertNotNull(appId);
        assertFalse(appId.isBlank(), "appId must not be blank");
    }

    @Test
    void getAppId_defaultFallbackIsKnownValue() {
        // ESTAT_APP_ID が未設定の場合、デフォルト値が返る (後方互換保証)
        // CI 環境で ESTAT_APP_ID が設定されていなければデフォルト値のはず
        String envValue = System.getenv("ESTAT_APP_ID");
        String appId = EstatConfig.getAppId();
        if (envValue == null || envValue.isBlank()) {
            assertEquals("24edfb042993e87548e75f8e26f6f5421646a6fe", appId,
                    "Default appId should match the backward-compatible value");
        } else {
            assertEquals(envValue, appId,
                    "When ESTAT_APP_ID env var is set, it should take precedence");
        }
    }
}
