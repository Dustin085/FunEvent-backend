package com.example.funeventbackend.exception;

public class InvalidPaymentCallbackException extends RuntimeException {
    public InvalidPaymentCallbackException(String message) {
        super(message);
    }
}
