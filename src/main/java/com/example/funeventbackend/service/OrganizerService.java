package com.example.funeventbackend.service;

import com.example.funeventbackend.dto.organizer.CreateOrganizerRequest;
import com.example.funeventbackend.exception.NotOrganizerException;
import com.example.funeventbackend.exception.OrganizerAlreadyExistsException;
import com.example.funeventbackend.model.Organizer;
import com.example.funeventbackend.model.User;
import com.example.funeventbackend.repository.OrganizerRepository;
import com.example.funeventbackend.dto.organizer.OrganizerResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OrganizerService {
    private final OrganizerRepository organizerRepository;

    @Transactional
    public OrganizerResponse create(User user, CreateOrganizerRequest dto) {
        // 檢查是否已有 Organizer
        if (organizerRepository.existsByUser(user)) {
            throw new OrganizerAlreadyExistsException("您已經是主辦者了");
        }
        // 建立新 Organizer
        Organizer newOrganizer = Organizer.builder()
                .user(user)
                .name(dto.name())
                .introduction(dto.introduction())
                .build();
        // 存入資料庫
        Organizer savedOrganizer = organizerRepository.save(newOrganizer);

        return OrganizerResponse.from(savedOrganizer);
    }

    /**
     * 目前使用者的主辦者身分。
     *
     * <p>⚠️ 不是主辦者時會拋 NotOrganizerException（403）。
     * 前端把它當成「還沒有主辦者身分」而不是錯誤 ——
     * 和 getCurrentUser() 遇到 401 回傳 null 是同一個模式。
     */
    @Transactional(readOnly = true)
    public OrganizerResponse getMine(User user) {
        return OrganizerResponse.from(getEntityByUser(user));
    }

    @Transactional(readOnly = true)
    public Organizer getEntityByUser(User user) {
        return organizerRepository.findByUser(user)
                .orElseThrow(
                        () -> new NotOrganizerException("使用者不具有主辦人身分")
                );
    }
}
