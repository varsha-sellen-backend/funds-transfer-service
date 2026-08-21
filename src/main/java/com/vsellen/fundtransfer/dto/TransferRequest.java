package com.vsellen.fundtransfer.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

@DifferentAccounts
public class TransferRequest {

    @NotBlank
    private String fromAccountReference;

    @NotBlank
    private String toAccountReference;

    @NotNull
    @Positive
    @Digits(integer = 17, fraction = 2, message = "amount must have at most 2 decimal places")
    private BigDecimal amount;

    public String getFromAccountReference() { return fromAccountReference; }
    public void setFromAccountReference(String fromAccountReference) { this.fromAccountReference = fromAccountReference; }

    public String getToAccountReference() { return toAccountReference; }
    public void setToAccountReference(String toAccountReference) { this.toAccountReference = toAccountReference; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
}
