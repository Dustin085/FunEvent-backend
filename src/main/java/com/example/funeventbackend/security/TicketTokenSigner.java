package com.example.funeventbackend.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Optional;

/**
 * 票券 QR 的簽章與驗證。內容格式是 {@code {ticketId}.{簽章}}。
 *
 * <p>⭐ 為什麼用簽章而不是「隨機 token 存雜湊」：「我的票券」頁每次打開都要
 * 重新畫出 QR，只存雜湊的話伺服器算不回原文。而存明文則是資料庫外洩後
 * 所有票都能被偽造。簽章可以隨時從 id 重算，且沒有 secret 就偽造不出來。
 *
 * <p>⚠️ ticketId 是連號、猜得到 —— <b>那沒關係</b>。安全性完全來自簽章：
 * 知道別人的票是 124 號，也產生不出 124 的合法簽章。
 *
 * <p>⚠️ secret <b>不能</b>跟 {@code app.access-token.secret} 共用。
 * 不同用途的簽章共用金鑰時，其中一邊的漏洞會擴散到另一邊。
 *
 * <p>⚠️ 換掉 secret 會讓<b>所有已經發出去的 QR 立刻失效</b>。
 * 這是簽章方案的代價，換 secret 前要先確認沒有進行中的活動。
 */
@Component
public class TicketTokenSigner {
    private static final String ALGORITHM = "HmacSHA256";
    private static final char SEPARATOR = '.';

    private final SecretKeySpec key;

    public TicketTokenSigner(@Value("${app.ticket.secret}") String secret) {
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
    }

    /** 產生 QR 的內容。可以隨時對同一張票重複呼叫，結果一樣 */
    public String sign(Long ticketId) {
        return ticketId + String.valueOf(SEPARATOR) + signature(ticketId.toString());
    }

    /**
     * 驗證 QR 的內容，通過就回傳 ticketId。
     *
     * <p>⚠️ 任何格式問題（null、沒有分隔符、id 不是數字、簽章不符）
     * 一律回 empty，<b>不丟例外也不區分原因</b> ——
     * 區分了等於告訴攻擊者他猜到了哪一步。
     */
    public Optional<Long> verify(String qrContent) {
        if (qrContent == null) {
            return Optional.empty();
        }
        int separatorIndex = qrContent.indexOf(SEPARATOR);
        if (separatorIndex <= 0 || separatorIndex == qrContent.length() - 1) {
            return Optional.empty();
        }

        String rawId = qrContent.substring(0, separatorIndex);
        String presented = qrContent.substring(separatorIndex + 1);

        long ticketId;
        try {
            ticketId = Long.parseLong(rawId);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }

        // ⚠️ 一定要用 MessageDigest.isEqual 而不是 String.equals ——
        // 前者是常數時間比對。equals 一遇到不同的字元就返回，
        // 攻擊者可以靠回應時間一個字元一個字元地把簽章試出來
        boolean matches = MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8),
                signature(rawId).getBytes(StandardCharsets.UTF_8));

        return matches ? Optional.of(ticketId) : Optional.empty();
    }

    private String signature(String payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            byte[] raw = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            // URL-safe 且不加 padding —— 跟 TokenGenerator 的風格一致，
            // 而且之後若要放進網址也不用再轉一次
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            // HmacSHA256 是 JVM 保證支援的演算法，key 也是建構時就建好的 ——
            // 這兩個分支理論上永遠不會執行，但簽章上是 checked exception
            throw new IllegalStateException("票券簽章失敗", e);
        }
    }
}
