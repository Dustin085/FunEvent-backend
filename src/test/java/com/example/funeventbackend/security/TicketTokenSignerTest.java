package com.example.funeventbackend.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 票券 QR 的簽章。
 *
 * <p>⚠️ 純單元測試，不需要 Spring context —— 這個類別只依賴一個字串。
 *
 * <p>⭐ 這裡守的是整個驗票系統的安全根基：
 * ticketId 是連號、任何人都猜得到，擋住偽造的<b>只有簽章</b>。
 */
class TicketTokenSignerTest {

    private final TicketTokenSigner signer = new TicketTokenSigner("test-secret-for-tickets");

    @Test
    @DisplayName("簽出來的內容驗得回同一個 ticketId")
    void signThenVerifyRoundTrip() {
        String qr = signer.sign(42L);

        assertEquals(Optional.of(42L), signer.verify(qr));
    }

    @Test
    @DisplayName("同一張票每次簽出來都一樣 —— 才能重複顯示 QR")
    void signIsDeterministic() {
        // ⭐ 這是選擇簽章而不是隨機 token 的理由：
        // 「我的票券」頁每次打開都要重畫 QR，結果必須一致
        assertEquals(signer.sign(42L), signer.sign(42L));
    }

    @Test
    @DisplayName("不同票簽出來不一樣")
    void differentTicketsGetDifferentSignatures() {
        assertNotEquals(signer.sign(42L), signer.sign(43L));
    }

    @Test
    @DisplayName("⭐ 改掉 id 就驗不過 —— 猜得到別人的 id 也沒用")
    void tamperedIdIsRejected() {
        String qr = signer.sign(42L);
        String signature = qr.substring(qr.indexOf('.') + 1);

        // 攻擊者知道自己是 42 號，猜隔壁是 43 號 —— 但簽章是 42 的
        assertTrue(signer.verify("43." + signature).isEmpty());
    }

    @Test
    @DisplayName("改掉簽章就驗不過")
    void tamperedSignatureIsRejected() {
        assertTrue(signer.verify("42.completelyMadeUpSignature").isEmpty());
    }

    @Test
    @DisplayName("⭐ 換一把金鑰就全部失效")
    void signatureIsKeyDependent() {
        String qr = signer.sign(42L);
        TicketTokenSigner otherSigner = new TicketTokenSigner("a-different-secret");

        // ⚠️ 這正是「換 secret 會讓所有已發出的 QR 立刻失效」那條警告的來源
        assertTrue(otherSigner.verify(qr).isEmpty());
    }

    @Test
    @DisplayName("格式亂七八糟的輸入一律回 empty，不丟例外")
    void malformedInputIsRejectedWithoutThrowing() {
        // ⚠️ 掃到別人的 QR code（Wi-Fi、網址、名片）是現場的常態，
        // 這些不能讓端點爆掉
        assertTrue(signer.verify(null).isEmpty());
        assertTrue(signer.verify("").isEmpty());
        assertTrue(signer.verify("沒有分隔符").isEmpty());
        assertTrue(signer.verify(".只有簽章").isEmpty());
        assertTrue(signer.verify("42.").isEmpty());
        assertTrue(signer.verify("不是數字.abc").isEmpty());
        assertTrue(signer.verify("https://example.com").isEmpty());
    }
}
