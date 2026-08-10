package com.example.funeventbackend.service;

import com.example.funeventbackend.model.RefreshToken;
import com.example.funeventbackend.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 撤銷整條 refresh token family。
 * <p>
 * ⚠ 為什麼要獨立成一個 component，而不是寫在 {RefreshTokenService} 裡：
 * <p>
 * 竊用偵測的流程是「撤銷整條 family」→「丟例外拒絕這次請求」，但這兩件事有衝突——
 * Spring 的 {@code @Transactional} 遇到 RuntimeException 會回滾整個交易，
 * 撤銷的寫入會跟著被撤銷掉，等於什麼都沒做。
 * <p>
 * 解法是讓撤銷跑在 {@code REQUIRES_NEW} 的獨立交易裡，先行提交、不受外層回滾影響。
 * 但 {@code @Transactional} 是靠 AOP 代理生效的，<b>同類別內部的方法呼叫不會經過代理</b>
 * （self-invocation），propagation 設定會完全失效。
 * 因此必須拆成獨立的 bean，讓呼叫跨越代理邊界。
 */
@Component
@RequiredArgsConstructor
public class RefreshTokenRevoker {
    private final RefreshTokenRepository refreshTokenRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revokeFamily(UUID familyId) {
        List<RefreshToken> refreshTokenList = refreshTokenRepository.findByFamilyId(familyId);
        // managed entity，dirty check 會在交易提交時自動 UPDATE
        refreshTokenList.forEach(token -> token.setUsed(true));
    }
}
