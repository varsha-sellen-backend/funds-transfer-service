package com.vsellen.fundtransfer.exception;

import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handleValidationReturnsBadRequestWithFailedFieldAndReason() throws NoSuchMethodException {
        BindingResult bindingResult = mock(BindingResult.class);
        FieldError fieldError = new FieldError("transferRequest", "amount", "must be positive");
        when(bindingResult.getFieldError()).thenReturn(fieldError);
        MethodArgumentNotValidException ex =
                new MethodArgumentNotValidException(dummyMethodParameter(), bindingResult);

        ResponseEntity<ErrorResponse> response = handler.handleValidation(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.status()).isEqualTo(400);
        assertThat(body.error()).isEqualTo("Bad Request");
        assertThat(body.field()).isEqualTo("amount");
        assertThat(body.message()).isEqualTo("must be positive");
        assertThat(body.timestamp()).isNotNull();
    }

    @Test
    void handleValidationFallsBackToGenericMessageWhenNoFieldErrorPresent() throws NoSuchMethodException {
        BindingResult bindingResult = mock(BindingResult.class);
        when(bindingResult.getFieldError()).thenReturn(null);
        MethodArgumentNotValidException ex =
                new MethodArgumentNotValidException(dummyMethodParameter(), bindingResult);

        ResponseEntity<ErrorResponse> response = handler.handleValidation(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().field()).isNull();
        assertThat(response.getBody().message()).isEqualTo("Validation failed");
    }

    @Test
    void handleValidationReturnsGlobalErrorMessageForClassLevelConstraintViolation() throws NoSuchMethodException {
        BindingResult bindingResult = mock(BindingResult.class);
        when(bindingResult.getFieldError()).thenReturn(null);
        ObjectError objectError = new ObjectError("transferRequest",
                "fromAccountReference and toAccountReference must differ");
        when(bindingResult.getGlobalError()).thenReturn(objectError);
        MethodArgumentNotValidException ex =
                new MethodArgumentNotValidException(dummyMethodParameter(), bindingResult);

        ResponseEntity<ErrorResponse> response = handler.handleValidation(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().field()).isNull();
        assertThat(response.getBody().message())
                .isEqualTo("fromAccountReference and toAccountReference must differ");
    }

    @Test
    void handleMissingHeaderReturnsBadRequestNamingTheHeader() throws NoSuchMethodException {
        MissingRequestHeaderException ex =
                new MissingRequestHeaderException("Idempotency-Key", dummyMethodParameter());

        ResponseEntity<ErrorResponse> response = handler.handleMissingHeader(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).isEqualTo("Missing required header: Idempotency-Key");
    }

    @Test
    void handleMalformedRequestBodyReturnsBadRequestWithoutLeakingParserDetails() {
        HttpMessageNotReadableException ex =
                new HttpMessageNotReadableException("JSON parse error: Unexpected character ('}' (code 125))");

        ResponseEntity<ErrorResponse> response = handler.handleMalformedRequestBody(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).isEqualTo("Malformed request body");
        assertThat(response.getBody().message()).doesNotContain("JSON parse error");
    }

    @Test
    void handleTypeMismatchReturnsBadRequestNamingParameterAndExpectedType() throws NoSuchMethodException {
        MethodArgumentTypeMismatchException ex = new MethodArgumentTypeMismatchException(
                "not-a-number", Long.class, "accountId", dummyMethodParameter(), new IllegalArgumentException());

        ResponseEntity<ErrorResponse> response = handler.handleTypeMismatch(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).isEqualTo("Invalid value for parameter 'accountId': expected Long");
    }

    @Test
    void handleUnexpectedReturnsSanitizedInternalServerError() {
        RuntimeException ex = new RuntimeException("password=hunter2; connection to jdbc:postgresql leaked here");

        ResponseEntity<ErrorResponse> response = handler.handleUnexpected(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.status()).isEqualTo(500);
        assertThat(body.error()).isEqualTo("Internal Server Error");
        assertThat(body.message()).isEqualTo("An unexpected error occurred");
        assertThat(body.message()).doesNotContain("hunter2", "jdbc:postgresql");
        assertThat(body.field()).isNull();
    }

    private MethodParameter dummyMethodParameter() throws NoSuchMethodException {
        Method method = GlobalExceptionHandlerTest.class.getDeclaredMethod("dummyTarget", String.class);
        return new MethodParameter(method, 0);
    }

    @SuppressWarnings("unused")
    private void dummyTarget(String value) {
    }
}
