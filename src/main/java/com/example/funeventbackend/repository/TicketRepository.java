package com.example.funeventbackend.repository;

import com.example.funeventbackend.model.Ticket;
import com.example.funeventbackend.model.User;
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
public interface TicketRepository extends JpaRepository<Ticket, Long> {

    /**
     * 這張訂單的所有票。
     *
     * <p>⚠️ @EntityGraph 一路抓到 ticketType.event：票券要顯示活動名稱，
     * 不預抓的話每一張票都會為了那個名稱多發兩句查詢。
     * 全程是 @ManyToOne，JOIN 後列數不變。
     */
    @EntityGraph(attributePaths = {
            "orderItem", "orderItem.ticketType", "orderItem.ticketType.event"})
    List<Ticket> findByOrderItemOrderIdOrderByIdAsc(Long orderId);

    /** 這張訂單已經發過票了沒。⚠️ 用來確認不會重複發票 */
    boolean existsByOrderItemOrderId(Long orderId);

    /**
     * 核銷用：撈出這個活動裡的某一張票。
     *
     * <p>⭐ 查詢本身就限定了活動 —— 拿 A 活動的票去掃 B 活動，
     * 結果是「查無此票」而不是「查到了但要記得擋」。
     * <b>能不能授權錯，跟有沒有記得檢查無關，而是查詢能不能撈到不該撈的東西。</b>
     *
     * <p>⚠️ @EntityGraph 一路預抓到 order.user：回應要顯示票種名稱與持票人，
     * 不預抓的話這三層 lazy 關聯會各發一次查詢。全程是 @ManyToOne，
     * JOIN 後列數不變。
     */
    @EntityGraph(attributePaths = {"orderItem", "orderItem.order", "orderItem.order.user"})
    @Query("SELECT t FROM Ticket t WHERE t.id = :ticketId "
            + "AND t.orderItem.ticketType.event.id = :eventId")
    Optional<Ticket> findByIdAndEventId(
            @Param("ticketId") Long ticketId,
            @Param("eventId") Long eventId);

    /**
     * 條件式 UPDATE：判斷與寫入在同一句 SQL，由資料庫保證原子性。
     * 回傳受影響列數，<b>0 代表這張票已經不是 VALID</b>。
     *
     * <p>⚠️ 用「先查狀態再寫」的話，兩個工作人員同時掃同一張票會兩個都放行 ——
     * 跟 {@code Order.markPaid} 是完全一樣的招式。
     */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE Ticket t SET "
            + "t.status = com.example.funeventbackend.model.TicketStatus.USED, "
            + "t.usedAt = :now, t.checkedInBy = :staff "
            + "WHERE t.id = :id "
            + "AND t.status = com.example.funeventbackend.model.TicketStatus.VALID")
    int markUsed(
            @Param("id") Long id,
            @Param("now") Instant now,
            @Param("staff") User staff);
}
