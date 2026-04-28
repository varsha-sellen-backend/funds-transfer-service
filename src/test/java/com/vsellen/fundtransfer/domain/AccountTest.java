package com.vsellen.fundtransfer.domain;

import com.vsellen.fundtransfer.exception.InsufficientFundsException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountTest {

    @Test
    void debitReducesBalanceWhenFundsAreSufficient() {
        Account account = new Account();
        account.setBalance(new BigDecimal("100.00"));

        account.debit(new BigDecimal("40.00"));

        assertThat(account.getBalance()).isEqualTo(new BigDecimal("60.00"));
    }

    @Test
    void debitThrowsWhenFundsAreInsufficient() {
        Account account = new Account();
        account.setBalance(new BigDecimal("10.00"));

        assertThatThrownBy(() -> account.debit(new BigDecimal("40.00")))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(account.getBalance()).isEqualTo(new BigDecimal("10.00"));
    }

    @Test
    void creditIncreasesBalance() {
        Account account = new Account();
        account.setBalance(new BigDecimal("10.00"));

        account.credit(new BigDecimal("5.00"));

        assertThat(account.getBalance()).isEqualTo(new BigDecimal("15.00"));
    }
}
