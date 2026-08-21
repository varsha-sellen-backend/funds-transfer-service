package com.vsellen.fundtransfer.service;

import com.vsellen.fundtransfer.domain.Account;
import com.vsellen.fundtransfer.domain.Transfer;
import com.vsellen.fundtransfer.domain.TransferStatus;
import com.vsellen.fundtransfer.dto.TransferRequest;
import com.vsellen.fundtransfer.dto.TransferResponse;
import com.vsellen.fundtransfer.event.TransferInitiatedEvent;
import com.vsellen.fundtransfer.exception.AccountNotFoundException;
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
        // Resolve both references before touching anything else, so a bad reference fails
        // fast with a clear error instead of surfacing later as an orphaned INITIATED
        // transfer that Kafka processing can never complete.
        Account fromAccount = resolveAccount(request.getFromAccountReference());
        Account toAccount = resolveAccount(request.getToAccountReference());

        Transfer transfer = new Transfer();
        transfer.setReferenceId(idempotencyKey);
        transfer.setFromAccountId(fromAccount.getId());
        transfer.setToAccountId(toAccount.getId());
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

    private Account resolveAccount(String externalReference) {
        return accountRepository.findByExternalReference(externalReference)
                .orElseThrow(() -> new AccountNotFoundException(externalReference));
    }

    public Transfer findById(String id) {
        return transferRepository.findById(id)
                .orElseThrow(() -> new TransferNotFoundException(id));
    }

    /**
     * Resolves the internal account ids on a transfer to their opaque external references.
     * Lives here rather than on the DTO so TransferResponse never needs repository access.
     */
    public TransferResponse toResponse(Transfer transfer) {
        String fromReference = accountRepository.findById(transfer.getFromAccountId())
                .orElseThrow().getExternalReference();
        String toReference = accountRepository.findById(transfer.getToAccountId())
                .orElseThrow().getExternalReference();
        return TransferResponse.from(transfer, fromReference, toReference);
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
