package com.example.funeventbackend.payment.ecpay;

import com.example.funeventbackend.model.Order;
import com.example.funeventbackend.model.Payment;
import com.example.funeventbackend.payment.PaymentCallbackResult;
import com.example.funeventbackend.payment.PaymentGateway;
import com.example.funeventbackend.payment.PaymentInitiation;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 綠界全方位金流（AioCheckOut V5）。
 * <p>
 * 綠界走的是「前端表單 POST」模型，不是「後端呼叫 API」：
 * 本類別只負責算出一包參數與簽章交給前端，由使用者的瀏覽器 POST 給綠界。
 * 因此 {@link #initiate} 沒有任何外部 HTTP 往返，放在交易內是安全的。
 * <p>
 * 只在 {@code app.payment.gateway=ecpay} 時啟用，否則走 FakePaymentGateway。
 */
@Component
@ConditionalOnProperty(name = "app.payment.gateway", havingValue = "ecpay")
@EnableConfigurationProperties(EcpayProperties.class)
@RequiredArgsConstructor
@Slf4j
public class EcpayPaymentGateway implements PaymentGateway {
    private static final DateTimeFormatter TRADE_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss");
    // 綠界的時間是台灣時間，不是 UTC
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    // TradeDesc / ItemName「請勿帶入特殊字元」，只留中英數與空白
    private static final Pattern UNSAFE_CHARS = Pattern.compile("[^\\p{IsHan}\\p{Alnum} ]");
    private static final int ITEM_NAME_MAX_LENGTH = 400;

    private final EcpayProperties properties;

    @PostConstruct
    void validateConfiguration() {
        // 缺 ReturnURL 的話付款會成功、但通知永遠收不到 —— 訂單卡在 PENDING 而錢已經收了。
        // 這種錯誤等到執行期才發現時，已經有人付錢了，所以開機就擋。
        if (!StringUtils.hasText(properties.returnUrl())) {
            throw new IllegalStateException(
                    "使用綠界金流時必須設定 app.payment.ecpay.return-url（需為公開可連的網址）");
        }
    }

    @Override
    public PaymentInitiation initiate(Order order, Payment payment) {
        Map<String, String> params = new HashMap<>();
        params.put("MerchantID", properties.merchantId());
        params.put("MerchantTradeNo", payment.getMerchantTradeNo());
        params.put("MerchantTradeDate", LocalDateTime.now(TAIPEI).format(TRADE_DATE_FORMAT));
        params.put("PaymentType", "aio");
        params.put("TotalAmount", toIntegerAmount(payment.getAmount()));
        params.put("TradeDesc", "FunEvent 活動票券");
        params.put("ItemName", buildItemName(order));
        params.put("ReturnURL", properties.returnUrl());
        params.put("ChoosePayment", "Credit");
        params.put("EncryptType", "1");
        // 綠界付款頁上「返回商店」按鈕要導去的位置。沒有它，使用者付完款
        // 會停在綠界的頁面，不知道該回哪裡。
        // ⚠️ 這只是瀏覽器導頁，跟付款結果沒有因果關係 ——
        // 使用者可以完全沒付款就按返回，所以訂單狀態一律以回呼寫入的為準。
        params.put("ClientBackURL", properties.clientBackUrl() + "/orders/" + order.getId());

        params.put("CheckMacValue",
                CheckMacValueCalculator.calculate(params, properties.hashKey(), properties.hashIv()));

        return new PaymentInitiation(properties.apiUrl(), params);
    }

    @Override
    public Optional<PaymentCallbackResult> parseCallback(Map<String, String> params) {
        String received = params.get("CheckMacValue");
        if (received == null) {
            return Optional.empty();
        }
        String expected = CheckMacValueCalculator.calculate(
                params, properties.hashKey(), properties.hashIv());

        // 用常數時間比對，不要用 equals —— 理由跟密碼比對必須走 BCrypt.matches 相同：
        // 逐字元比對會因「前幾個字對了就比較慢」而把資訊洩漏給計時攻擊
        if (!MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                received.toUpperCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8))) {
            log.warn("綠界回呼驗簽失敗 MerchantTradeNo={}", params.get("MerchantTradeNo"));
            return Optional.empty();
        }

        return Optional.of(new PaymentCallbackResult(
                params.get("MerchantTradeNo"),
                params.get("TradeNo"),
                "1".equals(params.get("RtnCode")),   // 1 = 付款成功
                new BigDecimal(params.get("TradeAmt"))));
    }

    /**
     * 綠界的 TotalAmount 只收整數台幣。有小數就拒絕，絕不四捨五入 ——
     * 靜默進位等於使用者被多收或少收錢，而且不留任何痕跡。
     */
    private String toIntegerAmount(BigDecimal amount) {
        try {
            return amount.setScale(0, RoundingMode.UNNECESSARY).toPlainString();
        } catch (ArithmeticException e) {
            throw new IllegalStateException(
                    "綠界僅接受整數台幣，此筆金額含小數：" + amount.toPlainString(), e);
        }
    }

    /** 多個商品用 # 分隔，上限 400 字。特殊字元會讓綠界拒絕，先清掉。 */
    private String buildItemName(Order order) {
        String itemName = order.getOrderItems().stream()
                .map(item -> UNSAFE_CHARS.matcher(item.getTicketTypeName()).replaceAll("")
                        + " x" + item.getQuantity())
                .collect(Collectors.joining("#"));
        if (!StringUtils.hasText(itemName)) {
            itemName = "活動票券";
        }
        return itemName.length() > ITEM_NAME_MAX_LENGTH
                ? itemName.substring(0, ITEM_NAME_MAX_LENGTH)
                : itemName;
    }
}
