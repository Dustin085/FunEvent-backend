package com.example.funeventbackend.repository;

import com.example.funeventbackend.model.Favorite;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface FavoriteRepository extends JpaRepository<Favorite, Long> {
    boolean existsByUserIdAndEventId(Long userId, Long eventId);
    long deleteByUserIdAndEventId(Long userId, Long eventId);

    @EntityGraph(attributePaths = {"event", "event.organizer"})
    Page<Favorite> findByUserId(Long userId, Pageable pageable);
}
