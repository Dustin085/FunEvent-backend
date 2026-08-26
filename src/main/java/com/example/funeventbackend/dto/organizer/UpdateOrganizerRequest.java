package com.example.funeventbackend.dto.organizer;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * ⚠️ 欄位與驗證跟 {@link CreateOrganizerRequest} 相同，但刻意分成兩個 record ——
 * 建立與更新是不同的動作，之後只要有一邊多一個欄位（例如建立時要同意條款），
 * 共用的那個就得加上「這個欄位只有建立時要填」的條件判斷。
 */
public record UpdateOrganizerRequest(
        @NotBlank(message = "名稱不能為空")
        @Size(max = 255)
        String name,

        String introduction
) {
}
