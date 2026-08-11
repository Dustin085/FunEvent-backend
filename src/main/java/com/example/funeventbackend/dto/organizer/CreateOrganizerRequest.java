package com.example.funeventbackend.dto.organizer;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateOrganizerRequest(
        @NotBlank(message = "名稱不能為空")
        @Size(max = 255)
        String name,

        String introduction
) {
}
