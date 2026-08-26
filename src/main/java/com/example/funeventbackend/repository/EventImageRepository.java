package com.example.funeventbackend.repository;

import com.example.funeventbackend.model.EventImage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface EventImageRepository extends JpaRepository<EventImage, Long> {

    /**
     * 更新活動圖片時先刪光再重建（全量取代）。
     *
     * <p>⚠️ 用衍生查詢而不是 {@code @Modifying} 的批次 DELETE ——
     * 後者會繞過持久化上下文，留下「已經被刪掉但仍是 managed」的物件。
     * 一個活動最多 10 張圖，多幾句 SQL 無所謂。
     */
    void deleteByEventId(Long eventId);
}
