package com.example.funeventbackend.repository;

import com.example.funeventbackend.model.Event;
import com.example.funeventbackend.model.EventStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface EventRepository extends JpaRepository<Event, Long>,
        JpaSpecificationExecutor<Event> {

    /**
     * 覆寫 JpaSpecificationExecutor 的方法只為了掛 @EntityGraph ——
     * 否則列表上每張卡片都會為了主辦單位名稱多發一句 SQL。
     *
     * <p>⚠️ 這是預抓 organizer 的正確位置。在 Specification 裡寫 root.fetch()
     * 會讓 Page 的 COUNT 查詢爆掉（COUNT 查詢不允許 fetch），
     * 得靠檢查 query.getResultType() 來閃 —— 很醜而且容易忘。
     *
     * <p>抓的是 @ManyToOne（對一），JOIN 後列數不變，分頁的 LIMIT 仍然正確 ——
     * 對「集合」用 @EntityGraph 才會逼 Hibernate 改成在記憶體裡分頁。
     */
    @Override
    @EntityGraph(attributePaths = "organizer")
    Page<Event> findAll(Specification<Event> spec, Pageable pageable);

    // 發出 SELECT ... FOR UPDATE，鎖住該列直到交易結束，
    // 避免「讀狀態 → 判斷 → 寫狀態」之間被其他交易插隊（TOCTOU）
    /**
     * 主辦者自己的活動，含草稿與已取消。
     *
     * <p>⚠️ 和公開列表最大的差別：不篩 status、不篩時間。
     * 主辦者要看得到全部，包含已結束的（那是他的歷史紀錄）。
     */
    @EntityGraph(attributePaths = "organizer")
    Page<Event> findByOrganizerId(Long organizerId, Pageable pageable);

    @EntityGraph(attributePaths = "organizer")
    Page<Event> findByOrganizerIdAndStatus(
            Long organizerId, EventStatus status, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM Event e WHERE e.id = :id")
    Optional<Event> findByIdForUpdate(@Param("id") Long id);
}
