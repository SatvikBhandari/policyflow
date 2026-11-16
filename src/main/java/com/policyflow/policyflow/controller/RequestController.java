package com.policyflow.policyflow.controller;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.TransactionException;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.policyflow.policyflow.dto.RequestForm;
import com.policyflow.policyflow.entity.Request;
import com.policyflow.policyflow.entity.User;
import com.policyflow.policyflow.enums.RequestCategory;
import com.policyflow.policyflow.enums.RequestPriority;
import com.policyflow.policyflow.enums.RequestStatus;
import com.policyflow.policyflow.exception.NoEligibleApproverException;
import com.policyflow.policyflow.security.CurrentUserProvider;
import com.policyflow.policyflow.security.DuplicateSubmitGuard;
import com.policyflow.policyflow.service.ApprovalService;
import com.policyflow.policyflow.service.RequestSearchService;
import com.policyflow.policyflow.service.RequestService;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;

@Controller
@RequestMapping("/requests")
public class RequestController {

    private static final Logger log = LoggerFactory.getLogger(RequestController.class);

    private static final String ACTION_DRAFT = "draft";
    private static final String ACTION_SUBMIT = "submit";
    private static final String NOT_A_DRAFT =
            "This request is not a draft any more (it may already have been submitted), so it cannot be changed.";
    private static final String SAVE_FAILED =
            "We could not save your request right now. Your input is kept below. Please try again.";

    private final RequestService requestService;
    private final RequestSearchService requestSearchService;
    private final ApprovalService approvalService;
    private final CurrentUserProvider currentUserProvider;
    private final DuplicateSubmitGuard duplicateSubmitGuard;

    public RequestController(RequestService requestService,
                             RequestSearchService requestSearchService,
                             ApprovalService approvalService,
                             CurrentUserProvider currentUserProvider,
                             DuplicateSubmitGuard duplicateSubmitGuard) {
        this.requestService = requestService;
        this.requestSearchService = requestSearchService;
        this.approvalService = approvalService;
        this.currentUserProvider = currentUserProvider;
        this.duplicateSubmitGuard = duplicateSubmitGuard;
    }

    // ------------------------------------------------------------------ lists and details

    @GetMapping
    public String myRequests(@RequestParam(name = "q", required = false) String q,
                             @RequestParam(name = "status", required = false) RequestStatus status,
                             @RequestParam(name = "category", required = false) RequestCategory category,
                             @RequestParam(name = "from", required = false) String from,
                             @RequestParam(name = "to", required = false) String to,
                             @RequestParam(name = "page", defaultValue = "0") int page,
                             Model model) {
        User currentUser = currentUserProvider.getCurrentUser();

        Page<Request> results = Page.empty();
        try {
            results = requestSearchService.searchOwn(currentUser, q, status, category,
                    parseDate(from), parseDate(to), page);
        } catch (DateTimeParseException ex) {
            model.addAttribute("filterError", "Enter dates in the format yyyy-mm-dd.");
        } catch (IllegalArgumentException ex) {
            model.addAttribute("filterError", ex.getMessage());
        }

        model.addAttribute("requestsPage", results);
        model.addAttribute("requests", results.getContent()); // the current page, as a plain list

        model.addAttribute("qFilter", q == null ? "" : q);
        model.addAttribute("statusFilter", status == null ? "" : status.name());
        model.addAttribute("categoryFilter", category == null ? "" : category.name());
        model.addAttribute("fromFilter", from == null ? "" : from);
        model.addAttribute("toFilter", to == null ? "" : to);
        model.addAttribute("filtering", (q != null && !q.isBlank()) || status != null || category != null
                || (from != null && !from.isBlank()) || (to != null && !to.isBlank()));
        model.addAttribute("statuses", RequestStatus.values());
        model.addAttribute("categories", RequestCategory.values());
        return "my-requests";
    }

    @GetMapping("/{id}")
    public String requestDetails(@PathVariable Long id, Model model) {
        User currentUser = currentUserProvider.getCurrentUser();
        // Throws ResourceNotFoundException (404) if missing OR owned by someone else
        Request request = requestService.getFor(id, currentUser);
        model.addAttribute("request", request);
        model.addAttribute("history", approvalService.historyFor(request.getId()));
        return "request-details";
    }

    // ------------------------------------------------------------------ new request: submit or save as draft

    @GetMapping("/new")
    public String newRequestForm(Model model, HttpSession session) {
        model.addAttribute("requestForm", new RequestForm());
        return showForm(model, session, null);
    }

    @PostMapping
    public String submitRequest(@Valid @ModelAttribute("requestForm") RequestForm form,
                                BindingResult bindingResult,
                                @RequestParam(name = "formToken", required = false) String formToken,
                                @RequestParam(name = "action", defaultValue = ACTION_SUBMIT) String action,
                                HttpSession session,
                                Model model,
                                RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            return showForm(model, session, null);
        }

        // A token can be used once. A repeat POST (double-click, Back + resubmit, expired session) is refused.
        if (!duplicateSubmitGuard.consume(session, formToken)) {
            redirectAttributes.addFlashAttribute("infoMessage",
                    "This request was already submitted, or the form had expired. "
                            + "Check your list below before submitting again.");
            return "redirect:/requests";
        }

        try {
            User currentUser = currentUserProvider.getCurrentUser();

            if (ACTION_DRAFT.equals(action)) {
                Request draft = requestService.saveDraft(form, currentUser);
                redirectAttributes.addFlashAttribute("successMessage",
                        "Draft " + draft.getReference() + " was saved. You can edit it or submit it for approval from here.");
                return "redirect:/requests/" + draft.getId();
            }

            Request saved = requestService.createAndSubmit(form, currentUser);
            redirectAttributes.addFlashAttribute("successMessage",
                    "Request " + saved.getReference() + " was submitted successfully.");
            return "redirect:/requests";
        } catch (NoEligibleApproverException ex) {
            bindingResult.reject("routing.failed", ex.getMessage());
            return showForm(model, session, null);
        } catch (DataAccessException | TransactionException ex) {
            log.error("Could not save request", ex);
            bindingResult.reject("save.failed", SAVE_FAILED);
            return showForm(model, session, null);
        }
    }

    // ------------------------------------------------------------------ drafts: edit, submit, delete

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model, HttpSession session,
                           RedirectAttributes redirectAttributes) {
        User currentUser = currentUserProvider.getCurrentUser();
        Request draft = requestService.getFor(id, currentUser); // 404 when missing or not yours

        if (draft.getStatus() != RequestStatus.DRAFT) {
            redirectAttributes.addFlashAttribute("errorMessage", NOT_A_DRAFT);
            return "redirect:/requests/" + id;
        }

        RequestForm form = new RequestForm();
        form.setTitle(draft.getTitle());
        form.setCategory(draft.getCategory());
        form.setPriority(draft.getPriority());
        form.setAmount(draft.getAmount());
        form.setDescription(draft.getDescription());
        model.addAttribute("requestForm", form);
        return showForm(model, session, draft);
    }

    @PostMapping("/{id}/edit")
    public String saveEdit(@PathVariable Long id,
                           @Valid @ModelAttribute("requestForm") RequestForm form,
                           BindingResult bindingResult,
                           @RequestParam(name = "action", defaultValue = ACTION_DRAFT) String action,
                           HttpSession session,
                           Model model,
                           RedirectAttributes redirectAttributes) {
        User currentUser = currentUserProvider.getCurrentUser();
        Request draft = requestService.getFor(id, currentUser); // 404 when missing or not yours

        if (draft.getStatus() != RequestStatus.DRAFT) {
            redirectAttributes.addFlashAttribute("errorMessage", NOT_A_DRAFT);
            return "redirect:/requests/" + id;
        }
        if (bindingResult.hasErrors()) {
            return showForm(model, session, draft);
        }

        try {
            if (ACTION_SUBMIT.equals(action)) {
                Request submitted = requestService.updateAndSubmitDraft(id, form, currentUser);
                redirectAttributes.addFlashAttribute("successMessage",
                        "Request " + submitted.getReference() + " was submitted successfully.");
                return "redirect:/requests";
            }
            Request saved = requestService.updateDraft(id, form, currentUser);
            redirectAttributes.addFlashAttribute("successMessage", "Draft " + saved.getReference() + " was updated.");
            return "redirect:/requests/" + id;
        } catch (NoEligibleApproverException ex) {
            bindingResult.reject("routing.failed", ex.getMessage());
            return showForm(model, session, draft);
        } catch (IllegalStateException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", NOT_A_DRAFT);
            return "redirect:/requests/" + id;
        } catch (IllegalArgumentException ex) {
            bindingResult.reject("draft.invalid", ex.getMessage());
            return showForm(model, session, draft);
        } catch (DataAccessException | TransactionException ex) {
            log.error("Could not save draft", ex);
            bindingResult.reject("save.failed", SAVE_FAILED);
            return showForm(model, session, draft);
        }
    }

    @PostMapping("/{id}/submit")
    public String submitDraft(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        User currentUser = currentUserProvider.getCurrentUser();
        try {
            Request submitted = requestService.submitDraft(id, currentUser);
            redirectAttributes.addFlashAttribute("successMessage",
                    "Request " + submitted.getReference() + " was submitted for approval.");
        } catch (NoEligibleApproverException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        } catch (IllegalStateException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", NOT_A_DRAFT);
        } catch (DataAccessException ex) {
            // includes a clash with a simultaneous submit of the same draft
            log.warn("Draft {} could not be submitted: {}", id, ex.toString());
            redirectAttributes.addFlashAttribute("errorMessage",
                    "This request was just updated by another action. Check its current status below.");
        }
        return "redirect:/requests/" + id;
    }

    @PostMapping("/{id}/delete")
    public String deleteDraft(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        User currentUser = currentUserProvider.getCurrentUser();
        try {
            Request draft = requestService.getFor(id, currentUser); // 404 when missing or not yours
            String reference = draft.getReference();
            requestService.deleteDraft(id, currentUser);
            redirectAttributes.addFlashAttribute("successMessage", "Draft " + reference + " was deleted.");
            return "redirect:/requests";
        } catch (IllegalStateException ex) {
            redirectAttributes.addFlashAttribute("errorMessage",
                    "Only drafts can be deleted. A submitted request can only be cancelled.");
            return "redirect:/requests/" + id;
        }
    }

    // ------------------------------------------------------------------ cancel a submitted request

    @PostMapping("/{id}/cancel")
    public String cancelRequest(@PathVariable Long id,
                                @RequestParam(name = "reason", required = false) String reason,
                                RedirectAttributes redirectAttributes) {
        User currentUser = currentUserProvider.getCurrentUser();
        try {
            Request cancelled = requestService.cancel(id, currentUser, reason);
            redirectAttributes.addFlashAttribute("successMessage",
                    "Request " + cancelled.getReference() + " was cancelled.");
            return "redirect:/requests";
        } catch (IllegalStateException ex) {
            redirectAttributes.addFlashAttribute("errorMessage",
                    "This request can no longer be cancelled. Approval has already started, or it is already closed.");
            return "redirect:/requests/" + id;
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/requests/" + id;
        } catch (OptimisticLockingFailureException ex) {
            redirectAttributes.addFlashAttribute("errorMessage",
                    "Someone just updated this request. Please check its latest status.");
            return "redirect:/requests/" + id;
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Adds what the form page needs. For a draft being edited (non-null) the form posts to its edit URL;
     * for a new request it gets a fresh one-time token.
     */
    private String showForm(Model model, HttpSession session, Request draft) {
        boolean editing = draft != null;
        model.addAttribute("categories", RequestCategory.values());
        model.addAttribute("priorities", RequestPriority.values());
        model.addAttribute("editing", editing);
        model.addAttribute("pageTitle", editing ? "Edit draft" : "New request");
        model.addAttribute("activeNav", editing ? "my-requests" : "new-request");

        if (editing) {
            model.addAttribute("formAction", "/requests/" + draft.getId() + "/edit");
            model.addAttribute("draftId", draft.getId());
            model.addAttribute("draftReference", draft.getReference());
        } else {
            model.addAttribute("formAction", "/requests");
            model.addAttribute("formToken", duplicateSubmitGuard.issue(session));
        }
        return "new-request";
    }

    private static LocalDate parseDate(String text) {
        return text == null || text.isBlank() ? null : LocalDate.parse(text.trim());
    }
}