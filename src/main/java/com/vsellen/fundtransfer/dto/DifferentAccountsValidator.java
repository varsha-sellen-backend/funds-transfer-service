package com.vsellen.fundtransfer.dto;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class DifferentAccountsValidator implements ConstraintValidator<DifferentAccounts, TransferRequest> {

    @Override
    public boolean isValid(TransferRequest request, ConstraintValidatorContext context) {
        if (request == null
                || request.getFromAccountReference() == null
                || request.getToAccountReference() == null) {
            // Blank/missing references are @NotBlank's concern, not this one.
            return true;
        }
        return !request.getFromAccountReference().equals(request.getToAccountReference());
    }
}
