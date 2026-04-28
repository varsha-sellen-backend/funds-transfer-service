package com.vsellen.fundtransfer.service;

import com.vsellen.fundtransfer.domain.Account;
import com.vsellen.fundtransfer.domain.Transfer;
import com.vsellen.fundtransfer.domain.TransferStatus;
import com.vsellen.fundtransfer.dto.TransferRequest;
import com.vsellen.fundtransfer.event.TransferInitiatedEvent;
import com.vsellen.fundtransfer.exception.InsufficientFundsException;
import com.vsellen.fundtransfer.exception.TransferNotFoundException;
import com.vsellen.fundtransfer.kafka.TransferEventProducer;
import com.vsellen.fundtransfer.repository.AccountRepository;
import com.vsellen.fundtransfer.repository.TransferRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class TransferService {

    private final AccountRepository accountRepository;
    private final TransferRepository transferRepository;
    private final TransferEventProducer eventProducer;

    public TransferService(AccountRepository accountRepository,
                            TransferRepository transferRepository,
                            TransferEventProducer eventProducer) {
        this.accountRepository = accountRepository;
        this.transferRepository = transferRepository;
        this.eventProducer = eventProducer;
    }

    /**
     * Idempotent on the caller-supplied key: a retried request with the same key returns the
     * original transfer instead of creating a second one. The unique constraint on
     * referenceId is the real guarantee - the findByReferenceId check is just the fast path
     * that avoids hitting it on every retry.
     */
    @Transactional
    public Transfer initiate(TransferRequest request, String idempotencyKey) {
        return transferRepository.findByReferenceId(idempotencyKey)
                .orElseGet(() -> createAndPublish(request, idempotencyKey));
    }

    private Transfer createAndPublish(TransferRequest request, String idempotencyKey) {
        Transfer transfer = new Transfer();
        transfer.setReferenceId(idempotencyKey);
        transfer.setFromAccountId(request.getFromAccountId());
        transfer.setToAccountId(request.getToAccountId());
        transfer.setAmount(request.getAmount());
        transfer.setStatus(TransferStatus.INITIATED);
        transfer.setCreatedAt(LocalDateTime.now());

        try {
            transferRepository.saveAndFlush(transfer);
        } catch (DataIntegrityViolationException raceLost) {
            // A concurrent request with the same idempotency key won the insert first.
            return transferRepository.findByReferenceId(idempotencyKey).orElseThrow();
        }

        eventProducer.publishTransferInitiated(new TransferInitiatedEvent(transfer.getId()));
        return transfer;
    }

    public Transfer findById(String id) {
        return transferRepository.findById(id)
                .orElseThrow(() -> new TransferNotFoundException(id));
    }

    /**
     * Invoked by the Kafka consumer. Guards on status so a duplicate delivery of the same
     * event (retry, consumer restart, rebalance) is a safe no-op instead of a double debit.
     */
    @Transactional
    public void processTransfer(String transferId) {
        Transfer transfer = transferRepository.findById(transferId)
                .orElseThrow(() -> new TransferNotFoundException(transferId));

        if (transfer.getStatus() != TransferStatus.INITIATED) {
            return;
        }

        transfer.setStatus(TransferStatus.PROCESSING);
        transferRepository.save(transfer);

        Account from = accountRepository.findById(transfer.getFromAccountId()).orElseThrow();
        Account to = accountRepository.findById(transfer.getToAccountId()).orElseThrow();

        try {
            from.debit(transfer.getAmount());
            to.credit(transfer.getAmount());
        } catch (InsufficientFundsException businessFailure) {
            // Not transient - retrying won't make the funds appear. Fail the transfer and
            // let the transaction commit that outcome; don't propagate into Kafka retry.
            transfer.setStatus(TransferStatus.FAILED);
            transfer.setFailureReason(businessFailure.getMessage());
            transferRepository.save(transfer);
            return;
        }

        // A stale @Version on either account throws ObjectOptimisticLockingFailureException
        // here, rolling back this whole transaction (including the PROCESSING write above)
        // so the Kafka consumer's retry sees INITIATED again and replays cleanly.
        accountRepository.save(from);
        accountRepository.save(to);

        transfer.setStatus(TransferStatus.SUCCESS);
        transferRepository.save(transfer);
    }

    @Transactional
    public void markFailed(String transferId, String reason) {
        transferRepository.findById(transferId).ifPresent(transfer -> {
            transfer.setStatus(TransferStatus.FAILED);
            transfer.setFailureReason(reason);
            transferRepository.save(transfer);
        });
    }
}
