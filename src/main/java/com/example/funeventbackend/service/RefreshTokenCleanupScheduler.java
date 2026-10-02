package com.example.funeventbackend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * 每天清一次已經過期的 refresh token。
 *
 * <p>每次換票都會新增一列，舊的那列要等自己的 7 天期限到了才算死資料，
 * 活躍使用者的一個登入階段一天就能累積上百列，沒有人清就會一直長。
 *
 * <p>⚠️ 只刪「過期超過緩衝期」的列。已使用但未過期的票是竊用偵測的證據，不能動 ——
 * 見 {@code RefreshTokenRepository.deleteByExpiresAtBefore}。
 *
 * <p>多實例不需要分散式鎖：DELETE 本身是冪等的，兩個實例同時跑最多是其中一個刪到 0 列。
 */
@Component
// ⚠️ 測試環境要關掉：排程在測試跑到一半刪資料，會讓斷言看起來隨機失敗
@ConditionalOnProperty(name = "app.refresh-token.cleanup.enabled",
        havingValue = "true", matchIfMissing = true)
@Slf4j
public class RefreshTokenCleanupScheduler {
    private final RefreshTokenService refreshTokenService;
    private final Duration retention;

    public RefreshTokenCleanupScheduler(
            RefreshTokenService refreshTokenService,
            @Value("${app.refresh-token.cleanup.retention:1d}") Duration retention) {
        this.refreshTokenService = refreshTokenService;
        this.retention = retention;
    }

    // 凌晨跑一次。Neon 已經被訂單逾時排程每 10 分鐘戳醒過，多這一次幾乎不增加運算量
    @Scheduled(cron = "${app.refresh-token.cleanup.cron:0 30 3 * * *}", zone = "Asia/Taipei")
    public void purgeExpired() {
        int deleted = refreshTokenService.purgeExpiredBefore(Instant.now().minus(retention));
        if (deleted > 0) {
            log.info("清掉 {} 筆已過期的 refresh token", deleted);
        }
    }
}
