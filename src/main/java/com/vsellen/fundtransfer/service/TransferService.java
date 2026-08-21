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
     *
     * Deliberately NOT @Transactional. It used to be, wrapping this entire method - including
     * the unique-constraint-violation fallback read in createAndPublish - in one service-level
     * transaction. That was never buying real atomicity here (the only write on the happy path
     * is a single INSERT, which is already atomic on its own), and it actively broke the
     * fallback on Postgres: see the comment on the catch block in createAndPublish for why.
     * With no service-level transaction, each call below to transferRepository /
     * accountRepository gets its own transaction automatically - Spring Data JPA's
     * SimpleJpaRepository is itself @Transactional per method (read-only for lookups,
     * read-write for saveAndFlush) - which is exactly the isolation the fallback needs.
     */
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
            // This call goes to the transferRepository bean, which Spring Data JPA wraps in
            // its own transaction (SimpleJpaRepository#saveAndFlush is @Transactional). That
            // transaction - not anything at this service layer - is what fails and rolls back
            // below.
            transferRepository.saveAndFlush(transfer);
        } catch (DataIntegrityViolationException raceLost) {
            // Postgres aborts an ENTIRE transaction the instant any statement inside it
            // errors - here, the unique-constraint violation on referenceId. Every later
            // statement on that same transaction then fails too, with "current transaction
            // is aborted, commands ignored until end of transaction block" (SQLState 25P02),
            // even a harmless SELECT - until a ROLLBACK happens. Catching the Java exception
            // does not by itself roll anything back.
            //
            // This used to be broken: when this whole method ran inside initiate()'s
            // @Transactional, the failed saveAndFlush and this fallback findByReferenceId
            // shared that same one aborted Postgres transaction, so the retry read would
            // itself throw against a real database - a bug the mocked TransferServiceTest
            // could never catch, since Mockito has no concept of a real connection or
            // transaction state; its stubbed findByReferenceId call "succeeds" no matter
            // what the previous stubbed call did.
            //
            // Now, with no service-level transaction: saveAndFlush's own transaction (opened
            // by the repository proxy, scoped to just that call) is already rolled back by
            // the time we're standing in this catch block - its boundary was that call
            // itself, which has already returned via this exception. This findByReferenceId
            // call is a fresh call to the repository bean, so it opens a brand new
            // transaction, on a connection that is not in Postgres's aborted state.
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
        // save() alone only marks the entity dirty in the persistence context; the actual
        // UPDATE is deferred to the next flush. The next calls here are
        // accountRepository.findById(...) - primary-key lookups, which go straight to
        // EntityManager.find() rather than a JPQL/criteria query. Hibernate's auto-flush
        // only fires before a query that might be affected by pending changes, and a PK
        // lookup on Account isn't recognized as something a pending Transfer UPDATE could
        // affect - so auto-flush does NOT trigger here. Without forcing it explicitly, this
        // UPDATE would just sit in the persistence context until the transaction commits at
        // the very end of this method, alongside the SUCCESS/FAILED write - i.e. Postgres
        // would go straight from INITIATED to SUCCESS/FAILED in one commit, and PROCESSING
        // would never actually be issued as its own statement.
        transferRepository.saveAndFlush(transfer);

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
