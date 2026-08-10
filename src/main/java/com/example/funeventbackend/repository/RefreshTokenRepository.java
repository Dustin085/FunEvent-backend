package com.example.funeventbackend.repository;

import com.example.funeventbackend.model.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {
    // 用 tokenHash 找出 RefreshToken
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    // 取出整條 familyId 內的所有 tokens
    List<RefreshToken> findByFamilyId(UUID id);
}
