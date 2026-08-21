package com.vsellen.fundtransfer.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.vsellen.fundtransfer.domain.Transfer;
import com.vsellen.fundtransfer.domain.TransferStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class TransferResponse {

    private String id;
    private String fromAccountReference;
    private String toAccountReference;
    private BigDecimal amount;
    private TransferStatus status;
    private LocalDateTime createdAt;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String failureReason;

    /**
     * fromAccountReference/toAccountReference must be the accounts' opaque
     * Account.externalReference - never the entity's internal Long id.
     */
    public static TransferResponse from(Transfer transfer, String fromAccountReference, String toAccountReference) {
        TransferResponse response = new TransferResponse();
        response.id = transfer.getId();
        response.fromAccountReference = fromAccountReference;
        response.toAccountReference = toAccountReference;
        response.amount = transfer.getAmount();
        response.status = transfer.getStatus();
        response.createdAt = transfer.getCreatedAt();
        response.failureReason = transfer.getFailureReason();
        return response;
    }

    public String getId() { return id; }
    public String getFromAccountReference() { return fromAccountReference; }
    public String getToAccountReference() { return toAccountReference; }
    public BigDecimal getAmount() { return amount; }
    public TransferStatus getStatus() { return status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public String getFailureReason() { return failureReason; }
}
