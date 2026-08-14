package com.example.funeventbackend.repository;

import com.example.funeventbackend.model.Event;
import com.example.funeventbackend.model.EventStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface EventRepository extends JpaRepository<Event, Long> {

    // 列表只回「已發布且還沒開始」的活動。
    // @EntityGraph 一併載入 organizer，否則每張卡片都會為了主辦單位名稱多發一句 SQL。
    // 這裡抓的是 @ManyToOne（對一），JOIN 後列數不變，分頁的 LIMIT 仍然正確 ——
    // 對「集合」用 @EntityGraph 才會逼 Hibernate 改成在記憶體裡分頁。
    @EntityGraph(attributePaths = "organizer")
    Page<Event> findByStatusAndStartAtAfter(EventStatus status, Instant startAt, Pageable pageable);

    // 發出 SELECT ... FOR UPDATE，鎖住該列直到交易結束，
    // 避免「讀狀態 → 判斷 → 寫狀態」之間被其他交易插隊（TOCTOU）
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM Event e WHERE e.id = :id")
    Optional<Event> findByIdForUpdate(@Param("id") Long id);
}
