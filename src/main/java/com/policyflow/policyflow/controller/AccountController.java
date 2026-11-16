package com.policyflow.policyflow.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.policyflow.policyflow.dto.ChangePasswordForm;
import com.policyflow.policyflow.entity.User;
import com.policyflow.policyflow.exception.UserAdminException;
import com.policyflow.policyflow.security.CurrentUserProvider;
import com.policyflow.policyflow.security.RoleLanding;
import com.policyflow.policyflow.service.AccountService;

import jakarta.validation.Valid;

/** The logged-in user's own account page (change password). Open to every role. */
@Controller
@RequestMapping("/account/password")
public class AccountController {

    private final AccountService accountService;
    private final CurrentUserProvider currentUserProvider;

    public AccountController(AccountService accountService, CurrentUserProvider currentUserProvider) {
        this.accountService = accountService;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping
    public String form(Model model) {
        User user = currentUserProvider.getCurrentUser();
        model.addAttribute("passwordForm", new ChangePasswordForm());
        addPageData(model, user);
        return "change-password";
    }

    @PostMapping
    public String change(@Valid @ModelAttribute("passwordForm") ChangePasswordForm form,
                         BindingResult bindingResult,
                         Model model,
                         RedirectAttributes redirectAttributes) {
        User user = currentUserProvider.getCurrentUser();

        if (!bindingResult.hasErrors()) {
            try {
                accountService.changePassword(user, form.getCurrentPassword(),
                        form.getNewPassword(), form.getConfirmPassword());
                redirectAttributes.addFlashAttribute("successMessage", "Your password was changed.");
                return "redirect:/account/password";
            } catch (UserAdminException ex) {
                if (ex.getField() != null) {
                    bindingResult.rejectValue(ex.getField(), "password.invalid", ex.getMessage());
                } else {
                    bindingResult.reject("password.invalid", ex.getMessage());
                }
            }
        }
        addPageData(model, user);
        return "change-password";
    }

    private void addPageData(Model model, User user) {
        model.addAttribute("forced", user.isMustChangePassword());
        model.addAttribute("landingUrl", RoleLanding.urlFor(user.getRole()));
    }
}