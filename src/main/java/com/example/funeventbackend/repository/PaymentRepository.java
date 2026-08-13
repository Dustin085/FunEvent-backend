package com.example.funeventbackend.repository;

import com.example.funeventbackend.model.Payment;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long> {
    // 用悲觀鎖而不是條件式 UPDATE，因為回呼要讀金額比對、還要寫好幾個欄位 ——
    // 「需要那個物件」就用悲觀鎖。單表查詢，只鎖 payments 一列，不會牽連其他表。
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Payment p WHERE p.merchantTradeNo = :merchantTradeNo")
    Optional<Payment> findByMerchantTradeNoForUpdate(@Param("merchantTradeNo") String merchantTradeNo);
}
