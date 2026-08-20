package com.example.funeventbackend.repository;

import com.example.funeventbackend.model.Order;
import com.example.funeventbackend.model.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {
    // 明細靠 Order.orderItems 上的 @BatchSize 批次載入，
    // 這裡不能用 @EntityGraph —— 抓集合會讓 Hibernate 改成在記憶體裡分頁
    Page<Order> findByUser(User user, Pageable pageable);

    // 把「是不是這個人的」寫進查詢條件，而不是查完再用 if 判斷：
    // 「不存在」和「不是你的」自然合流成同一個結果，不會有兩條分支洩漏差異
    @EntityGraph(attributePaths = "orderItems")
    Optional<Order> findByIdAndUser(Long id, User user);

    // 條件式 UPDATE：只有還在 PENDING 的訂單會被轉成 PAID。
    // 回傳 0 代表訂單已經付過款或已被取消 —— 呼叫端必須處理這個情況
    @Modifying(flushAutomatically = true)
    @Query("UPDATE Order o SET o.status = com.example.funeventbackend.model.OrderStatusType.PAID, "
            + "o.paidAt = :paidAt WHERE o.id = :id "
            + "AND o.status = com.example.funeventbackend.model.OrderStatusType.PENDING")
    int markPaid(@Param("id") Long id, @Param("paidAt") Instant paidAt);

    // 取消側與 markPaid 完全對稱：只有 PENDING 會被取消。
    // ⭐ 回傳 1 代表「這次呼叫贏得了狀態轉移」，也就是取得回補庫存的權利；
    // 回傳 0 代表別人先動了（付款成功、或另一個排程實例先取消），什麼都不該做
    @Modifying(flushAutomatically = true)
    @Query("UPDATE Order o SET o.status = com.example.funeventbackend.model.OrderStatusType.CANCELLED "
            + "WHERE o.id = :id "
            + "AND o.status = com.example.funeventbackend.model.OrderStatusType.PENDING")
    int markCancelled(@Param("id") Long id);

    /**
     * 這個使用者有沒有買過這個活動（且已付款）。評論資格的判斷依據。
     *
     * <p>⚠️ 用「COUNT > 0」而不是撈出訂單再判斷 ——
     * 我們只需要知道「有沒有」，不需要那些資料本身。
     */
    @Query("SELECT COUNT(oi) > 0 FROM OrderItem oi "
            + "WHERE oi.order.user.id = :userId "
            + "AND oi.order.status = com.example.funeventbackend.model.OrderStatusType.PAID "
            + "AND oi.ticketType.event.id = :eventId")
    boolean hasPaidOrderForEvent(@Param("userId") Long userId, @Param("eventId") Long eventId);

    // 逾時掃描。只取 id，不撈整個實體 —— 掃描階段不需要訂單內容
    @Query("SELECT o.id FROM Order o "
            + "WHERE o.status = com.example.funeventbackend.model.OrderStatusType.PENDING "
            + "AND o.expiresAt < :now ORDER BY o.id ASC")
    List<Long> findExpiredPendingIds(@Param("now") Instant now, Pageable pageable);
}
