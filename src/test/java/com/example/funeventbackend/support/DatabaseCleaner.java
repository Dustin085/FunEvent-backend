package com.example.funeventbackend.support;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 測試之間清空資料庫。
 *
 * <p>放在測試原始碼裡，所以只會出現在測試的 classpath 上，不會進正式建置 ——
 * 正式的 jar 裡根本沒有這個類別。
 *
 * <p>⚠️ 刻意不維護「刪除順序」：這個專案已經四次因為新增一張表卻忘了
 * 把它插進正確位置而爆 FK 違反（orders、payments、event_images、
 * user_oauth_accounts）。把順序集中成一份只是少改幾個地方，
 * 加表時仍然要判斷它該放哪。改成關掉外鍵檢查之後，順序就不存在了 ——
 * 加新表完全不用動這個檔案。
 *
 * <p>PostgreSQL 版（2026-08-28 從 H2 的 SET REFERENTIAL_INTEGRITY 改過來）。
 */
@Component
@Profile("test")
public class DatabaseCleaner {
    private final JdbcTemplate jdbcTemplate;
    private final String configuredUrl;

    // ⚠️ 不能用 @RequiredArgsConstructor：Lombok 不會把欄位上的 @Value
    // 複製到自動生成的建構子參數上，configuredUrl 會是 null
    public DatabaseCleaner(JdbcTemplate jdbcTemplate,
                           @Value("${spring.datasource.url}") String configuredUrl) {
        this.jdbcTemplate = jdbcTemplate;
        this.configuredUrl = configuredUrl;
    }

    /**
     * Flyway 的版本紀錄表。⚠️ 絕對不能清掉 —— 清了之後，同一個容器上啟動的
     * 下一個 Spring context 會看到「有表但沒有版本紀錄」，Flyway 直接拒絕啟動
     * （Found non-empty schema but no schema history table）。
     * 這個專案至少有兩個 context（帶 generate_statistics 的那兩個測試自成一組）。
     */
    private static final String FLYWAY_HISTORY_TABLE = "flyway_schema_history";

    /** Testcontainers 的 URL scheme，見 {@link #assertDisposableDatabase()}。 */
    private static final String TESTCONTAINERS_URL_PREFIX = "jdbc:tc:";

    public void clean() {
        assertDisposableDatabase();

        // 從 schema 現讀，不維護清單。TABLE_TYPE 過濾掉檢視表。
        // ⚠️ PostgreSQL 的 schema 名是小寫 'public'（H2 是大寫 'PUBLIC'）
        List<String> tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_type = 'BASE TABLE' "
                        + "AND table_name <> ?",
                String.class, FLYWAY_HISTORY_TABLE);

        if (tables.isEmpty()) {
            return;
        }

        // ⚠️ 一次 TRUNCATE 全部，靠 CASCADE 免掉刪除順序 ——
        // 跟 H2 版關掉外鍵檢查是同一個念頭：讓「順序」這個概念根本不存在，
        // 加新表完全不用動這個檔案。
        //
        // ⚠️ 但保證的方式不一樣，這點值得知道：H2 版是「真的不檢查外鍵」，
        // PostgreSQL 的 CASCADE 是「把被參照的表一起納入同一個 TRUNCATE」——
        // 外鍵檢查全程有效。所以這裡沒有 H2 版那個「忘了開回來」的風險，
        // 也就不需要 finally。
        //
        // 刻意不加 RESTART IDENTITY：H2 版的 TRUNCATE 不會重設序列，
        // 維持一樣的行為，避免測試在「id 從 1 開始」上長出隱性依賴。
        String tableList = String.join(", ", tables);
        jdbcTemplate.execute("TRUNCATE TABLE " + tableList + " CASCADE");
    }

    /**
     * ⚠️ 真正重要的防呆：確認我們清的是「用完就丟」的容器，不是一台真的資料庫。
     *
     * <p>「檔案放在測試原始碼裡」擋的是「正式環境呼叫它」，
     * 但真正會出事的情境是**有人拿測試去跑一個真的資料庫**
     *（application-test.yaml 被改錯、環境變數指向 staging、
     * 為了重現 bug 把測試接到共用的開發庫）。那時檔案位置一點用都沒有。
     *
     * <p>⚠️ H2 時代這裡讀的是 {@code connection.getMetaData().getURL()}，
     * 靠 {@code jdbc:h2:mem:} 前綴認出記憶體資料庫。改用 Testcontainers 之後
     * 那招失效了 —— 實測過，走 {@code jdbc:tc:} 時那個方法回的是**底層委派的
     * URL**（{@code jdbc:postgresql://localhost:56500/funevent}），跟一台真的
     * PostgreSQL 長得一模一樣，分不出來。
     *
     * <p>所以改成檢查**設定值**。這反而更強：{@code jdbc:tc:} 這個 scheme
     * 在構造上就只能連到 Testcontainers 當場開的容器，不可能指到一台真的
     * 資料庫；而上面列的三個威脅情境全都會改動這個設定值，照樣擋得下來。
     */
    private void assertDisposableDatabase() {
        if (configuredUrl == null || !configuredUrl.startsWith(TESTCONTAINERS_URL_PREFIX)) {
            // 只印驅動前綴，不印主機與帳密
            throw new IllegalStateException(
                    "DatabaseCleaner 只能用在 Testcontainers 起的容器上（"
                            + TESTCONTAINERS_URL_PREFIX + "…），但目前設定的是 "
                            + (configuredUrl == null
                            ? "(unknown)"
                            : configuredUrl.replaceAll("^(jdbc:[^:]+:).*", "$1…")));
        }
    }
}
