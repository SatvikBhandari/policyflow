package com.policyflow.policyflow.controller;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.policyflow.policyflow.dto.PolicyForm;
import com.policyflow.policyflow.entity.ApprovalPolicy;
import com.policyflow.policyflow.entity.User;
import com.policyflow.policyflow.enums.RequestCategory;
import com.policyflow.policyflow.enums.RequestPriority;
import com.policyflow.policyflow.enums.Role;
import com.policyflow.policyflow.exception.PolicyAdminException;
import com.policyflow.policyflow.security.CurrentUserProvider;
import com.policyflow.policyflow.service.PolicyAdminService;

import jakarta.validation.Valid;

@Controller
@RequestMapping("/admin/policies")
@PreAuthorize("hasRole('ADMIN')")
public class AdminPolicyController {

    private static final String LIST_REDIRECT = "redirect:/admin/policies";
    private static final String CONFLICT_MESSAGE =
            "Someone else changed this policy first. Nothing was saved. The latest version is shown.";

    private final PolicyAdminService policyAdminService;
    private final CurrentUserProvider currentUserProvider;

    public AdminPolicyController(PolicyAdminService policyAdminService, CurrentUserProvider currentUserProvider) {
        this.policyAdminService = policyAdminService;
        this.currentUserProvider = currentUserProvider;
    }

    // ---- list, health and preview ----

    @GetMapping
    public String list(@RequestParam(name = "previewAmount", required = false) String previewAmount,
                       @RequestParam(name = "previewCategory", required = false) RequestCategory previewCategory,
                       @RequestParam(name = "previewPriority", required = false) RequestPriority previewPriority,
                       @RequestParam(name = "previewDepartmentId", required = false) Long previewDepartmentId,
                       Model model) {
        User admin = currentUserProvider.getCurrentUser();

        model.addAttribute("policies", policyAdminService.list(admin));
        model.addAttribute("coverage", policyAdminService.coverage(admin));
        model.addAttribute("priorities", RequestPriority.values());
        model.addAttribute("departments", policyAdminService.activeDepartments());
        model.addAttribute("categories", RequestCategory.values());

        // The preview runs only when its form was submitted (previewAmount is present, even if empty)
        if (previewAmount != null) {
            model.addAttribute("previewAmount", previewAmount);
            model.addAttribute("previewCategory", previewCategory);
            model.addAttribute("previewPriority", previewPriority);
            model.addAttribute("previewDepartmentId", previewDepartmentId);

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
                    model.addAttribute("preview",
                            policyAdminService.preview(admin, amount, previewCategory, previewPriority, previewDepartmentId));
                } catch (PolicyAdminException ex) {
                    problem = ex.getMessage();
                }
            }
            model.addAttribute("previewError", problem);
        }
        return "admin-policies";
    }

    // ---- create ----

    @GetMapping("/new")
    public String newForm(Model model) {
        model.addAttribute("policyForm", new PolicyForm());
        addFormData(model, false, null);
        return "admin-policy-form";
    }

    @PostMapping
    public String create(@Valid @ModelAttribute("policyForm") PolicyForm form,
                         BindingResult bindingResult,
                         Model model,
                         RedirectAttributes redirectAttributes) {
        if (!bindingResult.hasErrors()) {
            User admin = currentUserProvider.getCurrentUser();
            try {
                ApprovalPolicy created = policyAdminService.create(admin, form);
                redirectAttributes.addFlashAttribute("successMessage",
                        "Policy \"" + created.getName() + "\" was created.");
                return LIST_REDIRECT;
            } catch (PolicyAdminException ex) {
                rejectField(bindingResult, ex);
            } catch (DataIntegrityViolationException ex) {
                bindingResult.rejectValue("name", "name.duplicate", "A policy with this name already exists.");
            }
        }
        addFormData(model, false, null);
        return "admin-policy-form";
    }

    // ---- edit ----

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        User admin = currentUserProvider.getCurrentUser();
        ApprovalPolicy policy = policyAdminService.get(admin, id);

        PolicyForm form = new PolicyForm();
        form.setVersion(policy.getVersion());
        form.setName(policy.getName());
        form.setDescription(policy.getDescription());
        form.setMinAmount(policy.getMinAmount());
        form.setMaxAmount(policy.getMaxAmount());
        form.setCategory(policy.getCategory());
        form.setPriority(policy.getPriority());
        form.setDepartmentId(policy.getDepartment() == null ? null : policy.getDepartment().getId());
        form.setApproverRoles(new LinkedHashSet<>(policy.getApproverRolesInOrder()));
        form.setActive(policy.isActive());

        model.addAttribute("policyForm", form);
        addFormData(model, true, policy);
        return "admin-policy-form";
    }

    @PostMapping("/{id}/edit")
    public String edit(@PathVariable Long id,
                       @Valid @ModelAttribute("policyForm") PolicyForm form,
                       BindingResult bindingResult,
                       Model model,
                       RedirectAttributes redirectAttributes) {
        User admin = currentUserProvider.getCurrentUser();
        if (!bindingResult.hasErrors()) {
            try {
                ApprovalPolicy updated = policyAdminService.update(admin, id, form);
                redirectAttributes.addFlashAttribute("successMessage",
                        "Policy \"" + updated.getName() + "\" was updated. Requests already in progress are not affected.");
                return LIST_REDIRECT;
            } catch (PolicyAdminException ex) {
                rejectField(bindingResult, ex);
            } catch (OptimisticLockingFailureException ex) {
                redirectAttributes.addFlashAttribute("errorMessage", CONFLICT_MESSAGE);
                return "redirect:/admin/policies/" + id + "/edit";
            } catch (DataIntegrityViolationException ex) {
                bindingResult.rejectValue("name", "name.duplicate", "A policy with this name already exists.");
            }
        }
        addFormData(model, true, policyAdminService.get(admin, id));
        return "admin-policy-form";
    }

    // ---- enable and disable ----

    @PostMapping("/{id}/activate")
    public String activate(@PathVariable Long id,
                           @RequestParam("version") Long version,
                           RedirectAttributes redirectAttributes) {
        return changeActive(id, true, version, redirectAttributes);
    }

    @PostMapping("/{id}/deactivate")
    public String deactivate(@PathVariable Long id,
                             @RequestParam("version") Long version,
                             RedirectAttributes redirectAttributes) {
        return changeActive(id, false, version, redirectAttributes);
    }

    // ---- helpers ----

    private String changeActive(Long id, boolean active, Long version, RedirectAttributes redirectAttributes) {
        User admin = currentUserProvider.getCurrentUser();
        try {
            ApprovalPolicy policy = policyAdminService.setActive(admin, id, active, version);
            redirectAttributes.addFlashAttribute("successMessage",
                    "Policy \"" + policy.getName() + "\" was " + (active ? "enabled." : "disabled."));
        } catch (OptimisticLockingFailureException ex) {
            redirectAttributes.addFlashAttribute("errorMessage",
                    "This policy was changed by someone else. Nothing was saved. Review it and try again.");
        }
        return LIST_REDIRECT;
    }

    private void rejectField(BindingResult bindingResult, PolicyAdminException ex) {
        if (ex.getField() != null) {
            bindingResult.rejectValue(ex.getField(), "policy.invalid", ex.getMessage());
        } else {
            bindingResult.reject("policy.invalid", ex.getMessage());
        }
    }

    private void addFormData(Model model, boolean editing, ApprovalPolicy target) {
        model.addAttribute("editing", editing);
        model.addAttribute("pageTitle", editing ? "Edit policy" : "New policy");
        model.addAttribute("formAction", editing ? "/admin/policies/" + target.getId() + "/edit" : "/admin/policies");
        model.addAttribute("target", target);
        model.addAttribute("approverChoices", List.of(Role.MANAGER, Role.FINANCE_OFFICER, Role.DEPARTMENT_HEAD));
        model.addAttribute("priorities", RequestPriority.values());
        model.addAttribute("categories", RequestCategory.values());
        model.addAttribute("departments", policyAdminService.activeDepartments());
    }
}