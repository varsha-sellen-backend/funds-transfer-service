package com.vsellen.fundtransfer.event;

public class TransferInitiatedEvent {

    private String transferId;

    public TransferInitiatedEvent() {
    }

    public TransferInitiatedEvent(String transferId) {
        this.transferId = transferId;
    }

    public String getTransferId() { return transferId; }
    public void setTransferId(String transferId) { this.transferId = transferId; }
}
