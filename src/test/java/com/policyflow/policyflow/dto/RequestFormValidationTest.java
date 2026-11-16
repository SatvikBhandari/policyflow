package com.policyflow.policyflow.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.policyflow.policyflow.enums.RequestCategory;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

class RequestFormValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    private static RequestForm validForm() {
        RequestForm form = new RequestForm();
        form.setTitle("Laptop for new hire");
        form.setCategory(RequestCategory.HARDWARE);
        form.setAmount(new BigDecimal("85000.00"));
        form.setDescription("Needed for onboarding.");
        return form;
    }

    private static Set<String> invalidFields(RequestForm form) {
        return validator.validate(form).stream()
                .map(violation -> violation.getPropertyPath().toString())
                .collect(Collectors.toSet());
    }

    @Test
    void validFormHasNoViolations() {
        assertEquals(Set.of(), invalidFields(validForm()));
    }

    @Test
    void smallestAllowedAmountIsAccepted() {
        RequestForm form = validForm();
        form.setAmount(new BigDecimal("0.01"));
        assertEquals(Set.of(), invalidFields(form));
    }

    @Test
    void blankTitleIsRejected() {
        RequestForm form = validForm();
        form.setTitle("   ");
        assertEquals(Set.of("title"), invalidFields(form));
    }

    @Test
    void missingCategoryIsRejected() {
        RequestForm form = validForm();
        form.setCategory(null);
        assertEquals(Set.of("category"), invalidFields(form));
    }

    @Test
    void zeroAndNegativeAmountsAreRejected() {
        for (String value : new String[] {"0", "0.00", "-5"}) {
            RequestForm form = validForm();
            form.setAmount(new BigDecimal(value));
            assertEquals(Set.of("amount"), invalidFields(form), "amount " + value);
        }
    }

    @Test
    void amountWithMoreThanTwoDecimalPlacesIsRejected() {
        RequestForm form = validForm();
        form.setAmount(new BigDecimal("10.555"));
        assertEquals(Set.of("amount"), invalidFields(form));
    }
}