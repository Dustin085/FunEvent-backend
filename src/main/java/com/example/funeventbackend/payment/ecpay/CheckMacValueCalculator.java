package com.example.funeventbackend.payment.ecpay;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 綠界檢查碼（CheckMacValue）計算。
 * <p>
 * 它是簽章，作用跟 JWT 的第三段完全相同：內容 + 只有雙方知道的秘密 → 雜湊 → 附上去。
 * 在付款流程的兩端各用一次：
 * <ul>
 *   <li><b>送出時</b> —— 付款參數是經由使用者的瀏覽器 POST 給綠界的，
 *       使用者看得到也改得動。簽章讓綠界能確認 TotalAmount 沒有被竄改。</li>
 *   <li><b>回呼時</b> —— 回呼端點是 permitAll，全世界都打得到。
 *       驗簽是唯一能證明「這筆回呼真的來自綠界」的方法。</li>
 * </ul>
 * <p>
 * ⚠️ HashKey 與 HashIV 只參與計算，<b>絕對不能</b>當成參數送出去。
 * <p>
 * 官方步驟：
 * <ol>
 *   <li>參數依英文字母 A-Z 排序（不含 CheckMacValue 本身）</li>
 *   <li>以 {@code key=value&key=value} 串接</li>
 *   <li>前面加 {@code HashKey=xxx&}，後面加 {@code &HashIV=xxx}</li>
 *   <li>整串（含 = 與 &）做 URL encode</li>
 *   <li>轉小寫，並把 7 個字元還原成 .NET 的編碼結果</li>
 *   <li>SHA256 後轉大寫</li>
 * </ol>
 */
public final class CheckMacValueCalculator {
    private static final String CHECK_MAC_VALUE = "CheckMacValue";

    private CheckMacValueCalculator() {
    }

    public static String calculate(Map<String, String> params, String hashKey, String hashIv) {
        String raw = params.entrySet().stream()
                .filter(entry -> !CHECK_MAC_VALUE.equalsIgnoreCase(entry.getKey()))
                .sorted(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER))
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("&",
                        "HashKey=" + hashKey + "&",
                        "&HashIV=" + hashIv));

        return sha256(dotNetUrlEncode(raw)).toUpperCase(Locale.ROOT);
    }

    /**
     * 綠界要的是 .NET {@code HttpUtility.UrlEncode} 的結果，不是 Java 的。
     * 兩者對這 7 個字元的處理不同，必須還原 —— 這是 CheckMacValue Error 最常見的原因。
     * <p>
     * 先轉小寫再替換：Java 產生的是 {@code %2D}（大寫），.NET 是 {@code %2d}。
     * 其中 {@code - _ . *} 這 4 個 Java 本來就不編碼，替換是空操作 ——
     * 留著是為了讓這段程式碼對得上官方規格。
     */
    private static String dotNetUrlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .toLowerCase(Locale.ROOT)
                .replace("%2d", "-")
                .replace("%5f", "_")
                .replace("%2e", ".")
                .replace("%21", "!")
                .replace("%2a", "*")
                .replace("%28", "(")
                .replace("%29", ")");
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 保證存在的演算法，走不到這裡
            throw new IllegalStateException("找不到 SHA-256 演算法", e);
        }
    }
}
