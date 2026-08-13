package com.example.funeventbackend.payment.ecpay;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 對照綠界官方文件的驗算範例。
 * <p>
 * 這個測試是整個綠界串接的地基：檢查碼算錯的話，送出付款會被拒絕、
 * 回呼驗簽會永遠失敗，而錯誤訊息只會說「CheckMacValue Error」，不會告訴你哪裡錯。
 * 先讓這裡變綠燈，後面才有意義。
 */
class CheckMacValueCalculatorTest {
    private static final String HASH_KEY = "pwFHCqoQZGmho4w6";
    private static final String HASH_IV = "EkRm7iFT261dpevs";

    @Test
    @DisplayName("對照綠界官方文件的驗算範例")
    void matchesOfficialExample() {
        // 刻意用亂序放入：排序是計算的一部分，不能靠呼叫端先排好
        Map<String, String> params = new LinkedHashMap<>();
        params.put("TradeDesc", "促銷方案");
        params.put("PaymentType", "aio");
        params.put("MerchantTradeDate", "2023/03/12 15:30:23");
        params.put("MerchantTradeNo", "ecpay20230312153023");
        params.put("MerchantID", "3002607");
        params.put("ReturnURL", "https://www.ecpay.com.tw/receive.php");
        params.put("ItemName", "Apple iphone 15");
        params.put("TotalAmount", "30000");
        params.put("ChoosePayment", "ALL");
        params.put("EncryptType", "1");

        assertEquals("6C51C9E6888DE861FD62FB1DD17029FC742634498FD813DC43D4243B5685B840",
                CheckMacValueCalculator.calculate(params, HASH_KEY, HASH_IV));
    }

    @Test
    @DisplayName("CheckMacValue 本身不參與計算")
    void ignoresCheckMacValueItself() {
        // 驗證回呼時，綠界送來的參數裡一定包含 CheckMacValue，
        // 必須拿其他參數重算再比對 —— 漏掉排除就永遠對不上
        Map<String, String> withoutMac = new LinkedHashMap<>();
        withoutMac.put("MerchantID", "3002607");
        withoutMac.put("TotalAmount", "100");

        Map<String, String> withMac = new LinkedHashMap<>(withoutMac);
        withMac.put("CheckMacValue", "WHATEVER");

        assertEquals(CheckMacValueCalculator.calculate(withoutMac, HASH_KEY, HASH_IV),
                CheckMacValueCalculator.calculate(withMac, HASH_KEY, HASH_IV));
    }
}
