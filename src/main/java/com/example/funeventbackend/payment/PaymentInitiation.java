package com.example.funeventbackend.payment;

import java.util.Map;

/**
 * @param paymentUrl 前端要導向（或表單 action）的網址
 * @param formFields 需要以表單 POST 過去的欄位。走純導轉的金流商會是空的
 */
public record PaymentInitiation(String paymentUrl, Map<String, String> formFields) {
}
