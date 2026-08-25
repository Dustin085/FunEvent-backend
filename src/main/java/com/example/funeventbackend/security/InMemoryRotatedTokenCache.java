package com.example.funeventbackend.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JVM 記憶體版本的 {@link RotatedTokenCache}。
 *
 * <p>⚠️ raw token 會在 heap 裡停留一個寬限期的時間。這是做不掉的 ——
 * 要把同一張票回傳給第二個請求，就一定得有地方保有它的原文。
 * 能做的是縮短時間（一個寬限期）與縮小範圍（不落地、不進 DB）。
 *
 * <p>⚠️ 部署後每次重啟與每次部署都會清空，多實例時各自獨立。
 * 這都沒關係 —— 呼叫端查不到就會退回輪替。
 */
@Component
public class InMemoryRotatedTokenCache implements RotatedTokenCache {

    private record Entry(String rawToken, Instant expiresAt) {
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Duration ttl;

    public InMemoryRotatedTokenCache(
            @Value("${app.refresh-token.reuse-interval:30s}") Duration ttl) {
        this.ttl = ttl;
    }

    @Override
    public void put(String oldTokenHash, String newRawToken) {
        Instant now = Instant.now();
        // 順手清掉過期的。條目只活一個寬限期，所以任一時刻的量級是
        // 「這段時間內換過票的次數」，全掃一遍的成本可以忽略。
        // ⚠️ 這行不能取代下面的排程 —— 它只在「有人換票」時才會執行
        entries.entrySet().removeIf(e -> now.isAfter(e.getValue().expiresAt()));
        entries.put(oldTokenHash, new Entry(newRawToken, now.plus(ttl)));
    }

    @Override
    public Optional<String> get(String oldTokenHash) {
        Entry entry = entries.get(oldTokenHash);
        if (entry == null) {
            return Optional.empty();
        }
        // ⚠️ 即使還沒被清掉也要自己判斷一次逾時 ——
        // 清理是「順手」做的，不保證及時
        if (Instant.now().isAfter(entry.expiresAt())) {
            entries.remove(oldTokenHash);
            return Optional.empty();
        }
        return Optional.of(entry.rawToken());
    }

    /**
     * ⚠️ 不能只靠 {@code put} 時的順手清理 —— 那在「流量停下來」之後就不會再執行，
     * 最後一批 raw token 會留在記憶體裡直到下次有人換票。
     * 這個排程讓「只停留一個寬限期」這件事在閒置時也成立。
     *
     * <p>（@EnableScheduling 在 FuneventBackendApplication 上，已為 OrderExpiryScheduler 開啟。）
     */
    @Scheduled(fixedDelayString = "${app.refresh-token.reuse-interval:30s}")
    void evictExpired() {
        Instant now = Instant.now();
        entries.entrySet().removeIf(e -> now.isAfter(e.getValue().expiresAt()));
    }
}
