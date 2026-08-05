package com.example.funeventbackend.repository;

import com.example.funeventbackend.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    // Query Methods 詳細介紹:
    // https://docs.spring.io/spring-data/jpa/reference/jpa/query-methods.html

    // Spring Data JPA 的語意分析：它會自動幫你推斷出 SQL (以email搜尋)：
    // SELECT * FROM members WHERE email = ?
    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);
}
