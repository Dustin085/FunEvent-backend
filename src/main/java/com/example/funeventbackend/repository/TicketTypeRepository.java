package com.example.funeventbackend.repository;

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

    // 條件式 UPDATE：判斷與扣減在同一句 SQL，由資料庫保證原子性，
    // 回傳受影響列數，0 代表庫存不足
    @Modifying(flushAutomatically = true)
    @Query("UPDATE TicketType t SET t.stock = t.stock - :quantity "
            + "WHERE t.id = :id AND t.stock >= :quantity")
    int decreaseStock(@Param("id") Long id, @Param("quantity") int quantity);
}
