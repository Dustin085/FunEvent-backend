package com.example.funeventbackend.repository;

import com.example.funeventbackend.model.Payment;
import com.example.funeventbackend.model.PaymentStatusType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long> {
    // 用悲觀鎖而不是條件式 UPDATE，因為回呼要讀金額比對、還要寫好幾個欄位 ——
    // 「需要那個物件」就用悲觀鎖。單表查詢，只鎖 payments 一列，不會牽連其他表。
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Payment p WHERE p.merchantTradeNo = :merchantTradeNo")
    Optional<Payment> findByMerchantTradeNoForUpdate(@Param("merchantTradeNo") String merchantTradeNo);

    /**
     * 這筆訂單現在有沒有「還活著」的付款嘗試（PENDING 且自己的期限還沒到）。
     *
     * <p>⚠️ PaymentService.initiate 判斷訂單過期時要先查這個，才能決定能不能
     * 立刻取消回補庫存 —— 有活著的付款代表還有機會付款成功，貿然取消會重演
     * 「使用者付完款才發現票被還回去」那個問題，只是換了個進入點。
     */
    @Query("SELECT COUNT(p) > 0 FROM Payment p WHERE p.order.id = :orderId "
            + "AND p.status = com.example.funeventbackend.model.PaymentStatusType.PENDING "
            + "AND p.expiresAt >= :now")
    boolean existsLivePaymentForOrder(@Param("orderId") Long orderId, @Param("now") Instant now);

    /**
     * 這筆訂單所有還卡在 PENDING 的付款嘗試——不看 expiresAt。
     *
     * <p>排程要取消訂單之前，用來找出「該不該主動查詢綠界」的對象：
     * 就算這筆 Payment 自己的期限已經過了，也不代表綠界那邊真的沒收到錢——
     * 唯一能確定的方法是問一次，見 PaymentService.reconcileAndCancelIfUnpaid。
     */
    List<Payment> findByOrderIdAndStatus(Long orderId, PaymentStatusType status);
}
