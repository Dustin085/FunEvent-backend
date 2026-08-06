package com.example.funeventbackend.security;

import com.example.funeventbackend.model.User;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

@Getter
@RequiredArgsConstructor
// 自訂的 UserDetails 把我們的 User Entity 轉換成 Security 需要的 UserDetails
public class CustomUserDetails implements UserDetails {
    private final User user;

    @Override
    public String getUsername() {// UserDetails 的 username == User.email
        return user.getEmail();
    }

    @Override
    public String getPassword() {
        return user.getPasswordHash();
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
    }

}
