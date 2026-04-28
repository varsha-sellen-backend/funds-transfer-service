package com.vsellen.fundtransfer.repository;

import com.vsellen.fundtransfer.domain.Account;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountRepository extends JpaRepository<Account, Long> {
}
