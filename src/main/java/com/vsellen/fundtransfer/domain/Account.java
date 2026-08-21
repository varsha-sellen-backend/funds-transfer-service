package com.vsellen.fundtransfer.domain;

import com.vsellen.fundtransfer.exception.InsufficientFundsException;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = "externalReference"))
public class Account {

    @Id
    @GeneratedValue
    private Long id;

    private String userId;

    /** Opaque, non-sequential public identifier for this account. Never expose {@link #id}. */
    private String externalReference;

    private BigDecimal balance;

    @Version
    private Long version;

    @PrePersist
    void generateExternalReference() {
        if (externalReference == null) {
            externalReference = UUID.randomUUID().toString();
        }
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getExternalReference() { return externalReference; }

    public BigDecimal getBalance() { return balance; }
    public void setBalance(BigDecimal balance) { this.balance = balance; }

    public Long getVersion() { return version; }

    public void debit(BigDecimal amount) {
        if (balance.compareTo(amount) < 0) {
            throw new InsufficientFundsException("Account " + id + " has insufficient funds for amount " + amount);
        }
        balance = balance.subtract(amount);
    }

    public void credit(BigDecimal amount) {
        balance = balance.add(amount);
    }
}
