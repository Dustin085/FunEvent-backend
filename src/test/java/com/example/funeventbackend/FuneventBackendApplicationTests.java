package com.example.funeventbackend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Initializr 產的空殼測試，只確認 Spring context 組得起來。
 *
 * <p>⚠️ {@code @ActiveProfiles("test")} 不是可有可無的。少了它，這個測試套的是
 * 正式設定 —— 也就是**連上開發者本機正在用的那個資料庫**，並且在上面跑 Flyway。
 * 資料庫還是 H2 記憶體模式時完全看不出來（它自己開一個新的就沒事），
 * 換成 PostgreSQL 之後才現形。
 */
@SpringBootTest
@ActiveProfiles("test")
class FuneventBackendApplicationTests {

    @Test
    void contextLoads() {
    }

}
