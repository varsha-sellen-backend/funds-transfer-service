package com.vsellen.fundtransfer.service;

import com.vsellen.fundtransfer.domain.Account;
import com.vsellen.fundtransfer.domain.Transfer;
import com.vsellen.fundtransfer.domain.TransferStatus;
import com.vsellen.fundtransfer.dto.TransferRequest;
import com.vsellen.fundtransfer.event.TransferInitiatedEvent;
import com.vsellen.fundtransfer.exception.TransferNotFoundException;
import com.vsellen.fundtransfer.kafka.TransferEventProducer;
import com.vsellen.fundtransfer.repository.AccountRepository;
import com.vsellen.fundtransfer.repository.TransferRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

    @Mock
    private AccountRepository accountRepository;
    @Mock
    private TransferRepository transferRepository;
    @Mock
    private TransferEventProducer eventProducer;

    private TransferService transferService;

    @BeforeEach
    void setUp() {
        transferService = new TransferService(accountRepository, transferRepository, eventProducer);
    }

    @Test
    void initiateWithKnownIdempotencyKeyReturnsExistingTransferWithoutPublishing() {
        Transfer existing = new Transfer();
        existing.setId("t-1");
        when(transferRepository.findByReferenceId("key-1")).thenReturn(Optional.of(existing));

        Transfer result = transferService.initiate(new TransferRequest(), "key-1");

        assertThat(result).isSameAs(existing);
        verify(transferRepository, never()).saveAndFlush(any());
        verify(eventProducer, never()).publishTransferInitiated(any());
    }

    @Test
    void initiateWithNewIdempotencyKeyCreatesTransferAndPublishesEvent() {
        when(transferRepository.findByReferenceId("key-2")).thenReturn(Optional.empty());
        TransferRequest request = new TransferRequest();
        request.setFromAccountId(1L);
        request.setToAccountId(2L);
        request.setAmount(new BigDecimal("50.00"));

        Transfer result = transferService.initiate(request, "key-2");

        assertThat(result.getStatus()).isEqualTo(TransferStatus.INITIATED);
        assertThat(result.getReferenceId()).isEqualTo("key-2");

        ArgumentCaptor<TransferInitiatedEvent> eventCaptor = ArgumentCaptor.forClass(TransferInitiatedEvent.class);
        verify(eventProducer).publishTransferInitiated(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getTransferId()).isEqualTo(result.getId());
    }

    @Test
    void initiateFallsBackToWinnerWhenConcurrentInsertLosesUniqueConstraintRace() {
        Transfer winner = new Transfer();
        winner.setId("t-3");
        when(transferRepository.findByReferenceId("key-3"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        when(transferRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate key"));

        Transfer result = transferService.initiate(new TransferRequest(), "key-3");

        assertThat(result).isSameAs(winner);
        verify(eventProducer, never()).publishTransferInitiated(any());
    }

    @Test
    void processTransferIsNoOpWhenTransferIsNotInitiated() {
        Transfer alreadyDone = new Transfer();
        alreadyDone.setId("t-4");
        alreadyDone.setStatus(TransferStatus.SUCCESS);
        when(transferRepository.findById("t-4")).thenReturn(Optional.of(alreadyDone));

        transferService.processTransfer("t-4");

        verify(accountRepository, never()).findById(any());
        verify(transferRepository, never()).save(any());
    }

    @Test
    void processTransferMovesFundsAndMarksSuccess() {
        Transfer transfer = new Transfer();
        transfer.setId("t-5");
        transfer.setStatus(TransferStatus.INITIATED);
        transfer.setFromAccountId(1L);
        transfer.setToAccountId(2L);
        transfer.setAmount(new BigDecimal("30.00"));

        Account from = new Account();
        from.setBalance(new BigDecimal("100.00"));
        Account to = new Account();
        to.setBalance(new BigDecimal("10.00"));

        when(transferRepository.findById("t-5")).thenReturn(Optional.of(transfer));
        when(accountRepository.findById(1L)).thenReturn(Optional.of(from));
        when(accountRepository.findById(2L)).thenReturn(Optional.of(to));

        transferService.processTransfer("t-5");

        assertThat(from.getBalance()).isEqualTo(new BigDecimal("70.00"));
        assertThat(to.getBalance()).isEqualTo(new BigDecimal("40.00"));
        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.SUCCESS);
    }

    @Test
    void processTransferMarksFailedOnInsufficientFundsWithoutThrowing() {
        Transfer transfer = new Transfer();
        transfer.setId("t-6");
        transfer.setStatus(TransferStatus.INITIATED);
        transfer.setFromAccountId(1L);
        transfer.setToAccountId(2L);
        transfer.setAmount(new BigDecimal("500.00"));

        Account from = new Account();
        from.setBalance(new BigDecimal("10.00"));
        Account to = new Account();
        to.setBalance(new BigDecimal("10.00"));

        when(transferRepository.findById("t-6")).thenReturn(Optional.of(transfer));
        when(accountRepository.findById(1L)).thenReturn(Optional.of(from));
        when(accountRepository.findById(2L)).thenReturn(Optional.of(to));

        transferService.processTransfer("t-6");

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(transfer.getFailureReason()).isNotBlank();
        verify(accountRepository, never()).save(any());
    }

    @Test
    void processTransferPropagatesOptimisticLockFailureSoKafkaRetries() {
        Transfer transfer = new Transfer();
        transfer.setId("t-7");
        transfer.setStatus(TransferStatus.INITIATED);
        transfer.setFromAccountId(1L);
        transfer.setToAccountId(2L);
        transfer.setAmount(new BigDecimal("10.00"));

        Account from = new Account();
        from.setBalance(new BigDecimal("100.00"));
        Account to = new Account();
        to.setBalance(new BigDecimal("10.00"));

        when(transferRepository.findById("t-7")).thenReturn(Optional.of(transfer));
        when(accountRepository.findById(1L)).thenReturn(Optional.of(from));
        when(accountRepository.findById(2L)).thenReturn(Optional.of(to));
        when(accountRepository.save(from)).thenThrow(new ObjectOptimisticLockingFailureException(Account.class, 1L));

        assertThatThrownBy(() -> transferService.processTransfer("t-7"))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    @Test
    void findByIdThrowsWhenTransferIsMissing() {
        when(transferRepository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transferService.findById("missing"))
                .isInstanceOf(TransferNotFoundException.class);
    }

    @Test
    void markFailedSetsStatusAndReason() {
        Transfer transfer = new Transfer();
        transfer.setId("t-8");
        transfer.setStatus(TransferStatus.PROCESSING);
        when(transferRepository.findById("t-8")).thenReturn(Optional.of(transfer));

        transferService.markFailed("t-8", "downstream timeout");

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(transfer.getFailureReason()).isEqualTo("downstream timeout");
        verify(transferRepository, times(1)).save(transfer);
    }
}
