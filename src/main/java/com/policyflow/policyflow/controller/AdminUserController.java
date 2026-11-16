package com.policyflow.policyflow.controller;

import org.springframework.dao.DataIntegrityViolationException;
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

import com.policyflow.policyflow.dto.CreateUserForm;
import com.policyflow.policyflow.dto.EditUserForm;
import com.policyflow.policyflow.entity.User;
import com.policyflow.policyflow.enums.Role;
import com.policyflow.policyflow.exception.UserAdminException;
import com.policyflow.policyflow.security.CurrentUserProvider;
import com.policyflow.policyflow.service.AdminUserService;

import jakarta.validation.Valid;

@Controller
@RequestMapping("/admin/users")
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserController {

    private static final String LIST_REDIRECT = "redirect:/admin/users";

    private final AdminUserService adminUserService;
    private final CurrentUserProvider currentUserProvider;

    public AdminUserController(AdminUserService adminUserService, CurrentUserProvider currentUserProvider) {
        this.adminUserService = adminUserService;
        this.currentUserProvider = currentUserProvider;
    }

    // ---- list ----

    @GetMapping
    public String list(@RequestParam(name = "q", required = false) String q,
                       @RequestParam(name = "role", required = false) Role role,
                       @RequestParam(name = "status", required = false) String status,
                       Model model) {
        User admin = currentUserProvider.getCurrentUser();
        Boolean active = "active".equals(status) ? Boolean.TRUE : "inactive".equals(status) ? Boolean.FALSE : null;

        model.addAttribute("users", adminUserService.search(admin, q, role, active));
        model.addAttribute("q", q == null ? "" : q);
        model.addAttribute("selectedRole", role);
        model.addAttribute("status", status == null ? "" : status);
        model.addAttribute("roles", Role.values());
        return "admin-users";
    }

    // ---- create ----

    @GetMapping("/new")
    public String newForm(Model model) {
        model.addAttribute("userForm", new CreateUserForm());
        addFormData(model);
        return "admin-user-form";
    }

    @PostMapping
    public String create(@Valid @ModelAttribute("userForm") CreateUserForm form,
                         BindingResult bindingResult,
                         Model model,
                         RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            addFormData(model);
            return "admin-user-form";
        }
        User admin = currentUserProvider.getCurrentUser();
        try {
            User created = adminUserService.create(admin, form);
            redirectAttributes.addFlashAttribute("successMessage",
                    created.getFullName() + " was created. They must change the temporary password at first sign in.");
            return LIST_REDIRECT;
        } catch (UserAdminException ex) {
            rejectField(bindingResult, ex);
        } catch (DataIntegrityViolationException ex) {
            bindingResult.rejectValue("email", "email.duplicate", "A user with this email already exists.");
        }
        addFormData(model);
        return "admin-user-form";
    }

    // ---- edit ----

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        User admin = currentUserProvider.getCurrentUser();
        User target = adminUserService.get(admin, id);

        EditUserForm form = new EditUserForm();
        form.setFullName(target.getFullName());
        form.setRole(target.getRole());
        form.setDepartmentId(target.getDepartment().getId());

        model.addAttribute("userForm", form);
        addEditData(model, admin, target);
        return "admin-user-edit";
    }

    @PostMapping("/{id}/edit")
    public String edit(@PathVariable Long id,
                       @Valid @ModelAttribute("userForm") EditUserForm form,
                       BindingResult bindingResult,
                       Model model,
                       RedirectAttributes redirectAttributes) {
        User admin = currentUserProvider.getCurrentUser();
        if (!bindingResult.hasErrors()) {
            try {
                User updated = adminUserService.update(admin, id, form);
                redirectAttributes.addFlashAttribute("successMessage", updated.getFullName() + " was updated.");
                return LIST_REDIRECT;
            } catch (UserAdminException ex) {
                rejectField(bindingResult, ex);
            }
        }
        addEditData(model, admin, adminUserService.get(admin, id));
        return "admin-user-edit";
    }

    // ---- activate, deactivate, reset password ----

    @PostMapping("/{id}/activate")
    public String activate(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        return changeActive(id, true, redirectAttributes);
    }

    @PostMapping("/{id}/deactivate")
    public String deactivate(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        return changeActive(id, false, redirectAttributes);
    }

    @PostMapping("/{id}/reset-password")
    public String resetPassword(@PathVariable Long id,
                                @RequestParam(name = "temporaryPassword", required = false) String temporaryPassword,
                                RedirectAttributes redirectAttributes) {
        User admin = currentUserProvider.getCurrentUser();
        try {
            adminUserService.resetPassword(admin, id, temporaryPassword);
            redirectAttributes.addFlashAttribute("successMessage",
                    "Temporary password set. The user must change it at next sign in.");
            return LIST_REDIRECT;
        } catch (UserAdminException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/admin/users/" + id + "/edit";
        }
    }

    // ---- helpers ----

    private String changeActive(Long id, boolean active, RedirectAttributes redirectAttributes) {
        User admin = currentUserProvider.getCurrentUser();
        try {
            User user = adminUserService.setActive(admin, id, active);
            redirectAttributes.addFlashAttribute("successMessage",
                    user.getFullName() + (active ? " was activated." : " was deactivated."));
        } catch (UserAdminException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return LIST_REDIRECT;
    }

    private void rejectField(BindingResult bindingResult, UserAdminException ex) {
        if (ex.getField() != null) {
            bindingResult.rejectValue(ex.getField(), "user.invalid", ex.getMessage());
        } else {
            bindingResult.reject("user.invalid", ex.getMessage());
        }
    }

    private void addFormData(Model model) {
        model.addAttribute("roles", Role.values());
        model.addAttribute("departments", adminUserService.activeDepartments());
    }

    private void addEditData(Model model, User admin, User target) {
        addFormData(model);
        model.addAttribute("target", target);
        model.addAttribute("isSelf", admin.getId().equals(target.getId()));
    }
}