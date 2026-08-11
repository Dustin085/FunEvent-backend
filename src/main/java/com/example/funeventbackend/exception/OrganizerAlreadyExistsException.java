package com.example.funeventbackend.exception;

public class OrganizerAlreadyExistsException extends RuntimeException {
    public OrganizerAlreadyExistsException(String message) {
        super(message);
    }
}
