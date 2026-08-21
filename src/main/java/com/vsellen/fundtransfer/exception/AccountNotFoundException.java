package com.vsellen.fundtransfer.exception;

public class AccountNotFoundException extends RuntimeException {
    public AccountNotFoundException(String accountReference) {
        super("Account not found: " + accountReference);
    }
}
