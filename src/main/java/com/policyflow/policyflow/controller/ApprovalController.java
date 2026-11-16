package com.policyflow.policyflow.controller;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.policyflow.policyflow.dto.ApprovalViews.QueueItem;
import com.policyflow.policyflow.entity.Request;
import com.policyflow.policyflow.entity.User;
import com.policyflow.policyflow.enums.RequestCategory;
import com.policyflow.policyflow.enums.RequestStatus;
import com.policyflow.policyflow.enums.Role;
import com.policyflow.policyflow.exception.InvalidDecisionException;
import com.policyflow.policyflow.security.CurrentUserProvider;
import com.policyflow.policyflow.security.RoleLanding;
import com.policyflow.policyflow.service.ApprovalQueueService;
import com.policyflow.policyflow.service.ApprovalService;

/** Approval queues, review pages and decisions for Manager, Finance Officer and Department Head. */
@Controller
public class ApprovalController {

    private static final String APPROVER_ROLES = "hasAnyRole('MANAGER','FINANCE_OFFICER','DEPARTMENT_HEAD')";
    private static final String CONFLICT_MESSAGE =
            "Someone else just updated this request. Please check its latest status.";

    private final ApprovalService approvalService;
    private final ApprovalQueueService approvalQueueService;
    private final CurrentUserProvider currentUserProvider;

    public ApprovalController(ApprovalService approvalService,
                              ApprovalQueueService approvalQueueService,
                              CurrentUserProvider currentUserProvider) {
        this.approvalService = approvalService;
        this.approvalQueueService = approvalQueueService;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping({"/manager", "/finance", "/department"})
    @PreAuthorize(APPROVER_ROLES)
    public String queue(@RequestParam(name = "q", required = false) String q,
                        @RequestParam(name = "category", required = false) RequestCategory category,
                        @RequestParam(name = "page", defaultValue = "0") int page,
                        Model model) {
        User user = currentUserProvider.getCurrentUser();
        addArea(model, user);

        Page<QueueItem> results = approvalQueueService.search(user, q, category, page);
        model.addAttribute("queuePage", results);
        model.addAttribute("queue", results.getContent()); // the current page, as a plain list
        model.addAttribute("decided", approvalService.recentlyDecidedBy(user));

        model.addAttribute("qFilter", q == null ? "" : q);
        model.addAttribute("categoryFilter", category == null ? "" : category.name());
        model.addAttribute("filtering", (q != null && !q.isBlank()) || category != null);
        model.addAttribute("categories", RequestCategory.values());
        return "approval-queue";
    }

    @GetMapping({"/manager/requests/{id}", "/finance/requests/{id}", "/department/requests/{id}"})
    @PreAuthorize(APPROVER_ROLES)
    public String review(@PathVariable Long id, Model model) {
        User user = currentUserProvider.getCurrentUser();
        addArea(model, user);
        String base = RoleLanding.urlFor(user.getRole()) + "/requests/" + id;
        model.addAttribute("review", approvalService.getReview(id, user));
        model.addAttribute("approveUrl", base + "/approve");
        model.addAttribute("rejectUrl", base + "/reject");
        return "approval-review";
    }

    @PostMapping({"/manager/requests/{id}/approve", "/finance/requests/{id}/approve",
            "/department/requests/{id}/approve"})
    @PreAuthorize(APPROVER_ROLES)
    public String approve(@PathVariable Long id,
                          @RequestParam(name = "comment", required = false) String comment,
                          RedirectAttributes redirectAttributes) {
        User user = currentUserProvider.getCurrentUser();
        String areaPath = RoleLanding.urlFor(user.getRole());
        try {
            Request decided = approvalService.approve(id, user, comment);
            String outcome = decided.getStatus() == RequestStatus.APPROVED
                    ? " was fully approved."
                    : " was approved and passed to the next approver.";
            redirectAttributes.addFlashAttribute("successMessage", decided.getReference() + outcome);
            return "redirect:" + areaPath;
        } catch (InvalidDecisionException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:" + areaPath + "/requests/" + id;
        } catch (OptimisticLockingFailureException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", CONFLICT_MESSAGE);
            return "redirect:" + areaPath;
        }
    }

    @PostMapping({"/manager/requests/{id}/reject", "/finance/requests/{id}/reject",
            "/department/requests/{id}/reject"})
    @PreAuthorize(APPROVER_ROLES)
    public String reject(@PathVariable Long id,
                         @RequestParam(name = "comment", required = false) String comment,
                         RedirectAttributes redirectAttributes) {
        User user = currentUserProvider.getCurrentUser();
        String areaPath = RoleLanding.urlFor(user.getRole());
        try {
            Request decided = approvalService.reject(id, user, comment);
            redirectAttributes.addFlashAttribute("successMessage", decided.getReference() + " was rejected.");
            return "redirect:" + areaPath;
        } catch (InvalidDecisionException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:" + areaPath + "/requests/" + id;
        } catch (OptimisticLockingFailureException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", CONFLICT_MESSAGE);
            return "redirect:" + areaPath;
        }
    }

    private void addArea(Model model, User user) {
        String areaPath = RoleLanding.urlFor(user.getRole());
        model.addAttribute("areaPath", areaPath);
        model.addAttribute("activeNav", areaPath.substring(1)); // "manager", "finance" or "department"
        model.addAttribute("areaTitle", titleFor(user.getRole()));
    }

    private static String titleFor(Role role) {
        return switch (role) {
            case MANAGER -> "Manager approvals";
            case FINANCE_OFFICER -> "Finance review";
            case DEPARTMENT_HEAD -> "Department approvals";
            default -> "Approvals";
        };
    }
}