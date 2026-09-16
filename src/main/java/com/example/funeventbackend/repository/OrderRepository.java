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

    // 條件式 UPDATE：只有還在 PENDING、而且還沒過期的訂單會被轉成 PAID。
    // ⚠️ expiresAt 這個條件不是可有可無的 —— 逾時取消排程只負責「整理」，
    // 排程多久跑一次跟「這筆訂單還能不能被付款」是兩件事。少了這個條件，
    // 排程間隔拉長時，付款回呼在排程來得及取消之前趕上，就會讓一筆早該
    // 失效的訂單成功轉成 PAID（票是還沒被回補所以不會超賣，但等於沒有真正
    // 執行「逾時未付款就失效」這條規則）。
    // paidAt 本來就是呼叫端算好的 Instant.now()，直接拿來當「現在」比對，
    // 不需要再多傳一個 now 參數。
    // 回傳 0 代表訂單已經付過款、已被取消、或已經過期 —— 呼叫端必須處理這個情況
    @Modifying(flushAutomatically = true)
    @Query("UPDATE Order o SET o.status = com.example.funeventbackend.model.OrderStatusType.PAID, "
            + "o.paidAt = :paidAt WHERE o.id = :id "
            + "AND o.status = com.example.funeventbackend.model.OrderStatusType.PENDING "
            + "AND o.expiresAt >= :paidAt")
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
     * 這個活動有沒有任何人付款成功。取消活動前的檢查依據。
     *
     * <p>⚠️ 有人付過錢就不能單方面取消 —— 那需要一整套退款流程。
     */
    @Query("SELECT COUNT(oi) > 0 FROM OrderItem oi "
            + "WHERE oi.order.status = com.example.funeventbackend.model.OrderStatusType.PAID "
            + "AND oi.ticketType.event.id = :eventId")
    boolean existsPaidOrderForEvent(@Param("eventId") Long eventId);

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
    //
    // ⚠️ NOT EXISTS 這段不是可有可無的：一筆訂單過期了，不代表可以安全取消 ——
    // 使用者可能在訂單快過期前才按下付款，Payment 有自己獨立的期限（見
    // PaymentService.initiate）。只要還有一筆 Payment 落在自己的期限內，
    // 這筆訂單就還算「有人在處理中」，不列入可取消名單，等那筆 Payment
    // 也過期了（或成功／失敗，狀態不再是 PENDING）才會出現在這裡。
    @Query("SELECT o.id FROM Order o "
            + "WHERE o.status = com.example.funeventbackend.model.OrderStatusType.PENDING "
            + "AND o.expiresAt < :now "
            + "AND NOT EXISTS (SELECT 1 FROM Payment p WHERE p.order = o "
            + "AND p.status = com.example.funeventbackend.model.PaymentStatusType.PENDING "
            + "AND p.expiresAt >= :now) "
            + "ORDER BY o.id ASC")
    List<Long> findExpiredPendingIds(@Param("now") Instant now, Pageable pageable);
}
