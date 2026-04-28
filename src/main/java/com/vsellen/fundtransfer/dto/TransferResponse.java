package com.vsellen.fundtransfer.dto;

import com.vsellen.fundtransfer.domain.Transfer;
import com.vsellen.fundtransfer.domain.TransferStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class TransferResponse {

    private String id;
    private Long fromAccountId;
    private Long toAccountId;
    private BigDecimal amount;
    private TransferStatus status;
    private LocalDateTime createdAt;

    public static TransferResponse from(Transfer transfer) {
        TransferResponse response = new TransferResponse();
        response.id = transfer.getId();
        response.fromAccountId = transfer.getFromAccountId();
        response.toAccountId = transfer.getToAccountId();
        response.amount = transfer.getAmount();
        response.status = transfer.getStatus();
        response.createdAt = transfer.getCreatedAt();
        return response;
    }

    public String getId() { return id; }
    public Long getFromAccountId() { return fromAccountId; }
    public Long getToAccountId() { return toAccountId; }
    public BigDecimal getAmount() { return amount; }
    public TransferStatus getStatus() { return status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
