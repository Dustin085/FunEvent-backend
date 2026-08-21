package com.example.funeventbackend.repository;

import com.example.funeventbackend.dto.order.EventSalesByStatus;
import com.example.funeventbackend.model.OrderItem;
import com.example.funeventbackend.model.OrderStatusType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {
    // 取消訂單時要知道該回補哪些票種、各幾張
    List<OrderItem> findByOrderId(Long orderId);

    // 刪除票種前的檢查：有任何訂單參照就不能刪
    boolean existsByTicketTypeId(Long ticketTypeId);

    /**
     * 某個活動的所有銷售明細。
     *
     * <p>⚠️ 查的是 OrderItem 不是 Order —— 一筆訂單可以跨活動下單，
     * 用 Order 當單位的話主辦者會看到別人活動的明細。
     *
     * <p>⚠️ @EntityGraph 一路預抓到 order.user：列表要顯示買家名稱與訂單狀態，
     * 不預抓的話每一列都會為此多發查詢。全程是 @ManyToOne，
     * JOIN 後列數不變，分頁的 LIMIT 仍然正確。
     */
    @EntityGraph(attributePaths = {"order", "order.user"})
    @Query("SELECT oi FROM OrderItem oi WHERE oi.ticketType.event.id = :eventId")
    Page<OrderItem> findByEventId(@Param("eventId") Long eventId, Pageable pageable);

    @EntityGraph(attributePaths = {"order", "order.user"})
    @Query("SELECT oi FROM OrderItem oi WHERE oi.ticketType.event.id = :eventId "
            + "AND oi.order.status = :status")
    Page<OrderItem> findByEventIdAndStatus(
            @Param("eventId") Long eventId,
            @Param("status") OrderStatusType status,
            Pageable pageable);

    /**
     * 依訂單狀態彙總這個活動的銷售。
     *
     * <p>⚠️ 用 GROUP BY 而不是把 CASE WHEN 疊在一句裡 ——
     * 後者在 JPQL 裡要寫成一長串完整限定的 enum 比較，幾乎不可讀。
     * 這樣最多回三、四列，在 Service 裡組起來很直覺。
     */
    @Query("SELECT new com.example.funeventbackend.dto.order.EventSalesByStatus("
            + "oi.order.status, SUM(oi.quantity), SUM(oi.unitPrice * oi.quantity)) "
            + "FROM OrderItem oi WHERE oi.ticketType.event.id = :eventId "
            + "GROUP BY oi.order.status")
    List<EventSalesByStatus> sumByStatus(@Param("eventId") Long eventId);
}
