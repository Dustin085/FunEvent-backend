package com.example.funeventbackend.service;

import com.example.funeventbackend.dto.auth.UserResponse;
import com.example.funeventbackend.model.User;
import com.example.funeventbackend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserService {
    private final UserRepository userRepository;

    /**
     * 取得特定 User Entity 僅供後端內部使用，回傳給前端應使用 DTO
     *
     * @param id 要尋找的使用者 id
     * @return 此 id 的 User Entity
     */
    @Transactional(readOnly = true)
    public User getUserEntity(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("找不到使用者"));
    }



    /**
     * 將 User Entity 轉換成 UserResponse
     * @param user 要被轉換的 User Entity
     * @return 符合傳入的 User 狀態的 UserResponse
     */
    private UserResponse convertToResponse(User user) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getName()
        );
    }
}
