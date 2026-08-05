package com.example.funeventbackend.exception;

import lombok.Getter;

@Getter
public class EmailAlreadyExistsException extends RuntimeException {
    private final String email;

    public EmailAlreadyExistsException(String email) {
        super("Email 已經被使用過了：" + email);
        this.email = email;
    }
}
