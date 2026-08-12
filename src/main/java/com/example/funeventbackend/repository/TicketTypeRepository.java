package com.example.funeventbackend.repository;

import com.example.funeventbackend.model.TicketType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TicketTypeRepository extends JpaRepository<TicketType, Long> {
    // 產生 SELECT 1 ... LIMIT 1，比撈出整包再判斷 isEmpty() 便宜
    boolean existsByEventId(Long eventId);

    List<TicketType> findByEventIdOrderByIdAsc(Long eventId);
}
