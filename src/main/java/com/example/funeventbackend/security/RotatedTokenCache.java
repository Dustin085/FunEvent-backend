package com.example.funeventbackend.security;

import java.util.Optional;

/**
 * 記住「某張 refresh token 換到的新 token 原文」，供寬限期內的重放取用。
 *
 * <p>⚠️ 抽成介面是為了之後換 Redis —— 目前單一 JVM，記憶體實作就夠；
 * 真的要多實例時新增一個 Redis 實作即可，{@code RefreshTokenService} 不用動。
 *
 * <p>⭐ key 是「舊 token 的 hash」而不是原文：hash 本來就存在 DB 裡、不是秘密，
 * 拿它當 key 就不必讓快取多保有一份舊 token 的原文。value 才是秘密。
 *
 * <p>⚠️ 這是最佳化，不是正確性的前提。查不到時呼叫端<b>必須</b>退回輪替，
 * 不能拒絕 —— 快取會因為重啟、換實例、逾時而落空，那些都不該讓使用者被登出。
 */
public interface RotatedTokenCache {

    void put(String oldTokenHash, String newRawToken);

    Optional<String> get(String oldTokenHash);
}
