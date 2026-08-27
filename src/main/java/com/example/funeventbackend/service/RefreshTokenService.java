package com.example.funeventbackend.service;

import com.example.funeventbackend.model.RefreshToken;
import com.example.funeventbackend.model.User;
import com.example.funeventbackend.repository.RefreshTokenRepository;
import com.example.funeventbackend.security.RotatedTokenCache;
import com.example.funeventbackend.security.TokenGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class RefreshTokenService {
    private final RefreshTokenRepository refreshTokenRepository;
    private final TokenGenerator tokenGenerator;
    private final long expiration;
    private final Duration reuseInterval;
    private final RotatedTokenCache rotatedTokenCache;

    public RefreshTokenService(
            RefreshTokenRepository refreshTokenRepository,
            TokenGenerator tokenGenerator,
            @Value("${app.refresh-token.expiration}") long expiration,
            @Value("${app.refresh-token.reuse-interval:30s}") Duration reuseInterval,
            RotatedTokenCache rotatedTokenCache
    ) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.tokenGenerator = tokenGenerator;
        this.expiration = expiration;
        this.reuseInterval = reuseInterval;
        this.rotatedTokenCache = rotatedTokenCache;
    }

    @Transactional
    public String issueNewFamily(User user) {
        // 新建 RefreshToken
        String rawToken = tokenGenerator.generateRawToken();
        String tokenHash = tokenGenerator.hashToken(rawToken);

        RefreshToken refreshToken = RefreshToken.builder()
                .user(user)
                .familyId(UUID.randomUUID())
                .expiresAt(Instant.now().plus(expiration, ChronoUnit.MILLIS))
                .tokenHash(tokenHash)
                .build();
        // 存入資料庫
        refreshTokenRepository.save(refreshToken);
        return rawToken;
    }

    /**
     * ⚠️ 這個方法<b>不丟例外</b>，一律回傳 {@link RotationOutcome}。
     * 理由見那個介面的說明 —— 在這裡丟例外會回滾掉竊用偵測的撤銷。
     */
    @Transactional
    public RotationOutcome rotate(String rawToken) {
        String tokenHash = tokenGenerator.hashToken(rawToken);
        // ⚠️ 悲觀鎖：底下「讀 used → 判斷 → 寫 used」必須是原子的。
        // 沒有鎖的話兩個併發請求會同時讀到 used = false，各自輪替一次
        Optional<RefreshToken> found = refreshTokenRepository.findByTokenHashForUpdate(tokenHash);
        if (found.isEmpty()) {
            return new RotationOutcome.Rejected();
        }
        RefreshToken refreshToken = found.get();

        Instant now = Instant.now();

        // 撤銷過的一律拒絕，而且不再重複撤銷。
        // ⚠️ 這條必須排在寬限期之前 —— 否則「family 已撤銷、但原始 token 還在
        // 寬限期內」會被放行，等於在一條已判定遭竊的 family 上繼續發票
        if (refreshToken.isRevoked()) {
            return new RotationOutcome.Rejected();
        }

        if (refreshToken.isUsed()) {
            // ⭐ 用過了不代表被竊 —— 多分頁、RSC 預抓取、Set-Cookie 沒送達瀏覽器，
            // 都會讓同一張票再送一次。用時間差區分：
            // 瞬間的重送是併發，隔久了才當成攻擊
            if (!isWithinReuseInterval(refreshToken, now)) {
                revokeFamily(refreshToken.getFamilyId());
                return new RotationOutcome.Rejected();
            }

            // ⭐ 寬限期內：把前一個請求換到的那張原封不動回傳，不產生第二張。
            // 這樣雙方拿到同一張票 —— 晚用的那個會落在窗口外、照樣觸發偵測，
            // 而且不會留下一張沒有任何人持有的孤兒票
            Optional<String> alreadyRotated = rotatedTokenCache.get(tokenHash);
            if (alreadyRotated.isPresent()) {
                return new RotationOutcome.Rotated(alreadyRotated.get(), refreshToken.getUser());
            }
            // 快取落空（重啟、換實例、逾時）→ 往下走，照常輪替一次。
            // ⚠️ 絕對不能改成拒絕：快取是最佳化，不是正確性的前提
        }

        // 檢查 token 是否已過期
        if (now.isAfter(refreshToken.getExpiresAt())) {
            return new RotationOutcome.Rejected();
        }

        // ⚠️ usedAt 只在「第一次」被用掉時寫入。寬限期內的重放刻意不更新它 ——
        // 每次重放都往後延的話，只要持續重放窗口就永遠不會關
        if (!refreshToken.isUsed()) {
            refreshToken.setUsed(true);
            refreshToken.setUsedAt(now);
        }

        // 建立新 token
        String newRawToken = tokenGenerator.generateRawToken();
        String newTokenHash = tokenGenerator.hashToken(newRawToken);
        RefreshToken newRefreshtoken = RefreshToken.builder()
                .user(refreshToken.getUser())
                .familyId(refreshToken.getFamilyId())
                // 每次換票重新建立七天有效期
                .expiresAt(now.plus(expiration, ChronoUnit.MILLIS))
                .tokenHash(newTokenHash)
                .build();
        // 只有新 token 需要 save（transient → 要靠它拿到 id）；
        // 舊 token 的 used = true 由髒檢查處理
        refreshTokenRepository.save(newRefreshtoken);

        // ⚠️ 寫在交易裡而不是 afterCommit：afterCommit 會有一段空窗 ——
        // 被鎖擋住的第二個請求可能在它執行之前就讀了快取、落空、多輪替一次。
        // 代價是萬一這之後交易回滾，快取會留下一筆指向不存在 token 的紀錄，
        // 造成一次換票失敗（一個寬限期後自動消失）。這裡之後只剩組回傳值，回滾機率極低
        rotatedTokenCache.put(tokenHash, newRawToken);

        // 回傳結果
        User user = newRefreshtoken.getUser();
        return new RotationOutcome.Rotated(newRawToken, user);
    }

    /**
     * 寬限期（reuse interval）：已使用的 token 在被用掉後的短時間內再次出現，
     * 視為正常的併發重送而不是竊用。
     *
     * <p>⚠️ usedAt 為 null 代表「被標記已使用，但不知道是什麼時候」——
     * 正常流程不會產生這種資料（{@code rotate} 設 used 時一定同時設 usedAt），
     * 只有這個欄位存在之前的舊資料會是這樣。
     * 一律當成窗口外（也就是拒絕）—— 安全的預設是拒絕，不是放行。
     *
     * <p>（登出走的是 revoked，而 revoked 在更前面就攔下來了，走不到這裡。）
     */
    private boolean isWithinReuseInterval(RefreshToken token, Instant now) {
        Instant usedAt = token.getUsedAt();
        return usedAt != null && now.isBefore(usedAt.plus(reuseInterval));
    }

    /**
     * 撤銷整條 family（判定竊用時）。
     *
     * <p>⚠️ 預設的 {@code REQUIRED}，加入呼叫端的交易 —— <b>不能</b>改成
     * {@code REQUIRES_NEW}：{@code rotate} 對這條 family 裡的某一列持有悲觀鎖，
     * 獨立交易去 UPDATE 同一列會等到鎖逾時（{@code CannotAcquireLockException}）。
     *
     * <p>這曾經真的是 {@code REQUIRES_NEW}，因為當時 {@code rotate} 撤銷完會丟例外、
     * 需要獨立提交才不被回滾。現在 {@code rotate} 改成回傳結果，那個需求消失了。
     */
    @Transactional
    public void revokeFamily(UUID familyId) {
        // managed entity，dirty check 會在交易提交時自動 UPDATE
        refreshTokenRepository.findByFamilyId(familyId)
                .forEach(token -> token.setRevoked(true));
    }

    /**
     * 撤銷這個使用者的全部 refresh token（改密碼時用）。
     *
     * <p>⚠️ 同樣是 {@code REQUIRED}：密碼更新若回滾，撤銷也該一起回滾 ——
     * 否則會變成「密碼沒改，但所有裝置都被登出」。
     */
    @Transactional
    public void revokeAllForUser(Long userId) {
        refreshTokenRepository.findByUserId(userId)
                .forEach(token -> token.setRevoked(true));
    }

    // 登出使用
    @Transactional
    public void logout(String rawToken) {
        String tokenHash = tokenGenerator.hashToken(rawToken);
        // 查不到就當作已經登出了，不丟例外 —— 登出應該是等冪的
        refreshTokenRepository.findByTokenHash(tokenHash).ifPresent(token -> {
            List<RefreshToken> family = refreshTokenRepository.findByFamilyId(token.getFamilyId());
            family.forEach(t -> t.setRevoked(true));
        });
    }
}
