package com.example.funeventbackend.service;

import com.example.funeventbackend.dto.organizer.CreateOrganizerRequest;
import com.example.funeventbackend.dto.organizer.UpdateOrganizerRequest;
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
     * 修改主辦單位的名稱與介紹。
     *
     * <p>⚠️ 這裡不需要像 {@code UserService.updateProfile} 那樣重新載入 ——
     * {@code getEntityByUser} 查出來的就是這個交易裡的 managed entity，
     * 髒檢查會在提交時自動 UPDATE。那邊之所以要重載，是因為它拿到的是
     * {@code principal.getUser()}，那是 JWT filter 在請求早期載入的 detached 物件。
     *
     * <p>⭐ 單位介紹會顯示在每一個活動頁上，所以這支不是可有可無的 ——
     * 沒有它，打錯一個字就永遠留在站上。
     */
    @Transactional
    public OrganizerResponse update(User user, UpdateOrganizerRequest dto) {
        Organizer organizer = getEntityByUser(user);
        organizer.setName(dto.name());
        organizer.setIntroduction(dto.introduction());
        return OrganizerResponse.from(organizer);
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
