package com.example.funeventbackend.repository;

import com.example.funeventbackend.model.RefreshToken;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {
    // 用 tokenHash 找出 RefreshToken
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * 換票專用。⚠️ 悲觀鎖：rotate 裡「讀 used → 判斷 → 寫 used」必須是原子的，
     * 沒有鎖的話兩個併發請求會同時讀到 used = false、各自輪替一次，
     * 竊用偵測形同虛設。作法與 PaymentRepository.findByMerchantTradeNoForUpdate 相同。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT rt FROM RefreshToken rt WHERE rt.tokenHash = :tokenHash")
    Optional<RefreshToken> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    // 取出整條 familyId 內的所有 tokens
    List<RefreshToken> findByFamilyId(UUID id);

    // 這個使用者的所有 token（改密碼時要全部撤銷）
    List<RefreshToken> findByUserId(Long userId);

    /**
     * 清理排程專用：只依 expires_at 刪，<b>不看 used / revoked</b>。
     *
     * <p>⚠️ 絕對不能加 {@code OR used = true}：未過期的已使用票正是竊用偵測的證據，
     * 刪掉之後重放它會變成「查不到」而不是「撤銷整條 family」，而且不會有任何錯誤。
     */
    @Modifying
    @Query("DELETE FROM RefreshToken rt WHERE rt.expiresAt < :cutoff")
    int deleteByExpiresAtBefore(@Param("cutoff") Instant cutoff);
}
