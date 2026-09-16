package com.example.funeventbackend.payment.ecpay;

import com.example.funeventbackend.model.Order;
import com.example.funeventbackend.model.Payment;
import com.example.funeventbackend.payment.PaymentCallbackResult;
import com.example.funeventbackend.payment.PaymentGateway;
import com.example.funeventbackend.payment.PaymentInitiation;
import com.example.funeventbackend.payment.PaymentQueryResult;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
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

    // 只有 queryStatus 需要真的對外打 HTTP（initiate/parseCallback 都不用），
    // 所以不透過 Spring bean 注入，直接建一個帶逾時的 RestClient 就夠。
    // 逾時設定跟 GoogleOAuthConfig.googleOAuthRestClient 同樣的理由：
    // 這支會在排程執行緒裡被呼叫，對方一慢就會卡住整批取消作業。
    private final RestClient restClient = RestClient.builder()
            .requestFactory(timeoutRequestFactory())
            .build();

    private static SimpleClientHttpRequestFactory timeoutRequestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        return factory;
    }

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
     * 主動查詢一筆交易目前的狀態（QueryTradeInfo/V2）。
     * <p>
     * ⚠️ 這支 API 跟 {@link #initiate}／{@link #parseCallback} 用的 AioCheckOut V5
     * 是不同的 API，但同一個網域、同一套 CheckMacValue 簽章方式，所以能直接沿用
     * {@link CheckMacValueCalculator}。<b>不要</b>跟另一支新版的 QueryTrade
     * （{@code ecpayment.ecpay.com.tw}）搞混——那支走 JSON + AES 加密，
     * 是完全不同的簽章機制，接錯支會全部解不開。
     * <p>
     * ⚠️ 官方文件沒有明確寫出回應本體的傳輸格式，這裡照 ECPay classic API
     * 一貫的 {@code key=value&key=value} 表單字串風格解析。第一次真的打測試環境時
     * 務必核對這裡解析不解析得出來——文件含糊的地方不能只靠猜。
     */
    @Override
    public Optional<PaymentQueryResult> queryStatus(String merchantTradeNo) {
        Map<String, String> params = new HashMap<>();
        params.put("MerchantID", properties.merchantId());
        params.put("MerchantTradeNo", merchantTradeNo);
        // 官方要求的是 Unix 秒數，而且驗證區間只有 3 分鐘內有效 ——
        // 呼叫端千萬不要把這個值算好存起來重複使用
        params.put("TimeStamp", String.valueOf(Instant.now().getEpochSecond()));
        params.put("CheckMacValue",
                CheckMacValueCalculator.calculate(params, properties.hashKey(), properties.hashIv()));

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        params.forEach(form::add);

        String rawResponse;
        try {
            rawResponse = restClient.post()
                    .uri(properties.queryUrl())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientException e) {
            // 連不上、逾時、對方回錯誤狀態碼 —— 呼叫端必須當成「不知道」，
            // 不能當成「查到了、沒付款」，那會誤觸取消
            log.warn("查詢綠界交易狀態失敗 merchantTradeNo={}", merchantTradeNo, e);
            return Optional.empty();
        }

        return parseQueryResponse(rawResponse, merchantTradeNo);
    }

    private Optional<PaymentQueryResult> parseQueryResponse(String rawResponse, String merchantTradeNo) {
        if (!StringUtils.hasText(rawResponse)) {
            return Optional.empty();
        }

        Map<String, String> fields = Arrays.stream(rawResponse.split("&"))
                .map(pair -> pair.split("=", 2))
                .filter(kv -> kv.length == 2)
                .collect(Collectors.toMap(
                        kv -> URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                        kv -> URLDecoder.decode(kv[1], StandardCharsets.UTF_8),
                        (a, b) -> a));

        String tradeStatus = fields.get("TradeStatus");
        String tradeAmt = fields.get("TradeAmt");
        if (tradeStatus == null || tradeAmt == null) {
            log.warn("查詢綠界交易狀態的回應解析不出必要欄位 merchantTradeNo={} raw={}",
                    merchantTradeNo, rawResponse);
            return Optional.empty();
        }

        boolean paid = "1".equals(tradeStatus);
        Instant paidAt = null;
        if (paid) {
            // ⚠️ 這裡不是可有可無的：這筆付款是事後才查到的，
            // 必須用 ECPay 記錄的真正付款時間去判斷「當初付款那一刻訂單過期了沒」，
            // 不能拿查詢當下的「現在」——那會讓一筆其實準時付款、只是回呼漏接的
            // 交易被誤判成逾期。缺這個欄位就沒辦法正確判斷，當成解析失敗處理。
            String paymentDate = fields.get("PaymentDate");
            if (paymentDate == null) {
                log.warn("查詢結果顯示已付款，但缺少 PaymentDate，無法判斷是否逾期 "
                        + "merchantTradeNo={} raw={}", merchantTradeNo, rawResponse);
                return Optional.empty();
            }
            paidAt = LocalDateTime.parse(paymentDate, TRADE_DATE_FORMAT)
                    .atZone(TAIPEI).toInstant();
        }

        return Optional.of(new PaymentQueryResult(
                merchantTradeNo,
                fields.get("TradeNo"),
                paid,
                new BigDecimal(tradeAmt),
                paidAt));
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
