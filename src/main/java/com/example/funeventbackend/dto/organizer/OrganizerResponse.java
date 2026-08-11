package com.example.funeventbackend.dto.organizer;

import com.example.funeventbackend.model.Organizer;

public record OrganizerResponse(
        Long id,
        String name,
        String introduction
) {
    public static OrganizerResponse from(Organizer organizer) {
        return new OrganizerResponse(
                organizer.getId(),
                organizer.getName(),
                organizer.getIntroduction()
        );
    }
}
