package com.example.funeventbackend.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    @JsonIgnore
    // ⚠️ 可為 null：透過 Google 等第三方登入建立的帳號沒有密碼。
    // 判斷「這個帳號能不能用密碼登入」請用 hasPassword()，不要各處自己比對 null
    @Column(name = "password_hash")
    private String passwordHash;

    @Column()
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RoleType role;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * 這個帳號是否可以用密碼登入。
     * 第三方登入建立的帳號沒有密碼，只能走原本的 provider。
     */
    public boolean hasPassword() {
        return passwordHash != null;
    }
}
