package com.vsellen.fundtransfer.exception;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * field is only populated for validation errors (which field failed and why);
 * omitted from the JSON body otherwise.
 */
public record ErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String message,
        @JsonInclude(JsonInclude.Include.NON_NULL) String field
) {

    public static ErrorResponse of(int status, String error, String message) {
        return new ErrorResponse(Instant.now(), status, error, message, null);
    }

    public static ErrorResponse of(int status, String error, String message, String field) {
        return new ErrorResponse(Instant.now(), status, error, message, field);
    }
}
