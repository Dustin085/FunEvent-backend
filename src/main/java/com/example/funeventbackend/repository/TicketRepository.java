package com.example.funeventbackend.repository;

import com.example.funeventbackend.model.Ticket;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TicketRepository extends JpaRepository<Ticket, Long> {

    /** 這張訂單的所有票。用於「我的票券」與測試 */
    List<Ticket> findByOrderItemOrderId(Long orderId);

    /** 這張訂單已經發過票了沒。⚠️ 用來確認不會重複發票 */
    boolean existsByOrderItemOrderId(Long orderId);
}
