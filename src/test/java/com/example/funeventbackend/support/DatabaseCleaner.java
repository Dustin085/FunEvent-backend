package com.example.funeventbackend.support;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
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
 * <p>⚠️ 使用 H2 專用語法（SET REFERENTIAL_INTEGRITY）。之後測試若改用
 * PostgreSQL，要換成 TRUNCATE ... CASCADE 或 session_replication_role。
 */
@Component
@Profile("test")
@RequiredArgsConstructor
public class DatabaseCleaner {
    private final JdbcTemplate jdbcTemplate;

    public void clean() {
        assertDisposableDatabase();

        // 從 schema 現讀，不維護清單。TABLE_TYPE 過濾掉檢視表
        List<String> tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = 'PUBLIC' AND table_type = 'BASE TABLE'",
                String.class);

        // ⚠️ 這是整個做法的關鍵：外鍵檢查關掉之後，清空的順序就無所謂了
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE");
        try {
            for (String table : tables) {
                jdbcTemplate.execute("TRUNCATE TABLE \"" + table + "\"");
            }
        } finally {
            // ⚠️ 一定要放 finally。沒開回來的話，之後所有測試都不檢查外鍵，
            // 於是「寫入了違反約束的資料」這種 bug 會變成測試抓不到 ——
            // 那比原本的問題嚴重得多
            jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY TRUE");
        }
    }

    /**
     * ⚠️ 真正重要的防呆：確認我們連的是「用完就丟」的記憶體資料庫。
     *
     * <p>「檔案放在測試原始碼裡」擋的是「正式環境呼叫它」，
     * 但真正會出事的情境是**有人拿測試去跑一個真的資料庫**
     *（application-test.yaml 被改錯、環境變數指向 staging、
     * 為了重現 bug 把測試接到共用的開發庫）。那時檔案位置一點用都沒有。
     *
     * <p>這道檢查不管是誰、用什麼 profile、怎麼呼叫 ——
     * 只要目標不是記憶體資料庫就拒絕動手。
     */
    private void assertDisposableDatabase() {
        DataSource dataSource = jdbcTemplate.getDataSource();
        if (dataSource == null) {
            throw new IllegalStateException("沒有 DataSource，拒絕執行");
        }
        String url;
        try (Connection connection = dataSource.getConnection()) {
            url = connection.getMetaData().getURL();
        } catch (SQLException e) {
            throw new IllegalStateException("無法確認資料庫位置，拒絕清空", e);
        }
        if (url == null || !url.startsWith("jdbc:h2:mem:")) {
            // 只印驅動前綴，不印主機與帳密
            throw new IllegalStateException(
                    "DatabaseCleaner 只能用在記憶體資料庫上，但目前連到 "
                            + (url == null
                            ? "(unknown)"
                            : url.replaceAll("^(jdbc:[^:]+:).*", "$1…")));
        }
    }
}
