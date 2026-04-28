package com.vsellen.fundtransfer.controller;

import com.vsellen.fundtransfer.domain.Transfer;
import com.vsellen.fundtransfer.dto.TransferRequest;
import com.vsellen.fundtransfer.dto.TransferResponse;
import com.vsellen.fundtransfer.service.TransferService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/v1/transfers")
public class TransferController {

    private final TransferService service;

    public TransferController(TransferService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<TransferResponse> initiate(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody TransferRequest request) {

        Transfer transfer = service.initiate(request, idempotencyKey);
        TransferResponse response = TransferResponse.from(transfer);

        return ResponseEntity
                .accepted()
                .location(URI.create("/v1/transfers/" + transfer.getId()))
                .body(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<TransferResponse> getStatus(@PathVariable String id) {
        Transfer transfer = service.findById(id);
        return ResponseEntity.ok(TransferResponse.from(transfer));
    }
}
