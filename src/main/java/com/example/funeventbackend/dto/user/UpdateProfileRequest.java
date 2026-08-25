package com.example.funeventbackend.dto.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * ⚠️ email 不在這裡：換信箱必須先驗證新信箱（寄確認信），
 * 否則等於讓人把帳號改成別人的信箱。那是另一套流程。
 */
public record UpdateProfileRequest(
        // ⚠️ 和 RegisterRequest.name 是同一組規則的第二份。record 的 Bean Validation
        // 標註沒辦法共用，這個重複是刻意接受的 —— 改規則時兩邊都要動
        @NotBlank(message = "名字不可為空")
        @Size(max = 50, message = "名字長度不可超過 50 字元")
        String name
) {
}
