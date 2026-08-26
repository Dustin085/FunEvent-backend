package com.example.funeventbackend.service;

import com.example.funeventbackend.model.Event;
import com.example.funeventbackend.model.Favorite;
import com.example.funeventbackend.model.User;
import com.example.funeventbackend.repository.FavoriteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class FavoriteService {
    private final FavoriteRepository favoriteRepository;
    private final EventService eventService;

    @Transactional
    public void create(User user, Long eventId) {
        Event event = eventService.getPublishedEntity(eventId);
        Favorite favorite = Favorite.builder()
                .user(user)
                .event(event)
                .build();
        try {
            favoriteRepository.save(favorite);
        } catch (DataIntegrityViolationException e) {
            // 已經收藏過了 —— PUT 的語意是「我要它變成這個狀態」，
            // 而那個狀態已經達成，這是成功不是錯誤
        }
    }

    @Transactional
    public void delete(User user, Long eventId) {
        favoriteRepository.deleteByUserIdAndEventId(user.getId(), eventId);
    }
}
