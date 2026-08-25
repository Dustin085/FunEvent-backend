package com.example.funeventbackend.repository;

import com.example.funeventbackend.dto.event.EventTicketAggregate;
import com.example.funeventbackend.model.TicketType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface TicketTypeRepository extends JpaRepository<TicketType, Long> {
    // 產生 SELECT 1 ... LIMIT 1，比撈出整包再判斷 isEmpty() 便宜
    boolean existsByEventId(Long eventId);

    List<TicketType> findByEventIdOrderByIdAsc(Long eventId);

    // 一次撈出多個票種並同時載入活動，避免逐一存取 t.getEvent() 造成 1+N
    @Query("SELECT t FROM TicketType t JOIN FETCH t.event WHERE t.id IN :ids")
    List<TicketType> findAllByIdInWithEvent(@Param("ids") Collection<Long> ids);

    /**
     * 一頁活動的最低價與剩餘張數，一句查完。
     *
     * <p>⭐ 只算 {@code stock > 0} 的票種：售罄的票種價格拿去顯示「NT$ X 起」
     * 是在騙人 —— 那個價格永遠買不到。剩餘張數不受這個條件影響，
     * 因為售罄的票種本來就貢獻 0。
     *
     * <p>⚠️ 販售期間刻意不列入條件，見 {@code EventService.loadTicketAggregates}。
     *
     * <p>⚠️ {@code t.event.id} 走的是外鍵欄位，不會產生 JOIN。
     */
    @Query("SELECT new com.example.funeventbackend.dto.event.EventTicketAggregate("
            + "t.event.id, MIN(t.price), SUM(t.stock)) "
            + "FROM TicketType t "
            + "WHERE t.event.id IN :eventIds AND t.stock > 0 "
            + "GROUP BY t.event.id")
    List<EventTicketAggregate> findAggregatesByEventIds(@Param("eventIds") Collection<Long> eventIds);

    // 條件式 UPDATE：判斷與扣減在同一句 SQL，由資料庫保證原子性，
    // 回傳受影響列數，0 代表庫存不足
    @Modifying(flushAutomatically = true)
    @Query("UPDATE TicketType t SET t.stock = t.stock - :quantity "
            + "WHERE t.id = :id AND t.stock >= :quantity")
    int decreaseStock(@Param("id") Long id, @Param("quantity") int quantity);

    // 回補庫存（訂單逾時取消時）。
    // ⚠️ 刻意不加 stock + quantity <= capacity 的條件 —— 那會讓「重複回補」
    // 默默失敗，而我們要它爆掉：冪等的保證來自 Order.markCancelled 的狀態轉移，
    // 不是這一句。ck_ticket_types_stock_within_capacity 這個 CHECK 是最後的警報器
    @Modifying(flushAutomatically = true)
    @Query("UPDATE TicketType t SET t.stock = t.stock + :quantity WHERE t.id = :id")
    int restoreStock(@Param("id") Long id, @Param("quantity") int quantity);
}
