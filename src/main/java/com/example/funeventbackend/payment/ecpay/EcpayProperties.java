package com.example.funeventbackend.payment.ecpay;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 綠界設定。
 * <p>
 * ⚠️ {@code hashKey} 與 {@code hashIv} 只用來計算檢查碼，
 * 絕對不能出現在送給綠界的參數裡 —— 那等於把鑰匙貼在門上。
 */
@ConfigurationProperties(prefix = "app.payment.ecpay")
public record EcpayProperties(
        String merchantId,
        String hashKey,
        String hashIv,
        String apiUrl,
        String returnUrl
) {
}
