package com.policyflow.policyflow.controller;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.policyflow.policyflow.dto.AdminViews.AuditEntry;
import com.policyflow.policyflow.entity.Request;
import com.policyflow.policyflow.entity.User;
import com.policyflow.policyflow.exception.InvalidDecisionException;
import com.policyflow.policyflow.security.CurrentUserProvider;
import com.policyflow.policyflow.service.AdminOversightService;
import com.policyflow.policyflow.service.AuditLogService;

/** Administrator overview, audit log and stuck-request tools. */
@Controller
@RequestMapping("/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private static final String STUCK_REDIRECT = "redirect:/admin/stuck";

    private final AdminOversightService oversightService;
    private final AuditLogService auditLogService;
    private final CurrentUserProvider currentUserProvider;

    public AdminController(AdminOversightService oversightService,
                           AuditLogService auditLogService,
                           CurrentUserProvider currentUserProvider) {
        this.oversightService = oversightService;
        this.auditLogService = auditLogService;
        this.currentUserProvider = currentUserProvider;
    }

    // ---- overview ----

    @GetMapping
    public String overview(Model model) {
        User admin = currentUserProvider.getCurrentUser();
        model.addAttribute("overview", oversightService.overview(admin));
        return "admin-overview";
    }

    // ---- audit log ----

    @GetMapping("/audit")
    public String audit(@RequestParam(name = "action", required = false) String action,
                        @RequestParam(name = "entityType", required = false) String entityType,
                        @RequestParam(name = "actor", required = false) String actor,
                        @RequestParam(name = "from", required = false) String from,
                        @RequestParam(name = "to", required = false) String to,
                        @RequestParam(name = "page", defaultValue = "0") int page,
                        Model model) {
        User admin = currentUserProvider.getCurrentUser();

        Page<AuditEntry> entries = Page.empty();
        try {
            entries = auditLogService.search(admin, action, entityType, actor,
                    parseDate(from), parseDate(to), page);
        } catch (DateTimeParseException ex) {
            model.addAttribute("errorMessage", "Enter dates in the format yyyy-mm-dd.");
        } catch (IllegalArgumentException ex) {
            model.addAttribute("errorMessage", ex.getMessage());
        }

        model.addAttribute("entries", entries);
        model.addAttribute("actionFilter", blankIfNull(action));
        model.addAttribute("entityTypeFilter", blankIfNull(entityType));
        model.addAttribute("actorFilter", blankIfNull(actor));
        model.addAttribute("fromFilter", blankIfNull(from));
        model.addAttribute("toFilter", blankIfNull(to));
        model.addAttribute("actions", auditLogService.actionOptions(admin));
        model.addAttribute("entityTypes", auditLogService.entityTypeOptions(admin));
        return "admin-audit";
    }

    // ---- stuck requests ----

    @GetMapping("/stuck")
    public String stuck(Model model) {
        User admin = currentUserProvider.getCurrentUser();
        model.addAttribute("stuck", oversightService.findStuck(admin));
        return "admin-stuck";
    }

    @PostMapping("/stuck/{id}/close")
    public String close(@PathVariable Long id,
                        @RequestParam(name = "reason", required = false) String reason,
                        RedirectAttributes redirectAttributes) {
        User admin = currentUserProvider.getCurrentUser();
        try {
            Request closed = oversightService.forceClose(admin, id, reason);
            redirectAttributes.addFlashAttribute("successMessage",
                    closed.getReference() + " was closed. The requester can see your reason in the approval history.");
        } catch (InvalidDecisionException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        } catch (OptimisticLockingFailureException ex) {
            redirectAttributes.addFlashAttribute("errorMessage",
                    "Someone just updated this request. Nothing was changed. Please review the list again.");
        }
        return STUCK_REDIRECT;
    }

    // ---- helpers ----

    private static LocalDate parseDate(String text) {
        return text == null || text.isBlank() ? null : LocalDate.parse(text.trim());
    }

    private static String blankIfNull(String value) {
        return value == null ? "" : value;
    }
}