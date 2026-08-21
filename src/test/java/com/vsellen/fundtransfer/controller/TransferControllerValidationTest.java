package com.vsellen.fundtransfer.controller;

import com.vsellen.fundtransfer.service.TransferService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the actual MVC dispatch (not just GlobalExceptionHandler in isolation) so a
 * regression that lets the Exception.class fallback swallow one of these back into a 500
 * would actually fail these tests.
 */
@WebMvcTest(TransferController.class)
class TransferControllerValidationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TransferService transferService;

    @Test
    void missingIdempotencyKeyHeaderReturns400NotTheGenericFallback500() throws Exception {
        String body = """
                {"fromAccountReference":"acc-1","toAccountReference":"acc-2","amount":10.00}
                """;

        mockMvc.perform(post("/v1/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Missing required header: Idempotency-Key"));
    }

    @Test
    void selfTransferIsRejectedWith400() throws Exception {
        String body = """
                {"fromAccountReference":"same-account","toAccountReference":"same-account","amount":10.00}
                """;

        mockMvc.perform(post("/v1/transfers")
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("fromAccountReference and toAccountReference must differ"));
    }

    @Test
    void amountWithMoreThanTwoDecimalPlacesIsRejectedWith400() throws Exception {
        String body = """
                {"fromAccountReference":"acc-1","toAccountReference":"acc-2","amount":10.123}
                """;

        mockMvc.perform(post("/v1/transfers")
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.field").value("amount"))
                .andExpect(jsonPath("$.message").value("amount must have at most 2 decimal places"));
    }
}
