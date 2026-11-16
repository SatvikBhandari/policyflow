package com.policyflow.policyflow.controller;

import java.math.BigDecimal;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.policyflow.policyflow.entity.User;
import com.policyflow.policyflow.enums.RequestCategory;
import com.policyflow.policyflow.exception.PolicyAdminException;
import com.policyflow.policyflow.security.CurrentUserProvider;
import com.policyflow.policyflow.service.PolicyViewService;

@Controller
@RequestMapping("/policies")
public class PolicyViewController {

    private final PolicyViewService policyViewService;
    private final CurrentUserProvider currentUserProvider;

    public PolicyViewController(PolicyViewService policyViewService, CurrentUserProvider currentUserProvider) {
        this.policyViewService = policyViewService;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping
    public String list(@RequestParam(name = "previewAmount", required = false) String previewAmount,
                       @RequestParam(name = "previewCategory", required = false) RequestCategory previewCategory,
                       Model model) {
        User user = currentUserProvider.getCurrentUser();

        model.addAttribute("policies", policyViewService.activePolicies(user));
        model.addAttribute("categories", RequestCategory.values());

        if (previewAmount != null) {
            model.addAttribute("previewAmount", previewAmount);
            model.addAttribute("previewCategory", previewCategory);

            BigDecimal amount = null;
            String problem = null;
            String text = previewAmount.trim();
            if (!text.isEmpty()) {
                try {
                    amount = new BigDecimal(text);
                } catch (NumberFormatException ex) {
                    problem = "Enter a valid amount, for example 85000.";
                }
            }
            if (problem == null) {
                try {
                    model.addAttribute("preview", policyViewService.preview(user, amount, previewCategory));
                } catch (PolicyAdminException ex) {
                    problem = ex.getMessage();
                }
            }
            model.addAttribute("previewError", problem);
        }
        return "policies";
    }
}